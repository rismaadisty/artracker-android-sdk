package id.art.tracker.replay.screenshot

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapPoolTest {
    @Test
    fun twoBuffersAreHandedOut_thenNone_untilOneComesBack() {
        val pool = BitmapPool()
        val a = pool.acquire(100, 200)
        val b = pool.acquire(100, 200)
        assertNotNull(a); assertNotNull(b)
        assertNotSame(a, b)
        assertNull(pool.acquire(100, 200))
        pool.release(a!!)
        assertNotNull(pool.acquire(100, 200))
    }

    @Test
    fun aReleasedBufferOfTheSameSizeIsReused_andComesBackBlack() {
        val pool = BitmapPool()
        val a = pool.acquire(50, 50)!!
        a.eraseColor(Color.RED)
        pool.release(a)
        val again = pool.acquire(50, 50)!!
        assertSame(a, again)
        assertEquals(Color.BLACK, again.getPixel(10, 10))
    }

    @Test
    fun aDifferentSize_getsAFreshBuffer() {
        val pool = BitmapPool()
        val a = pool.acquire(50, 50)!!
        pool.release(a)
        val b = pool.acquire(60, 70)!!
        assertEquals(60, b.width)
        assertEquals(70, b.height)
    }

    @Test
    fun absurdSizes_areRefused() {
        val pool = BitmapPool()
        assertNull(pool.acquire(0, 10))
        assertNull(pool.acquire(10, -1))
        assertNull(pool.acquire(100_000, 100_000))
    }
}
