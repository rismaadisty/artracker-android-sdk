package id.art.tracker.perf

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkProbeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun caps(vararg transports: Int): NetworkCapabilities {
        val c = ShadowNetworkCapabilities.newInstance()
        transports.forEach { shadowOf(c).addTransportType(it) }
        return c
    }

    @Test
    fun `networkKind maps transports`() {
        assertEquals("wifi", networkKind(caps(NetworkCapabilities.TRANSPORT_WIFI)))
        assertEquals("cellular", networkKind(caps(NetworkCapabilities.TRANSPORT_CELLULAR)))
        assertEquals("ethernet", networkKind(caps(NetworkCapabilities.TRANSPORT_ETHERNET)))
    }

    @Test
    fun `unknown transport omits net`() {
        assertNull(networkKind(caps(NetworkCapabilities.TRANSPORT_VPN)))
    }

    @Test
    fun `null capabilities mean no network`() {
        assertEquals("none", networkKind(null))
    }

    @Test
    fun `no active network reports none`() {
        shadowOf(cm).setActiveNetworkInfo(null)
        val probe = ConnectivityNetworkProbe(app)
        probe.start()
        assertEquals("none", probe.current())
        probe.stop()
    }

    @Test
    fun `start seeds the cache from the active network`() {
        val net = cm.activeNetwork!!
        shadowOf(cm).setNetworkCapabilities(net, caps(NetworkCapabilities.TRANSPORT_WIFI))
        val probe = ConnectivityNetworkProbe(app)
        probe.start()
        assertEquals("wifi", probe.current())
        probe.stop()
    }

    @Test
    fun `callback updates the cache`() {
        val probe = ConnectivityNetworkProbe(app)
        probe.start()
        val callback = shadowOf(cm).networkCallbacks.single()
        val net: Network = ShadowNetwork.newInstance(42)
        callback.onCapabilitiesChanged(net, caps(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertEquals("cellular", probe.current())
        callback.onLost(net)
        assertEquals("none", probe.current())
        probe.stop()
    }

    @Test
    fun `start twice registers one callback`() {
        val probe = ConnectivityNetworkProbe(app)
        probe.start()
        probe.start()
        assertEquals(1, shadowOf(cm).networkCallbacks.size)
        probe.stop()
    }

    @Test
    fun `stop unregisters the callback`() {
        val probe = ConnectivityNetworkProbe(app)
        probe.start()
        assertTrue(shadowOf(cm).networkCallbacks.isNotEmpty())
        probe.stop()
        assertTrue(shadowOf(cm).networkCallbacks.isEmpty())
        probe.stop() // idempotent
    }
}
