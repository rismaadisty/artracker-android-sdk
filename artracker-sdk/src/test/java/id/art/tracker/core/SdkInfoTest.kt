package id.art.tracker.core

import id.art.tracker.sdk.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class SdkInfoTest {
    @Test
    fun name_isArtrackerAndroid() {
        assertEquals("artracker-android", SdkInfo.NAME)
    }

    @Test
    fun version_matchesBuildConfig() {
        assertEquals(BuildConfig.SDK_VERSION, SdkInfo.VERSION)
    }
}
