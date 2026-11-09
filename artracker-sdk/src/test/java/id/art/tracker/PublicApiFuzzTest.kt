package id.art.tracker

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.ClassAvailability
import id.art.tracker.core.SdkLog
import id.art.tracker.transport.ReplayStore
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.testutil.QueueReader
import id.art.tracker.testutil.StrictJson
import java.math.BigDecimal
import java.math.BigInteger
import java.net.ServerSocket
import java.time.Duration
import java.util.Date
import java.util.Random
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Throws hostile input at every public entry point for [ITERATIONS] iterations with a fixed [SEED] (the run is
 * deterministic: a failure message carries the seed and iteration, and re-running reproduces it).
 *
 * Properties:
 * 1. No public call lets an exception (or Error) escape, on the main thread or on a background thread.
 * 2. The pipeline stays alive: a canary tracked at every checkpoint reaches the queue.
 * 3. Every payload that reaches the persistent queue is STRICT JSON (not just `org.json`-lenient), carries the
 *    required keys and respects the size caps (attributes 24 KB, name 255, message 4096, ids 128).
 * 4. `diagnostics()` keeps working.
 *
 * Transport points at a closed local port: every flush fails fast and leaves the rows in the queue, where the
 * test reads them straight from SQLite (and deletes what it has validated, so the 5 MB cap never evicts unseen rows).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublicApiFuzzTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val random = Random(SEED)
    private var iteration = -1
    private var lastOp = "<setup>"
    private val seenNames = HashMap<String, Int>()
    private var validated = 0
    private lateinit var activity: Activity
    private lateinit var label: TextView

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ClassAvailability.resetOverrides()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
        SdkLog.enabled = false
    }

    @Test
    fun hostileInput_neverEscapes_andEveryPersistedPayloadIsValid() {
        ARTracker.init(
            app,
            ARTrackerConfig(
                endpoint = "http://127.0.0.1:${closedPort()}",
                apiKey = "art_live_" + "a".repeat(43),
                environment = "fuzz",
                appVersion = "1.0",
                replay = ReplayConfig(enabled = true, sampleRate = 1.0),
            ),
        )
        assertTrue(ARTracker.isInitialized())
        ProcessLifecycleTestUtil.start()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        label = TextView(activity).apply { text = "fuzz screen" }
        activity.setContentView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label)
                addView(
                    EditText(activity).apply {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        setText("hunter2")
                    },
                )
            },
        )

        for (i in 0 until ITERATIONS) {
            iteration = i
            runIteration(i)
            if ((i + 1) % CHECKPOINT_EVERY == 0) checkpoint()
        }
        iteration = ITERATIONS
        checkpoint()

        assertTrue("validated only $validated of ~${ITERATIONS * 2} expected payloads: the fuzz did not exercise the pipeline", validated > 800)
        // Every kind of item was produced at some point (guards against a fuzz that silently stops recording).
        // Replay no longer travels through the ingest queue (it has its own upload queue), so there is no replay item.
        listOf("track_fuzz", "log", "exception", "\$identify", "screen_view", "session_start")
            .forEach { assertTrue("no persisted '$it' item over $ITERATIONS iterations (seed=$SEED)", (seenNames[it] ?: 0) > 0) }
    }

    // region iteration

    private fun runIteration(i: Int) {
        lastOp = "<iteration start>"
        // Opted-out stretches exist, but most iterations run enabled.
        if (i % 11 != 10) guard("optOut(false)") { ARTracker.optOut(false) }
        val onBackgroundThread = i % 4 == 3
        val ops = 1 + random.nextInt(4)
        val plan = List(ops) { random.nextInt(OP_COUNT) }
        val work = {
            // One log per iteration cycling through every level, so each LogLevel is exercised ~200 times.
            val level = LogLevel.entries[i % LogLevel.entries.size]
            val message = hostileString()
            val attrs = hostileMap()
            guard("log($level, ${describe(message)}, ${attrs.size} attrs)") { ARTracker.log(level, message, attrs) }
            for (op in plan) runOp(op)
            guard("diagnostics()") {
                val d = ARTracker.diagnostics()
                assertTrue("diagnostics().initialized", d.initialized)
                assertNotNull("diagnostics().sessionId", d.sessionId)
                assertTrue("pendingItems >= 0", d.pendingItems >= 0)
            }
        }
        if (onBackgroundThread) runOnBackgroundThread(work) else work()
        mutateScreenAndTouch()
        // Main-looper work (replay start/stop, frame capture, flush ticks) runs in virtual time.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
    }

    private fun runOp(op: Int) {
        when (op) {
            0 -> {
                val name = hostileString()
                val props = hostileMap()
                guard("track(${describe(name)}, ${props.size} props)") { ARTracker.track(name, props) }
            }
            1 -> {
                val level = LogLevel.entries[random.nextInt(LogLevel.entries.size)]
                val message = hostileString()
                val props = hostileMap()
                guard("log($level, ${describe(message)})") { ARTracker.log(level, message, props) }
            }
            2 -> {
                val t = hostileThrowable()
                val props = hostileMap()
                guard("captureException(${t.javaClass.simpleName})") { ARTracker.captureException(t, props) }
            }
            3 -> {
                val id = hostileString()
                val traits = if (random.nextInt(3) == 0) giantMap() else hostileMap()
                guard("identify(${describe(id)}, ${traits.size} traits)") { ARTracker.identify(id, traits) }
            }
            4 -> {
                val name = hostileString()
                guard("screen(${describe(name)})") { ARTracker.screen(name) }
            }
            5 -> guard("flush()") { ARTracker.flush() }
            6 -> guard("reset()") { ARTracker.reset() }
            7 -> {
                // Opting out discards the whole unsent queue, so keep it rare: most items must survive to be checked.
                val enabled = random.nextInt(5) == 0
                guard("optOut($enabled)") { ARTracker.optOut(enabled) }
            }
            8 -> guard("startReplay()") { ARTracker.startReplay() }
            9 -> guard("stopReplay()") { ARTracker.stopReplay() }
            10 -> {
                val any: Any = if (random.nextBoolean()) Any() else hostileString()
                guard("trackNavController(${describe(any)})") { ARTracker.trackNavController(any) }
            }
            11 -> guard("track(\"track_fuzz\")") { ARTracker.track("track_fuzz", hostileMap()) }
            else -> error("unknown op $op")
        }
    }

    /** Changes what the replay walker sees (including hostile text) and sends a tap through the window callback. */
    private fun mutateScreenAndTouch() {
        guard("mutate view tree") {
            label.text = if (random.nextInt(8) == 0) hostileString().take(70_000) else "tick $iteration"
        }
        if (random.nextInt(3) == 0) {
            guard("touch") {
                val now = SystemClock.uptimeMillis()
                val x = random.nextInt(400).toFloat()
                val y = random.nextInt(800).toFloat()
                for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now, action, x, y, 0)
                    activity.window.callback.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
        }
    }

    // endregion

    // region checkpoint: drain and validate what reached the queue

    private fun checkpoint() {
        lastOp = "<checkpoint>"
        guard("optOut(false) at checkpoint") { ARTracker.optOut(false) }
        val canary = "canary_${iteration}_${random.nextInt(1_000_000)}"
        guard("canary") { ARTracker.track(canary, mapOf("i" to iteration)) }
        guard("flush at checkpoint") { ARTracker.flush() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_500)) // chunk timer: replay chunks get enqueued
        runBlocking {
            withTimeout(60_000) {
                ARTrackerCore.instanceOrNull()!!.replayController?.awaitIdle()
                ARTrackerCore.instanceOrNull()!!.awaitIdle()
            }
        }
        val payloads = QueueReader.drain(app)
        val names = payloads.map { validate(it) }
        assertTrue(
            "canary '$canary' never reached the queue: the pipeline is dead or dropped it (seed=$SEED, iteration=$iteration)",
            canary in names,
        )
        for (n in names) seenNames.merge(n, 1, Int::plus)
        validated += names.size
        val d = ARTracker.diagnostics()
        assertTrue(d.initialized)
        assertFalse("SDK unexpectedly disabled", d.disabled)
    }

    /** Returns the item name. Fails with full context on any violation. */
    private fun validate(payload: String): String {
        val where = "seed=$SEED iteration=$iteration lastOp=$lastOp"
        val preview = payload.take(300).replace("\u0000", "\\u0000")
        StrictJson.validate(payload)?.let { fail("payload is not strict JSON: $it ($where) payload starts: $preview") }
        val json = JSONObject(payload)
        for (key in REQUIRED_KEYS) assertTrue("missing '$key' ($where) payload starts: $preview", json.has(key))
        val type = json.getString("type")
        assertTrue("bad type $type ($where)", type in setOf("event", "log", "session"))
        assertEquals("level only for logs ($where)", type == "log", json.has("level"))
        if (type == "log") assertTrue(json.getString("level") in setOf("debug", "info", "warn", "error", "fatal"))
        assertTrue("bad id ($where)", UUID_V7.matches(json.getString("id")))
        assertTrue("bad timestamp ($where)", TIMESTAMP.matches(json.getString("timestamp")))
        val name = json.getString("name")
        assertTrue("name ${name.length} > 255 ($where)", name.length <= 255)
        if (json.has("message")) {
            assertTrue("message ${json.getString("message").length} > 4096 ($where)", json.getString("message").length <= 4096)
        }
        assertTrue("session_id > 128 ($where)", json.getString("session_id").length <= 128)
        if (json.has("user_id")) assertTrue("user_id > 128 ($where)", json.getString("user_id").length <= 128)
        assertNotNull(json.optJSONObject("attributes"))
        // `attributes` is the last member; measure its raw UTF-8 size, not a re-serialisation.
        val start = payload.indexOf(ATTRIBUTES_MARKER)
        assertTrue("attributes not found ($where)", start >= 0)
        val rawAttributes = payload.substring(start + ATTRIBUTES_MARKER.length, payload.length - 1)
        val bytes = rawAttributes.toByteArray(Charsets.UTF_8).size
        assertTrue("attributes $bytes bytes > 24 KB for '$name' ($where)", bytes <= 24 * 1024)
        return name
    }

    // endregion

    // region hostile data

    /** Runs [block]; anything thrown (Exception or Error) fails the test with the seed/iteration context. */
    private inline fun guard(op: String, block: () -> Unit) {
        lastOp = op
        try {
            block()
        } catch (t: Throwable) {
            throw AssertionError("public API leaked ${t.javaClass.name}: ${t.message} (seed=$SEED iteration=$iteration op=$op)", t)
        }
    }

    private fun runOnBackgroundThread(block: () -> Unit) {
        var failure: Throwable? = null
        val t = thread(name = "fuzz-caller") {
            try {
                block()
            } catch (e: Throwable) {
                failure = e
            }
        }
        t.join(60_000)
        assertFalse("background caller hung (seed=$SEED iteration=$iteration op=$lastOp)", t.isAlive)
        failure?.let { throw it }
    }

    private fun describe(value: Any?): String = when (value) {
        is String -> if (value.length <= 24) "\"${value.replace("\u0000", "\\0")}\"" else "String(len=${value.length})"
        else -> value?.javaClass?.simpleName ?: "null"
    }

    private fun hostileString(): String = when (random.nextInt(16)) {
        0 -> ""
        1 -> "\u0000"
        2 -> "   "
        3 -> "\u2028\u2029\u0085"
        4 -> "\ud800" // lone high surrogate
        5 -> "x".repeat(listOf(1, 127, 128, 129, 254, 255, 256, 4095, 4096, 4097, 30_000)[random.nextInt(11)])
        6 -> huge(HUGE_A)
        7 -> huge(HUGE_NUL)
        8 -> "alice@example.com 4111 1111 1111 1111 Bearer abcdefghijklmnop 081234567890"
        9 -> "\ud83d\ude00".repeat(300) // astral: the 255/128 cut must not split a surrogate pair
        10 -> huge(HUGE_DIGITS)
        11 -> "{\"a\":[1,2,3]} \"quoted\" back\\slash \n\r\t\b\u000C </script>"
        12 -> "eyJhbGciOi.eyJzdWIiOiIx.signature art_live_" + "q".repeat(43)
        13 -> randomUnicode(1 + random.nextInt(60))
        14 -> "a" + "\u0000".repeat(random.nextInt(20)) + "z"
        else -> "ordinary text $iteration"
    }

    /** The 1 MB strings are the slow ones to redact; use them in about one call in six, 30 KB otherwise. */
    private fun huge(full: String): String = if (random.nextInt(6) == 0) full else full.take(30_000)

    private fun randomUnicode(length: Int): String {
        val sb = StringBuilder()
        repeat(length) {
            val cp = when (random.nextInt(4)) {
                0 -> random.nextInt(0x80)
                1 -> 0x80 + random.nextInt(0x700)
                2 -> 0x800 + random.nextInt(0xD000)
                else -> 0x10000 + random.nextInt(0x1000)
            }
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun hostileKey(): Any? = when (random.nextInt(14)) {
        0 -> ""
        1 -> "\u0000"
        2 -> huge(HUGE_A)
        3 -> 42
        4 -> 1.5
        5 -> null
        6 -> listOf(1, 2)
        7 -> ThrowingToString()
        8 -> SelfReferencingNode()
        9 -> "password"
        10 -> "key_${random.nextInt(50)}"
        11 -> "k".repeat(300)
        12 -> true
        else -> "key_${random.nextInt(1000)}"
    }

    private fun hostileValue(depth: Int): Any? = when (random.nextInt(34)) {
        0 -> null
        1 -> Double.NaN
        2 -> Double.POSITIVE_INFINITY
        3 -> Double.NEGATIVE_INFINITY
        4 -> Float.NaN
        5 -> Float.NEGATIVE_INFINITY
        6 -> random.nextInt()
        7 -> random.nextLong()
        8 -> random.nextBoolean()
        9 -> hostileString()
        10 -> ByteArray(random.nextInt(20_000)) { random.nextInt(256).toByte() }
        11 -> IntArray(random.nextInt(3000)) { it }
        12 -> BigDecimal("1e400")
        13 -> BigInteger.TEN.pow(500)
        14 -> Date(random.nextLong() % 4_000_000_000_000L)
        15 -> LogLevel.WARN
        16 -> ThrowingToString()
        17 -> ErrorThrowingToString()
        18 -> SelfReferencingNode() // toString recurses forever: StackOverflowError
        19 -> HugeToString()
        20 -> NonFiniteNumber()
        21 -> EvilIterable()
        22 -> EvilMap()
        23 -> deeplyNested(if (random.nextInt(10) == 0) 10_000 else 200)
        24 -> selfReferencingMap()
        25 -> selfReferencingList()
        26 -> List(random.nextInt(5_000)) { it } // huge list
        27 -> { { "lambda" } } // Function0: dropped by the redactor
        28 -> hostileThrowable()
        29 -> Thread.currentThread()
        30 -> 'x'
        31 -> if (depth < 3) hostileMap(depth + 1) else "leaf"
        32 -> if (depth < 3) List(random.nextInt(6)) { hostileValue(depth + 1) } else "leaf"
        else -> Any()
    }

    private fun hostileMap(depth: Int = 0): Map<String, Any?> {
        // Declared Map<String, Any?> for the API, but keys can be anything at runtime (erased generics).
        val m = LinkedHashMap<Any?, Any?>()
        when (random.nextInt(10)) {
            0 -> return emptyMap()
            1 -> repeat(random.nextInt(2_000)) { m["key_$it"] = hostileValue(depth + 1) }
            else -> repeat(1 + random.nextInt(8)) { m[hostileKey()] = hostileValue(depth) }
        }
        @Suppress("UNCHECKED_CAST")
        return m as Map<String, Any?>
    }

    /** Thousands of entries with long strings: far beyond the 24 KB budget. */
    private fun giantMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        repeat(2_000) { m["trait_$it"] = "v".repeat(1 + random.nextInt(2_000)) }
        return m
    }

    private fun deeplyNested(levels: Int): Any? {
        var v: Any? = "leaf"
        repeat(levels) { v = if (it % 2 == 0) mapOf("k" to v) else listOf(v) }
        return v
    }

    private fun selfReferencingMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["self"] = m
        m["name"] = "cyclic"
        return m
    }

    private fun selfReferencingList(): List<Any?> {
        val l = ArrayList<Any?>()
        l.add(l)
        l.add(1)
        return l
    }

    private fun hostileThrowable(): Throwable = when (random.nextInt(9)) {
        0 -> ThrowingMessageThrowable()
        1 -> ThrowingStackTraceThrowable()
        2 -> RuntimeException("deep ${random.nextInt(8)}").also { it.stackTrace = bigStack(5_000) }
        3 -> RuntimeException("cyclic cause a").also { a ->
            val b = IllegalStateException("cyclic cause b", a)
            a.initCause(b)
        }
        4 -> SelfCauseThrowable()
        5 -> RuntimeException(hostileString())
        6 -> {
            var t: Throwable = RuntimeException("long chain")
            repeat(40) { t = IllegalStateException("cause $it", t) }
            t
        }
        7 -> ThrowingToStringThrowable()
        else -> IllegalArgumentException("plain ${random.nextInt(5)}")
    }

    private fun bigStack(frames: Int): Array<StackTraceElement> =
        Array(frames) { StackTraceElement("com.example.Gen${it % 97}", "method${it % 13}", "File$it.kt", it) }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    // endregion

    private class ThrowingToString {
        override fun toString(): String = throw IllegalStateException("toString exploded")
    }

    /** Throws an Error, which `catch (e: Exception)` does not catch. */
    private class ErrorThrowingToString {
        override fun toString(): String = throw AssertionError("toString exploded with an Error")
    }

    /** A linked structure whose generated-style toString follows the cycle: StackOverflowError. */
    private class SelfReferencingNode {
        val next: Any = this
        override fun toString(): String = "Node(next=$next)"
    }

    private class HugeToString {
        override fun toString(): String = HUGE_A
    }

    /** A Number that is not Double/Float/BigDecimal but prints a token that is not JSON. */
    private class NonFiniteNumber : Number() {
        override fun toByte(): Byte = 0
        override fun toDouble(): Double = Double.NaN
        override fun toFloat(): Float = Float.NaN
        override fun toInt(): Int = 0
        override fun toLong(): Long = 0
        override fun toShort(): Short = 0
        override fun toString(): String = "NaN"
    }

    private class EvilIterable : Iterable<Any?> {
        override fun iterator(): Iterator<Any?> = throw IllegalStateException("iterator exploded")
    }

    private class EvilMap : AbstractMap<String, Any?>() {
        override val entries: Set<Map.Entry<String, Any?>> get() = throw IllegalStateException("entries exploded")
    }

    private class ThrowingMessageThrowable : RuntimeException() {
        override val message: String? get() = throw IllegalStateException("getMessage exploded")
    }

    private class ThrowingStackTraceThrowable : RuntimeException("stack trace exploded") {
        override fun getStackTrace(): Array<StackTraceElement> = throw IllegalStateException("getStackTrace exploded")
    }

    private class ThrowingToStringThrowable : RuntimeException("toString exploded") {
        override fun toString(): String = throw IllegalStateException("toString exploded")
    }

    private class SelfCauseThrowable : RuntimeException("self cause") {
        override val cause: Throwable get() = this
    }

    private companion object {
        const val SEED = 0x5EEDA27L
        const val ITERATIONS = 1000
        const val CHECKPOINT_EVERY = 25
        const val OP_COUNT = 12
        const val ATTRIBUTES_MARKER = ",\"attributes\":"
        val REQUIRED_KEYS = listOf("id", "type", "name", "timestamp", "session_id", "attributes")
        val UUID_V7 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        val TIMESTAMP = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")
        val HUGE_A = "a".repeat(1_000_000)
        val HUGE_NUL = "\u0000".repeat(1_000_000)
        val HUGE_DIGITS = "1 ".repeat(500_000)
    }
}
