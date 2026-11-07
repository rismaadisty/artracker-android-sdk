package id.art.tracker.e2e

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.art.tracker.ARTracker
import id.art.tracker.TestPlaygroundApplication
import id.art.tracker.playground.boot.QueueWiper
import id.art.tracker.playground.settings.EndpointClass
import id.art.tracker.playground.settings.EndpointGuard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun clean() = TestPlaygroundApplication.ingest.clear()

    @Test
    fun sdkPrivateFilesMatchTheNamesTheWiperDeletes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TestPlaygroundApplication.ingest.enqueue(503) // keep an item queued so the database certainly exists
        ARTracker.track("safety_probe")
        ARTracker.flush()
        val db = context.getDatabasePath("artracker.db")
        val crashDir = File(context.filesDir, "artracker")
        val deadline = System.nanoTime() + 10_000_000_000L
        while ((!db.exists() || !crashDir.isDirectory) && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("the SDK no longer creates ${db.name}: update QueueWiper", db.exists())
        assertTrue("the SDK no longer creates files/artracker: update QueueWiper", crashDir.isDirectory)

        // Run the wiper on COPIES: the live files belong to the running SDK.
        val dbDir = tmp.newFolder("databases")
        val filesDir = tmp.newFolder("files")
        db.parentFile!!.listFiles { f -> f.name.startsWith("artracker.db") }!!.forEach { it.copyTo(File(dbDir, it.name)) }
        crashDir.copyRecursively(File(filesDir, "artracker"))

        val deleted = QueueWiper.wipe(dbDir, filesDir)
        assertTrue("expected at least the database and the crash directory, got $deleted", deleted >= 2)
        assertFalse(File(dbDir, "artracker.db").exists())
        assertFalse(File(filesDir, "artracker").exists())
    }

    @Test
    fun theTestApplicationNeverUsesARemoteEndpoint() {
        assertEquals(EndpointClass.SAFE_LOCAL, EndpointGuard.classify(TestPlaygroundApplication.ingest.url))
        assertEquals(EndpointClass.SAFE_LOCAL, EndpointGuard.classify(TestPlaygroundApplication.targetsUrl))
    }
}
