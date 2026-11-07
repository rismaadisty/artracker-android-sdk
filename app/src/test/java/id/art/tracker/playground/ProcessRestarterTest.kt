package id.art.tracker.playground

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Restart order (like ProcessPhoenix): the MAIN process starts the `:restart` activity and only then kills itself, so the
 * launcher is started after the old process is gone and comes up in a fresh process that runs SampleApplication again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProcessRestarterTest {
    @After
    fun restoreExit() {
        RestartActivity.exitProcess = { Runtime.getRuntime().exit(0) }
    }

    @Test
    fun startsTheRestartActivityAndOnlyThenKillsTheCurrentProcess() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val order = mutableListOf<String>()
        ProcessRestarter.restart(app) { order += "kill: restart activity already started = ${shadowOf(app).peekNextStartedActivity() != null}" }
        assertEquals(listOf("kill: restart activity already started = true"), order)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(RestartActivity::class.java.name, started.component!!.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun restartActivityStartsTheLauncherInAFreshTaskAndExitsItsOwnProcess() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var exited = false
        RestartActivity.exitProcess = { exited = true }
        Robolectric.buildActivity(RestartActivity::class.java).create()
        val launcher = shadowOf(app).nextStartedActivity
        assertNotNull("no launcher intent was started", launcher)
        assertEquals("id.art.tracker.MainActivity", launcher.component!!.className)
        assertTrue(launcher.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(launcher.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertTrue("the :restart process must exit itself", exited)
    }
}
