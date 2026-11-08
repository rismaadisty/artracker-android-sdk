package id.art.tracker.playground.boot

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackendGrantStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store get() = SharedPrefsBackendGrantStore(context)

    @Before
    fun clean() {
        context.getSharedPreferences("playground_boot", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun consumeWithoutIssueIsFalse() {
        assertFalse(store.consume(1_000))
    }

    @Test
    fun issueThenConsumeIsTrueOnlyOnce() {
        store.issue(1_000)
        assertTrue(store.consume(2_000))
        assertFalse(store.consume(2_001))
    }

    @Test
    fun consumeAtExactlyTtlIsTrue() {
        store.issue(1_000)
        assertTrue(store.consume(121_000))
    }

    @Test
    fun consumeAfterTtlIsFalseAndClearsTheGrant() {
        store.issue(1_000)
        assertFalse(store.consume(121_001))
        assertFalse(store.consume(121_002))
    }

    @Test
    fun issueTwiceKeepsOnlyTheLatest() {
        store.issue(1_000)
        store.issue(500_000)
        assertTrue(store.consume(500_001))
        assertFalse(store.consume(500_002))
    }

    @Test
    fun grantSurvivesANewStoreInstanceLikeAProcessRestart() {
        store.issue(1_000)
        assertTrue(SharedPrefsBackendGrantStore(context).consume(1_500))
    }

    @Test
    fun runMarkerDefaultsToNotRemoteAndPersists() {
        val marker = SharedPrefsRunMarkerStore(context)
        assertFalse(marker.lastRunRemote)
        marker.lastRunRemote = true
        assertEquals(true, SharedPrefsRunMarkerStore(context).lastRunRemote)
    }
}
