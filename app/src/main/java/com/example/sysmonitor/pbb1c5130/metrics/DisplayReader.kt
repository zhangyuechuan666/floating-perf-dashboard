package com.example.sysmonitor.pbb1c5130.metrics

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display

data class DisplayStats(
    /** Refresh rate of the Android default display's current mode, in Hz. */
    val refreshHz: Float? = null,
    /** Vsync callbacks actually delivered to this app per second (measured). */
    val measuredFps: Float? = null,
    /** Refresh rates of the display's supported modes, highest first. */
    val supportedHz: List<Float> = emptyList(),
)

/**
 * Display refresh rate from the public DisplayManager API. On PICO headsets this is the Android
 * display the app runs on; the headset panel rate is set by the XR compositor and is not exposed
 * to Spatial SDK apps, so the two can differ.
 */
class DisplayReader(context: Context) {
    private val displayManager = context.applicationContext.getSystemService(DisplayManager::class.java)

    fun read(measuredFps: Float?): DisplayStats {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        return DisplayStats(
            refreshHz = display?.refreshRate?.takeIf { it > 0f },
            measuredFps = measuredFps,
            supportedHz = display?.supportedModes
                ?.map { it.refreshRate }
                ?.distinctBy { Math.round(it * 10) }
                ?.sortedDescending()
                .orEmpty(),
        )
    }
}

/**
 * Counts Choreographer vsync callbacks on the main thread to measure the frame rate actually
 * delivered to the app. [start] and [stop] must be called on the main thread; [fps] may be read
 * from any thread.
 */
class FrameRateMeter : Choreographer.FrameCallback {
    @Volatile
    var fps: Float? = null
        private set

    private var running = false
    private var frames = 0
    private var windowStartMs = 0L

    fun start() {
        if (running) return
        running = true
        frames = 0
        windowStartMs = SystemClock.elapsedRealtime()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        fps = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        frames++
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - windowStartMs
        if (elapsed >= 1000L) {
            fps = frames * 1000f / elapsed
            frames = 0
            windowStartMs = now
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
}
