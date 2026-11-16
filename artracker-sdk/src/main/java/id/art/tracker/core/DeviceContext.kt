package id.art.tracker.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.util.Locale
import java.util.TimeZone

/**
 * Static device/app facts used for the `session_start` attributes and the envelope `context`.
 * Safe to call from any thread; no method throws (each probe falls back to a neutral value).
 */
internal class DeviceContext(
    context: Context,
    configuredAppVersion: String?,
    /** Reads the manifest build id; called at most once, off the main thread. */
    private val buildIdReader: (Context) -> String? = BuildId::read,
) {
    private val appContext: Context = context.applicationContext ?: context

    /** When the installed build was installed or last updated (`PackageInfo.lastUpdateTime`); 0 when unknown. */
    val lastUpdateTimeMs: Long by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { packageInfo().lastUpdateTime }.getOrDefault(0L)
    }

    /** The `id.art.tracker.BUILD_ID` of the manifest, or null (absent, blank, unreadable). Read once, never throws. */
    val buildId: String? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { buildIdReader(appContext) }.getOrNull()
    }

    private val packageVersionName: String? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { packageInfo()?.versionName }.getOrNull()
    }

    private val appBuild: Long by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            val info = packageInfo() ?: return@runCatching 0L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)
    }

    val appVersion: String =
        configuredAppVersion?.takeIf { it.isNotBlank() } ?: packageVersionName ?: "unknown"

    fun sessionStartAttributes(): Map<String, Any?> {
        val attrs = LinkedHashMap<String, Any?>(16)
        attrs["platform"] = "android"
        attrs["os_version"] = safe("unknown") { Build.VERSION.RELEASE ?: "unknown" }
        attrs["api_level"] = Build.VERSION.SDK_INT
        attrs["manufacturer"] = safe("unknown") { Build.MANUFACTURER ?: "unknown" }
        attrs["model"] = safe("unknown") { Build.MODEL ?: "unknown" }
        attrs["app_version"] = appVersion
        attrs["app_build"] = appBuild
        buildId?.let { attrs["build_id"] = it } // right after app_build; no key at all when there is none
        attrs["screen"] = screen()
        attrs["locale"] = safe("unknown") { Locale.getDefault().toLanguageTag() }
        attrs["tz"] = safe("unknown") { TimeZone.getDefault().id }
        attrs["network"] = network()
        attrs["is_emulator"] = isEmulator()
        attrs["sdk_version"] = SdkInfo.VERSION
        return attrs
    }

    fun envelopeContext(environment: String): Map<String, Any?> = linkedMapOf(
        "environment" to environment,
        "app_version" to appVersion,
        "sdk_platform" to "android",
    )

    private fun packageInfo() = appContext.packageManager.getPackageInfo(appContext.packageName, 0)

    private fun screen(): Map<String, Any?> {
        val m = runCatching { appContext.resources.displayMetrics }.getOrNull()
        return linkedMapOf(
            "w" to (m?.widthPixels ?: 0),
            "h" to (m?.heightPixels ?: 0),
            "density" to (m?.density?.toDouble() ?: 0.0),
        )
    }

    private fun network(): String = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching "unknown"
        val active = cm.activeNetwork ?: return@runCatching "none"
        val caps = cm.getNetworkCapabilities(active) ?: return@runCatching "unknown"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    }.getOrDefault("unknown")

    private fun isEmulator(): Boolean = runCatching {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val device = Build.DEVICE.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            manufacturer.contains("Genymotion") ||
            (brand.startsWith("generic") && device.startsWith("generic")) ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu")
    }.getOrDefault(false)

    private inline fun <T> safe(fallback: T, block: () -> T): T = runCatching(block).getOrDefault(fallback)
}
