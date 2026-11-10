package id.art.tracker.replay.screenshot

import android.util.Base64
import id.art.tracker.core.Clock
import id.art.tracker.core.Json
import id.art.tracker.core.SdkLog
import id.art.tracker.replay.ReplaySink
import id.art.tracker.replay.touch.TouchKind
import id.art.tracker.transport.NewReplayBatch
import id.art.tracker.transport.ReplayFormat
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** One masked, compressed screenshot. [width]/[height] are pixels of the JPEG, [viewportWidthDp]/[viewportHeightDp] the screen in dp. */
internal class ScreenFrame(
    val ts: Long,
    /** Sanitized screen name (redacted, path ids masked, URL-safe: see `ReplayHref`). */
    val screen: String,
    val width: Int,
    val height: Int,
    val viewportWidthDp: Int,
    val viewportHeightDp: Int,
    val jpeg: ByteArray,
    /** How many regions were painted over (sensitive views, `FLAG_SECURE` windows, the keyboard placeholder). */
    val masked: Int,
)

/** A touch in dp. */
internal class ScreenTouch(val ts: Long, val xDp: Int, val yDp: Int, val kind: TouchKind)

/**
 * Groups screenshot frames and touches into `artracker-screen-v1` chunks (gzipped JSON, at most [maxGzipBytes]):
 * `{ format, platform:"android", seq, frames:[{ts, screen, w, h, vw, vh, mime, data, masked}], touches:[{ts, x, y, kind}] }`.
 * A chunk closes [windowMs] after its first entry, on a session change, when the next frame would not fit, or on
 * [close]. `seq` counts the chunks of a session from 0.
 *
 * The size check before a close is an estimate (JPEG bytes survive base64 + gzip almost unchanged, so the estimate is
 * the JPEG size plus a margin and the per-frame metadata); the closed body is measured and, in the rare case it is
 * still too big, dropped.
 *
 * Not thread-safe: one caller (the replay processing context), like the encoder.
 */
