package id.art.tracker.core

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The `id.art.tracker.BUILD_ID` manifest meta-data: what the SDK reads, and what it refuses to send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuildIdTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = BuildId.META_DATA_KEY

    private fun withMetaData(bundle: Bundle?): String? {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo!!.metaData = bundle
        return BuildId.read(context)
    }

    private fun withValue(put: Bundle.() -> Unit): String? = withMetaData(Bundle().apply(put))

    @Test
    fun stringValueIsRead() {
        assertEquals("git-1a2b3c4d5e6f", withValue { putString(key, "git-1a2b3c4d5e6f") })
    }

    @Test
    fun valueIsTrimmed() {
        assertEquals("abc", withValue { putString(key, "  abc \n") })
    }

    @Test
    fun integerValueBecomesItsDecimalString() {
        // A CI build number written as android:value="4512" is stored by Android as an Integer.
        assertEquals("4512", withValue { putInt(key, 4512) })
    }

    @Test
    fun floatValueIsIgnored() {
        // android:value="12e45" is parsed by Android as a Float: sending that would be sending something else.
        assertNull(withValue { putFloat(key, 1.2e5f) })
    }

    @Test
    fun booleanValueIsIgnored() {
        assertNull(withValue { putBoolean(key, true) })
    }

    @Test
    fun blankValueIsOmitted() {
        assertNull(withValue { putString(key, "   ") })
    }

    @Test
    fun missingMetaDataIsOmitted() {
        assertNull(withMetaData(null))
        assertNull(withValue { putString("some.other.key", "x") })
    }

    @Test
    fun longValueIsClippedTo128() {
        assertEquals(128, withValue { putString(key, "a".repeat(300)) }!!.length)
    }

    @Test
    fun clippingNeverSplitsASurrogatePair() {
        // 127 letters and one emoji (two chars): the cut at 128 would leave a lone high surrogate.
        assertEquals("a".repeat(127), withValue { putString(key, "a".repeat(127) + "😀") })
    }

    @Test
    fun anIdTheRedactionWouldRewriteIsDropped() {
        // The pipeline masks card-like digit runs in every attribute value: the id would arrive as "**** **** **** 1111"
        // and match no mapping. Better no id than a wrong one.
        assertNull(withValue { putString(key, "4111-1111-1111-1111") })
    }

    @Test
    fun idsOfTheRecommendedShapesPassTheRedactionCheck() {
        assertEquals("git-1a2b3c4d5e6f", withValue { putString(key, "git-1a2b3c4d5e6f") })
        assertEquals("123e4567-e89b-12d3-a456-426614174000", withValue { putString(key, "123e4567-e89b-12d3-a456-426614174000") })
        assertEquals("4512", withValue { putInt(key, 4512) })
    }

    @Test
    fun packageManagerFailureOmitsTheBuildId() {
        val broken = object : ContextWrapper(context) {
            override fun getPackageManager(): PackageManager = throw IllegalStateException("package manager is gone")
        }
        assertNull(BuildId.read(broken))
    }
}
