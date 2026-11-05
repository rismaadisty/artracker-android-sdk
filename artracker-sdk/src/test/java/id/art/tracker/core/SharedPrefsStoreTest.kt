package id.art.tracker.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPrefsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun roundTripsAndRemovesAcrossInstances() {
        val a = SharedPrefsStore(context, "artracker_test_rt")
        assertNull(a.getString("s"))
        assertEquals(7L, a.getLong("l", 7L))
        a.putString("s", "v")
        a.putLong("l", 42L)

        val b = SharedPrefsStore(context, "artracker_test_rt")
        assertEquals("v", b.getString("s"))
        assertEquals(42L, b.getLong("l", 0L))

        b.putString("s", null)
        assertNull(SharedPrefsStore(context, "artracker_test_rt").getString("s"))
    }

    @Test
    fun killSwitchStatePersistsThroughSharedPrefs() {
        val clock = Clock { 1_000L }
        KillSwitch(SharedPrefsStore(context, "artracker_test_ks"), clock).apply {
            trip()
            optedOut = true
        }
        val again = KillSwitch(SharedPrefsStore(context, "artracker_test_ks"), clock)
        assertEquals(true, again.optedOut)
        assertEquals(true, again.isActive())
    }
}
