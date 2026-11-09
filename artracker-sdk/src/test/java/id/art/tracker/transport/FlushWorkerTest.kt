package id.art.tracker.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import id.art.tracker.core.Clock
import id.art.tracker.core.KillSwitch
import id.art.tracker.core.SharedPrefsStore
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.FakeQueue
import id.art.tracker.testutil.FakeReplayBackend
import id.art.tracker.testutil.InMemoryStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlushWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()

    private val backend by lazy { FakeReplayBackend() }

    @Before
    fun setUp() {
        FlusherRegistry.current = null
        FlusherRegistry.replay = null
        context.deleteDatabase("artracker.db")
        context.deleteDatabase(ReplayStore.DB_NAME)
    }

    private var workManagerInitialised = false

    private fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManagerInitialised = true
    }

    @After
    fun tearDown() {
        FlusherRegistry.current = null
        FlusherRegistry.replay = null
        runCatching { server.shutdown() }
        runCatching { backend.shutdown() }
        context.deleteDatabase(ReplayStore.DB_NAME)
        if (workManagerInitialised) {
            // Release WorkManager's Room database so Robolectric's CloseGuard stays quiet.
            // Room is only a runtime (not compile) dependency here, hence reflection.
            val db = WorkManagerImpl.getInstance(context).workDatabase
            Class.forName("androidx.room.RoomDatabase").getMethod("close").invoke(db)
        }
        context.deleteDatabase("artracker.db")
    }

    private fun worker() = TestListenableWorkerBuilder<FlushWorker>(context).build()

    private fun doWork(): ListenableWorker.Result = runBlocking { worker().doWork() }

    @Test
    fun schedule_createsUniqueWorkWithConnectedConstraint() {
        initWorkManager()
        val scheduler = WorkManagerScheduler(context)
        scheduler.scheduleFlush()
        scheduler.scheduleFlush() // KEEP: a second call must not create or replace anything

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("artracker-flush").get()
        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(info.tags.contains(FlushWorker::class.java.name))
    }

    @Test
    fun schedule_usesExponentialBackoffOf30Seconds() {
        initWorkManager()
        WorkManagerScheduler(context).scheduleFlush()
        val info = WorkManager.getInstance(context).getWorkInfosForUniqueWork("artracker-flush").get().single()
        // WorkInfo does not expose the backoff policy; the request is built from these constants.
        assertEquals(30L, WorkManagerScheduler.BACKOFF_SECONDS)
        assertEquals(0, info.runAttemptCount)
    }

    @Test
    fun worker_withoutCoreOrConfig_succeeds() {
        assertEquals(ListenableWorker.Result.success(), doWork())
    }

    @Test
    fun worker_withRegistryFlusher_flushesAndSucceeds() {
        val clock = FakeClock(1_700_000_000_000L)
        val queue = FakeQueue()
        queue.insert(listOf(StoredItem("a", 2, 1L, "{\"id\":\"a\"}")))
        var sent = 0
        FlusherRegistry.current = flusher(queue, clock) { sent++; SendResult.Accepted }

        assertEquals(ListenableWorker.Result.success(), doWork())
        assertEquals(1, sent)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun worker_networkFailure_retries() {
        val clock = FakeClock(1_700_000_000_000L)
        val queue = FakeQueue()
        queue.insert(listOf(StoredItem("a", 2, 1L, "{\"id\":\"a\"}")))
        FlusherRegistry.current = flusher(queue, clock) { SendResult.NetworkError(java.io.IOException("offline")) }

        assertEquals(ListenableWorker.Result.retry(), doWork())
        assertEquals(1, queue.pendingCount())
    }

    @Test
    fun worker_flusherThrowsAnError_retriesAndKeepsTheItems() {
        val clock = FakeClock(1_700_000_000_000L)
        val queue = FakeQueue()
        queue.insert(listOf(StoredItem("a", 2, 1L, "{\"id\":\"a\"}")))
        FlusherRegistry.current = flusher(queue, clock) { throw OutOfMemoryError("gzip") }

        assertEquals(ListenableWorker.Result.retry(), doWork())
        assertEquals(1, queue.pendingCount())
        assertEquals(0, queue.inFlightCount())
    }

    @Test
    fun worker_paused_retries() {
        val clock = FakeClock(1_700_000_000_000L)
        val queue = FakeQueue()
        queue.insert(listOf(StoredItem("a", 2, 1L, "{\"id\":\"a\"}")))
        FlusherRegistry.current = flusher(queue, clock) { SendResult.RetryAfter(60_000) }

        assertEquals(ListenableWorker.Result.retry(), doWork())
    }

    @Test
    fun worker_withSavedConfig_buildsOwnFlusherAndKeepsInFlightBatches() {
        val config = TransportConfigStore(SharedPrefsStore(context, TransportConfigStore.PREFS_NAME))
        server.enqueue(MockResponse().setResponseCode(202))
        server.start()
        config.save(server.url("/").toString().trimEnd('/'), "art_live_" + "k".repeat(32), "staging", "9.9")

        val seed = EventStore(context, Clock.SYSTEM)
        try {
            seed.insert(listOf(StoredItem("held", 0, 1L, "{\"id\":\"held\"}")))
            // Simulates a live SDK instance mid-send: the worker must not hand this batch out again.
            val inFlight = seed.claimBatch()!!
            seed.insert(listOf(StoredItem("fresh", 2, 2L, "{\"id\":\"fresh\"}")))

            assertEquals(ListenableWorker.Result.success(), doWork())

            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/ingest", request.path)
            assertEquals("art_live_" + "k".repeat(32), request.getHeader("X-Artracker-Key"))
            val body = java.util.zip.GZIPInputStream(request.body.inputStream()).use { String(it.readBytes()) }
            assertTrue(body, body.contains("\"fresh\""))
            assertTrue(body, body.contains("\"environment\":\"staging\""))
            assertTrue(body, body.contains("\"app_version\":\"9.9\""))
            assertTrue(body, !body.contains("\"held\""))
            assertEquals(0, seed.pendingCount())
            seed.complete(inFlight.id)
        } finally {
            seed.close()
        }
    }

    private fun seedReplayBatches(count: Int) {
        TransportConfigStore(SharedPrefsStore(context, TransportConfigStore.PREFS_NAME))
            .save(backend.endpoint, "art_live_" + "k".repeat(32), "staging", "9.9")
        val store = ReplayStore(context, Clock.SYSTEM)
        try {
            repeat(count) {
                store.enqueue(NewReplayBatch("session-fresh-1", byteArrayOf(1, 2, 3), 1, 1_000, 2_000, false, null, "staging"))
            }
        } finally {
            store.close()
        }
    }

    private fun pendingReplayBatches(): Int {
        val store = ReplayStore(context, Clock.SYSTEM)
        try {
            return store.pendingCount()
        } finally {
            store.close()
        }
    }

    @Test
    fun freshProcess_uploadsPendingReplayBatches() {
        seedReplayBatches(2)

        assertEquals(ListenableWorker.Result.success(), doWork())

        assertEquals(2, backend.requestsTo(FakeReplayBackend.ACK).size)
        assertEquals(
            listOf(0, 1),
            backend.requestsTo(FakeReplayBackend.CHUNK_URL).map { it.json().getInt("chunk_seq") },
        )
        assertEquals(0, pendingReplayBatches())
    }

    @Test
    fun replayRetry_returnsWorkerRetry() {
        seedReplayBatches(1)
        backend.chunkUrl = { MockResponse().setResponseCode(503) }

        assertEquals(ListenableWorker.Result.retry(), doWork())
        assertEquals(1, pendingReplayBatches())
    }

    @Test
    fun noReplayDatabase_doesNotCreateOne() {
        TransportConfigStore(SharedPrefsStore(context, TransportConfigStore.PREFS_NAME))
            .save(backend.endpoint, "art_live_" + "k".repeat(32), "staging", "9.9")

        assertEquals(ListenableWorker.Result.success(), doWork())

        assertTrue(!context.getDatabasePath(ReplayStore.DB_NAME).exists())
    }

    private fun flusher(queue: ItemQueue, clock: Clock, send: () -> SendResult) = Flusher(
        queue,
        PayloadEncoder { emptyMap() },
        { send() },
        KillSwitch(InMemoryStore(), clock),
        clock,
        Backoff(),
        BackgroundScheduler { },
    )
}
