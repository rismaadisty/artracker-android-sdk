package id.art.tracker.testutil

import id.art.tracker.perf.DeviceProbe
import id.art.tracker.perf.FrameSource
import id.art.tracker.perf.FrameWindow
import id.art.tracker.perf.NetworkProbe
import id.art.tracker.perf.ProcessReader

/** A `/proc/self/stat` line with the given CPU ticks and thread count (utime = field 14, num_threads = field 20). */
internal fun statLine(utime: Long, stime: Long = 0, threads: Int = 10, comm: String = "id.art.sample"): String {
    val fields = MutableList(50) { "0" }
    fields[0] = "S"
    fields[11] = utime.toString()
    fields[12] = stime.toString()
    fields[17] = threads.toString()
    return "1234 ($comm) " + fields.joinToString(" ")
}

internal class FakeProcessReader(var stat: String? = null, var statm: String? = null) : ProcessReader {
    override fun readStat(): String? = stat

    override fun readStatm(): String? = statm
}

internal class FakeFrameSource(var window: FrameWindow = FrameWindow(0, 0, 0, 60f)) : FrameSource {
    var started = 0
    var stopped = 0

    override fun start() {
        started++
    }

    override fun stop() {
        stopped++
    }

    override fun drain(): FrameWindow = window.also { window = FrameWindow(0, 0, 0, it.refreshHz) }
}

internal class FakeDeviceProbe(
    var ram: Int? = 4096,
    var cores: Int = 1,
    var battery: Double? = 0.5,
    var thermal: String? = "nominal",
    var clkTck: Long = 100,
    var pageSize: Long = 4096,
    var osVersion: String? = "14",
    var ramFree: Int? = 1500,
    var storageTotal: Int? = 128_000,
    var storageFree: Int? = 64_000,
) : DeviceProbe {
    override fun osVersion(): String? = osVersion

    override fun ramFreeMb(): Int? = ramFree

    override fun storageTotalMb(): Int? = storageTotal

    override fun storageFreeMb(): Int? = storageFree

    override fun ramMb(): Int? = ram

    override fun cores(): Int = cores

    override fun batteryFraction(): Double? = battery

    override fun thermal(): String? = thermal

    override fun clkTck(): Long = clkTck

    override fun pageSizeBytes(): Long = pageSize
}

internal class FakeNetworkProbe(var net: String? = "wifi") : NetworkProbe {
    var starts = 0
    var stops = 0

    override fun start() {
        starts++
    }

    override fun stop() {
        stops++
    }

    override fun current(): String? = net
}
