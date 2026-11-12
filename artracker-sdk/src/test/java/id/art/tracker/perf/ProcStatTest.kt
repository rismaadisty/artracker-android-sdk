package id.art.tracker.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcStatTest {
    // Fields after the closing paren, 1-based from the stat man page: state(3) ppid(4) ... utime(14) stime(15) ... num_threads(20).
    private fun line(comm: String, utime: Long = 250, stime: Long = 50, threads: Int = 34): String {
        val fields = MutableList(50) { "0" }
        fields[0] = "S" // field 3
        fields[1] = "1" // field 4
        fields[11] = utime.toString() // field 14
        fields[12] = stime.toString() // field 15
        fields[17] = threads.toString() // field 20
        return "1234 ($comm) " + fields.joinToString(" ")
    }

    @Test
    fun `parses stat`() {
        assertEquals(ProcStat(250, 50, 34), parseProcStat(line("id.art.sample")))
    }

    @Test
    fun `parses stat with spaces and parens in comm`() {
        assertEquals(ProcStat(250, 50, 34), parseProcStat(line("my app) (x")))
    }

    @Test
    fun `garbage line returns null`() {
        assertNull(parseProcStat("not a stat line"))
        assertNull(parseProcStat(""))
        assertNull(parseProcStat("1 (short) S 1 2"))
    }

    @Test
    fun `statm resident pages is the second field`() {
        assertEquals(6789L, parseStatmResidentPages("12345 6789 100 1 0 200 0"))
        assertNull(parseStatmResidentPages("12345"))
        assertNull(parseStatmResidentPages("a b c"))
    }

    @Test
    fun `cpu percent is core seconds over elapsed times cores`() {
        assertEquals(25.0, cpuPercent(0, 100, 100, 1000, 4)!!, 0.0001)
    }

    @Test
    fun `cpu percent without elapsed time or cores is null`() {
        assertNull(cpuPercent(0, 100, 100, 0, 4))
        assertNull(cpuPercent(0, 100, 100, 1000, 0))
    }

    @Test
    fun `cpu percent is clamped to 100`() {
        assertEquals(100.0, cpuPercent(0, 10_000, 100, 1000, 1)!!, 0.0001)
        assertEquals(0.0, cpuPercent(500, 100, 100, 1000, 1)!!, 0.0001)
    }
}