internal class ScreenChunker(
    private val sink: ReplaySink,
    private val clock: Clock,
    private val environment: String,
    private val userId: () -> String?,
    private val maxGzipBytes: Int = DEFAULT_MAX_GZIP_BYTES,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    enum class Add { ADDED, TOO_LARGE }

    private val frames = ArrayList<ScreenFrame>()
    private val touches = ArrayList<ScreenTouch>()
    private var sessionId: String? = null
    private var estimated = 0
    private var openedAtMs = 0L
    private var firstTs = Long.MAX_VALUE
    private var lastTs = Long.MIN_VALUE
    private var hasError = false
    private var errorPending = false
    private var seqSession: String? = null
    private var nextSeq = 0

    /** Adds [frame]; [Add.TOO_LARGE] (nothing added) when it could not fit even an empty chunk. */
    fun addFrame(sessionId: String, frame: ScreenFrame): Add {
        val cost = estimate(frame)
        if (BASE_ESTIMATE + cost > maxGzipBytes) return Add.TOO_LARGE
        if (this.sessionId != null && this.sessionId != sessionId) close()
        if (estimated + cost > maxGzipBytes) close()
        open(sessionId)
        frames.add(frame)
        estimated += cost
        track(frame.ts)
        return Add.ADDED
    }

    /** Touches only ride on a chunk that already holds frames of the same session. */
    fun addTouch(sessionId: String, touch: ScreenTouch) {
        if (this.sessionId != sessionId || frames.isEmpty()) return
        if (estimated + TOUCH_ESTIMATE > maxGzipBytes) {
            close()
            return
        }
        touches.add(touch)
        estimated += TOUCH_ESTIMATE
        track(touch.ts)
    }

    fun markError() {
        if (frames.isNotEmpty()) hasError = true else errorPending = true
    }

    fun closeIfDue() {
        if (frames.isNotEmpty() && clock.nowMs() - openedAtMs >= windowMs) close()
    }

    /** Closes the open chunk now; a no-op when there is none. */
    fun close() {
        val session = sessionId
        if (frames.isEmpty() || session == null) return
        val snapshotFrames = ArrayList(frames)
        val snapshotTouches = ArrayList(touches)
        val first = firstTs
        val last = lastTs
        val flagged = hasError
        reset()

        if (seqSession != session) {
            seqSession = session
            nextSeq = 0
        }
        val seq = nextSeq++
        val body = try {
            gzip(Json.encode(document(seq, snapshotFrames, snapshotTouches), Json.REPLAY_MAX_DEPTH))
        } catch (e: Exception) {
            SdkLog.w("encoding screen chunk failed (${e.javaClass.simpleName}); dropping ${snapshotFrames.size} frames")
            return
        }
        if (body.size > maxGzipBytes) {
            SdkLog.w("screen chunk of ${snapshotFrames.size} frames is ${body.size} bytes gzipped; dropped (limit $maxGzipBytes)")
            return
        }
        val batch = NewReplayBatch(
            sessionId = session,
            gzipBody = body,
            eventCount = snapshotFrames.size + snapshotTouches.size,
            firstTsMs = first,
            lastTsMs = last,
            hasError = flagged,
            userId = userId(),
            environment = environment,
            format = ReplayFormat.SCREEN_V1,
        )
        try {
            sink.accept(batch)
        } catch (e: Exception) {
            SdkLog.w("replay sink failed (${e.javaClass.simpleName})")
        }
    }

    /** Drops the open chunk (and a pending error flag) without handing anything to the sink. */
    fun discard() {
        reset()
        errorPending = false
    }

    private fun open(sessionId: String) {
        if (frames.isEmpty()) {
            this.sessionId = sessionId
            openedAtMs = clock.nowMs()
            hasError = errorPending
            errorPending = false
            estimated = BASE_ESTIMATE
        }
    }

    private fun reset() {
        frames.clear()
        touches.clear()
        sessionId = null
        estimated = 0
        firstTs = Long.MAX_VALUE
        lastTs = Long.MIN_VALUE
        hasError = false
    }

    private fun track(ts: Long) {
        if (ts < firstTs) firstTs = ts
        if (ts > lastTs) lastTs = ts
    }

    private fun estimate(f: ScreenFrame): Int = (f.jpeg.size * DATA_FACTOR).toInt() + FRAME_ESTIMATE + f.screen.length

    private fun document(seq: Int, frames: List<ScreenFrame>, touches: List<ScreenTouch>): Map<String, Any?> {
        val doc = LinkedHashMap<String, Any?>(6)
        doc["format"] = ReplayFormat.SCREEN_V1
        doc["platform"] = PLATFORM
        doc["seq"] = seq
        doc["frames"] = frames.map { f ->
            linkedMapOf<String, Any?>(
                "ts" to f.ts,
                "screen" to f.screen,
                "w" to f.width,
                "h" to f.height,
                "vw" to f.viewportWidthDp,
                "vh" to f.viewportHeightDp,
                "mime" to MIME_JPEG,
                "data" to Base64.encodeToString(f.jpeg, Base64.NO_WRAP),
                "masked" to f.masked,
            )
        }
        doc["touches"] = touches.map { t ->
            linkedMapOf<String, Any?>("ts" to t.ts, "x" to t.xDp, "y" to t.yDp, "kind" to t.kind.name.lowercase())
        }
        return doc
    }

    private fun gzip(json: String): ByteArray {
        val out = ByteArrayOutputStream(json.length / 3 + 64)
        GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    companion object {
        const val DEFAULT_MAX_GZIP_BYTES: Int = 2 * 1024 * 1024
        const val DEFAULT_WINDOW_MS: Long = 10_000
        private const val PLATFORM = "android"
        private const val MIME_JPEG = "image/jpeg"
        private const val DATA_FACTOR = 1.05
        private const val FRAME_ESTIMATE = 220
        private const val TOUCH_ESTIMATE = 40
        private const val BASE_ESTIMATE = 120
    }
}
