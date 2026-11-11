package id.art.tracker.perf

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.VisibleForTesting
import id.art.tracker.core.SdkLog
import id.art.tracker.lifecycle.ActivityTracker
import java.lang.ref.WeakReference

/** Attaches the platform frame-metrics listener to a window; a recording fake in tests. */
internal interface FrameWindowAttacher {
    fun attach(window: Window, listener: Window.OnFrameMetricsAvailableListener, handler: Handler)

    fun detach(window: Window, listener: Window.OnFrameMetricsAvailableListener)
}

internal object PlatformFrameWindowAttacher : FrameWindowAttacher {
    override fun attach(window: Window, listener: Window.OnFrameMetricsAvailableListener, handler: Handler) {
        window.addOnFrameMetricsAvailableListener(listener, handler)
    }

    override fun detach(window: Window, listener: Window.OnFrameMetricsAvailableListener) {
        window.removeOnFrameMetricsAvailableListener(listener)
    }
}

private const val DEFAULT_REFRESH_HZ = 60f

/**
 * Counts the frames of the resumed Activity's window with `Window.OnFrameMetricsAvailableListener` (API 24+).
 * The callbacks arrive on a private `HandlerThread` that exists only between [start] and [stop].
 */
internal class FrameMetricsFrameSource(
    private val windows: WindowListenerRegistry,
    private val accumulator: FrameAccumulator = FrameAccumulator(),
    private val attacher: FrameWindowAttacher = PlatformFrameWindowAttacher,
) : FrameSource {
    private class Attached(val window: WeakReference<Window>, val listener: Window.OnFrameMetricsAvailableListener)

    private val lock = Any()
    private var started = false
    private var handler: Handler? = null
    private var attached: Attached? = null
    private var attachedActivity: WeakReference<Activity>? = null

    @VisibleForTesting
    @Volatile
    internal var thread: HandlerThread? = null
        private set

    private val windowListener = object : ActivityTracker.WindowListener {
        override fun onActivityResumed(a: Activity) = this@FrameMetricsFrameSource.onResumed(a)

        override fun onActivityPaused(a: Activity) = this@FrameMetricsFrameSource.onPaused(a)
    }

    override fun start() {
        synchronized(lock) {
            if (started) return
            val t = HandlerThread("artracker-perf-frames").also { it.start() }
            thread = t
            handler = Handler(t.looper)
            started = true
        }
        windows.add(windowListener)
    }

    override fun stop() {
        windows.remove(windowListener)
        synchronized(lock) {
            if (!started) return
            started = false
            detachLocked()
            thread?.quitSafely()
            handler = null
        }
    }

    override fun drain(): FrameWindow = accumulator.drain()

    /** One frame's `TOTAL_DURATION`; the platform callback funnels here. */
    @VisibleForTesting
    internal fun onFrameDuration(totalDurationNs: Long, refreshHz: Float) {
        accumulator.onFrame(totalDurationNs, refreshHz)
    }

    private fun onResumed(activity: Activity) {
        runCatching {
            synchronized(lock) {
                val h = handler ?: return
                detachLocked()
                val window = activity.window ?: return
                val hz = refreshRateOf(activity)
                val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    onFrameDuration(metrics.getMetric(FrameMetrics.TOTAL_DURATION), hz)
                }
                attacher.attach(window, listener, h)
                attached = Attached(WeakReference(window), listener)
                attachedActivity = WeakReference(activity)
            }
        }.onFailure { SdkLog.w("attaching the frame listener failed", it) }
    }

    private fun onPaused(activity: Activity) {
        synchronized(lock) {
            if (attachedActivity?.get() === activity) detachLocked()
        }
    }

    private fun detachLocked() {
        val current = attached ?: return
        attached = null
        attachedActivity = null
        val window = current.window.get() ?: return
        runCatching { attacher.detach(window, current.listener) }
            .onFailure { SdkLog.w("detaching the frame listener failed", it) }
    }

    private fun refreshRateOf(activity: Activity): Float = runCatching {
        val hz = if (Build.VERSION.SDK_INT >= 30) {
            activity.display?.refreshRate
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.refreshRate
        }
        if (hz != null && hz > 0f) hz else DEFAULT_REFRESH_HZ
    }.getOrDefault(DEFAULT_REFRESH_HZ)
}
