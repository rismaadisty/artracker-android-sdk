package id.art.tracker.perf

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import id.art.tracker.core.SdkLog

/** Type of the default network, cached so that reading it on the sampling thread never blocks. */
internal interface NetworkProbe {
    /** Registers for network changes; idempotent. */
    fun start()

    /** Unregisters; idempotent. */
    fun stop()

    /** `wifi`, `cellular`, `ethernet`, `none` (no active network), or null when the transport is unknown. */
    fun current(): String?
}

/** `null` capabilities mean there is no network; a transport this SDK does not name (VPN, Bluetooth, …) maps to null. */
internal fun networkKind(caps: NetworkCapabilities?): String? = when {
    caps == null -> "none"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
    else -> null
}

internal class ConnectivityNetworkProbe(context: Context) : NetworkProbe {
    private val app: Context = context.applicationContext ?: context

    @Volatile
    private var kind: String? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    private fun manager(): ConnectivityManager? =
        runCatching { app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }.getOrNull()

    @Synchronized
    override fun start() {
        if (callback != null) return
        val cm = manager() ?: return
        runCatching {
            kind = networkKind(cm.activeNetwork?.let(cm::getNetworkCapabilities))
        }.onFailure { SdkLog.w("reading the active network failed", it) }
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Before API 26 `onAvailable` is not always followed by `onCapabilitiesChanged`.
            override fun onAvailable(network: Network) {
                kind = runCatching { networkKind(cm.getNetworkCapabilities(network)) }.getOrNull()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                kind = networkKind(networkCapabilities)
            }

            override fun onLost(network: Network) {
                kind = "none"
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { callback = cb }
            .onFailure { SdkLog.w("registering the network callback failed", it) }
    }

    @Synchronized
    override fun stop() {
        val cb = callback ?: return
        callback = null
        val cm = manager() ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
            .onFailure { SdkLog.w("unregistering the network callback failed", it) }
    }

    override fun current(): String? = kind
}
