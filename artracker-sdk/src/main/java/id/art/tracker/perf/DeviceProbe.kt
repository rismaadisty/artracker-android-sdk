package id.art.tracker.perf

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.system.Os
import android.system.OsConstants

/** One `ActivityManager.getMemoryInfo` reading: free RAM in MB and whether the system itself calls memory low. */
internal data class MemoryReading(val freeMb: Int, val low: Boolean)

/** Platform facts for the profiler; a fake in tests. Every call is safe to make on the sampling thread. */
internal interface DeviceProbe {
    fun ramMb(): Int?

    fun cores(): Int

    fun batteryFraction(): Double?

    fun thermal(): String?

    fun clkTck(): Long

    fun pageSizeBytes(): Long

    /** Android release, e.g. "14". */
    fun osVersion(): String?

    /** Free RAM (`MemoryInfo.availMem`) and `MemoryInfo.lowMemory` from one read, taken on every call. */
    fun memory(): MemoryReading?

    /** Size and free space of the data partition in MB. */
    fun storageTotalMb(): Int?

    fun storageFreeMb(): Int?
}

private const val DEFAULT_CLK_TCK = 100L
private const val DEFAULT_PAGE_SIZE = 4096L
private const val BYTES_PER_MB = 1_048_576L

/** Bytes to MB, rounded to the nearest integer. */
private fun bytesToMb(bytes: Long): Int = Math.round(bytes.toDouble() / BYTES_PER_MB).toInt()

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
    /** Test seam: builds the `StatFs` for a path; may throw (some devices reject the data directory). */
    private val statFs: (String) -> StatFs = { StatFs(it) },
) : DeviceProbe {
    private val app: Context = context.applicationContext ?: context

    private val ram: Int? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            bytesToMb(info.totalMem).takeIf { it > 0 }
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

    override fun osVersion(): String? = runCatching { Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } }.getOrNull()

    override fun memory(): MemoryReading? = runCatching {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        bytesToMb(info.availMem).takeIf { it >= 0 }?.let { MemoryReading(it, info.lowMemory) }
    }.getOrNull()

    /** The partition size does not change, so a successful read is kept; a failed one is retried on the next call. */
    @Volatile
    private var storageTotal: Int? = null

    override fun storageTotalMb(): Int? = storageTotal ?: runCatching {
        bytesToMb(statFs(Environment.getDataDirectory().path).totalBytes).takeIf { it > 0 }
    }.getOrNull().also { storageTotal = it }

    override fun storageFreeMb(): Int? =
        runCatching { bytesToMb(statFs(Environment.getDataDirectory().path).availableBytes).takeIf { it >= 0 } }.getOrNull()

    override fun clkTck(): Long = clkTck

    override fun pageSizeBytes(): Long = pageSize
}
