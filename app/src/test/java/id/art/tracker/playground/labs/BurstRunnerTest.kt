package id.art.tracker.playground.labs

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstRunnerTest {
    @Test
    fun emitsExactlyNValuesInOrder() = runBlocking {
        val seen = mutableListOf<Int>()
        BurstRunner { seen += it }.run(250) { }
        assertEquals((0 until 250).toList(), seen)
    }

    @Test
    fun progressIsMonotonicAndEndsAtN() = runBlocking {
        val progress = mutableListOf<Int>()
        BurstRunner { }.run(250) { progress += it }
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(250, progress.last())
    }

    @Test
    fun progressForAnExactMultipleEndsAtN() = runBlocking {
        val progress = mutableListOf<Int>()
        BurstRunner { }.run(200) { progress += it }
        assertEquals(200, progress.last())
    }

    @Test
    fun cancellingStopsWithinOneYieldWindow() = runBlocking {
        var emitted = 0
        lateinit var job: Job
        val runner = BurstRunner {
            emitted++
            if (emitted == 50) job.cancel()
        }
        job = launch(start = CoroutineStart.LAZY) { runner.run(10_000) { } }
        job.start()
        job.join()
        assertTrue("emitted=$emitted", emitted in 50..150)
    }

    @Test
    fun zeroCountEmitsNothing() = runBlocking {
        var emitted = 0
        BurstRunner { emitted++ }.run(0) { }
        assertEquals(0, emitted)
    }
}
