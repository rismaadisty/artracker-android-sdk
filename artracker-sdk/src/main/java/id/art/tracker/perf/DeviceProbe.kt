package id.art.tracker.perf

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants

/** Platform facts for the profiler; a fake in tests. Every call is safe to make on the sampling thread. */
internal interface DeviceProbe {
    fun ramMb(): Int?

    fun cores(): Int

    fun batteryFraction(): Double?

    fun thermal(): String?

    fun clkTck(): Long

    fun pageSizeBytes(): Long
}

private const val DEFAULT_CLK_TCK = 100L
private const val DEFAULT_PAGE_SIZE = 4096L
private const val BYTES_PER_MB = 1_048_576L

/** Maps `PowerManager.THERMAL_STATUS_*` to the wire vocabulary; null for a value this SDK does not know. */
internal fun mapThermalStatus(status: Int): String? = when (status) {
    PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> "nominal"
    PowerManager.THERMAL_STATUS_MODERATE -> "fair"
    PowerManager.THERMAL_STATUS_SEVERE -> "serious"
    PowerManager.THERMAL_STATUS_CRITICAL,
    PowerManager.THERMAL_STATUS_EMERGENCY,
    PowerManager.THERMAL_STATUS_SHUTDOWN,
    -> "critical"
    else -> null
}

internal class AndroidDeviceProbe(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : DeviceProbe {
    private val app: Context = context.applicationContext ?: context

    private val ram: Int? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            (info.totalMem / BYTES_PER_MB).toInt().takeIf { it > 0 }
        }.getOrNull()
    }

    private val clkTck: Long by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(DEFAULT_CLK_TCK).takeIf { it > 0 } ?: DEFAULT_CLK_TCK
    }

    private val pageSize: Long by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(DEFAULT_PAGE_SIZE).takeIf { it > 0 } ?: DEFAULT_PAGE_SIZE
    }

    override fun ramMb(): Int? = ram

    override fun cores(): Int = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(1).coerceAtLeast(1)

    override fun batteryFraction(): Double? = runCatching {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (percent == Int.MIN_VALUE || percent <= 0) null else (percent / 100.0).coerceAtMost(1.0)
    }.getOrNull()

    override fun thermal(): String? {
        if (sdkInt < 29 || Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            mapThermalStatus(pm.currentThermalStatus)
        }.getOrNull()
    }

    override fun clkTck(): Long = clkTck

    override fun pageSizeBytes(): Long = pageSize
}
