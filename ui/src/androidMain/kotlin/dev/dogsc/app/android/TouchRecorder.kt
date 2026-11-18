package dev.dogsc.app.android

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Optional touch/click recorder shared by the accessibility service and the
 * recording coordinator. Only normalized positions and timestamps are stored.
 */
object TouchRecorder {
    private var writer: BufferedWriter? = null
    private var startedAtNanos: Long = 0
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400
    private var lastX = 0.5
    private var lastY = 0.5
    // Latest finger position from the raw motion stream; the click event itself
    // carries no coordinates, so the clicked view's center is only a fallback.
    private var lastMotionX: Double? = null
    private var lastMotionY: Double? = null
    private var lastMotionAtNanos: Long = 0
    // Disk writes happen on a dedicated thread: the accessibility callbacks run on
    // the main thread and a per-event flush at 60-120 Hz caused jank/ANR exposure.
    private val pendingLines = java.util.ArrayDeque<String>()
    private var ioThread: Thread? = null
    @Volatile private var ioRunning = false

    @Synchronized
    fun start(file: File, width: Int, height: Int) {
        stop()
        file.parentFile?.mkdirs()
        writer = BufferedWriter(FileWriter(file, false))
        startedAtNanos = SystemClock.elapsedRealtimeNanos()
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)
        lastX = 0.5
        lastY = 0.5
        lastMotionX = null
        lastMotionY = null
        ioRunning = true
        ioThread = Thread({ ioLoop() }, "DogSC-TouchIO").apply { start() }
    }

    @Synchronized
    fun stop() {
        ioRunning = false
        ioThread?.interrupt()
        runCatching { ioThread?.join(1500) }
        ioThread = null
        // Drain whatever is still queued so a normal stop loses no events.
        val target = writer
        if (target != null) {
            runCatching {
                while (true) {
                    val line = synchronized(pendingLines) { pendingLines.pollFirst() } ?: break
                    target.write(line)
                }
                target.flush()
            }
        }
        runCatching { writer?.close() }
        writer = null
        synchronized(pendingLines) { pendingLines.clear() }
    }

    val isActive: Boolean get() = writer != null

    @Synchronized
    fun recordTouch(x: Double, y: Double, kind: String) {
        val normalizedX = (x / screenWidth).coerceIn(0.0, 1.0)
        val normalizedY = (y / screenHeight).coerceIn(0.0, 1.0)
        if (kind == "move" && kotlin.math.abs(normalizedX - lastX) < 0.0005 &&
            kotlin.math.abs(normalizedY - lastY) < 0.0005
        ) {
            return
        }
        lastX = normalizedX
        lastY = normalizedY
        write(normalizedX, normalizedY, kind)
    }

    /** Raw finger position from the motion stream (Android 14+). */
    @Synchronized
    fun recordMotion(x: Double, y: Double) {
        lastMotionX = x
        lastMotionY = y
        lastMotionAtNanos = SystemClock.elapsedRealtimeNanos()
        recordTouch(x, y, "move")
    }

    @Synchronized
    fun recordClick(x: Double, y: Double, longClick: Boolean = false) {
        // Prefer the actual finger position when a recent motion sample exists;
        // the clicked view's center can be far from the tap on large views.
        val fresh = SystemClock.elapsedRealtimeNanos() - lastMotionAtNanos < 500_000_000L
        val clickX = if (fresh) lastMotionX ?: x else x
        val clickY = if (fresh) lastMotionY ?: y else y
        val normalizedX = (clickX / screenWidth).coerceIn(0.0, 1.0)
        val normalizedY = (clickY / screenHeight).coerceIn(0.0, 1.0)
        write(normalizedX, normalizedY, if (longClick) "rightClick" else "leftClick")
    }

    private fun write(x: Double, y: Double, kind: String) {
        if (writer == null) return
        val time = (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000_000.0
        // Locale.ROOT is mandatory: comma-decimal locales emitted "time":0,1234,
        // which is invalid JSON and silently discarded the whole pointer track.
        val line = "{\"time\":${"%.4f".format(java.util.Locale.ROOT, time)}," +
            "\"x\":${"%.5f".format(java.util.Locale.ROOT, x)}," +
            "\"y\":${"%.5f".format(java.util.Locale.ROOT, y)},\"kind\":\"$kind\"}\n"
        synchronized(pendingLines) {
            // Bound memory if the disk stalls; dropping the oldest move samples is
            // preferable to an unbounded queue.
            if (pendingLines.size > 8192) pendingLines.pollFirst()
            pendingLines.addLast(line)
        }
    }

    private fun ioLoop() {
        while (ioRunning) {
            val target = writer
            var wrote = false
            if (target != null) {
                runCatching {
                    while (true) {
                        val line = synchronized(pendingLines) { pendingLines.pollFirst() } ?: break
                        target.write(line)
                        wrote = true
                    }
                    if (wrote) target.flush()
                }
            }
            if (!wrote) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }
}

class DogScTouchAccessibilityService : android.accessibilityservice.AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        // Android 14+ can stream raw touch motion; older versions fall back to click/scroll anchors.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            runCatching {
                serviceInfo = serviceInfo.apply {
                    flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
                    setMotionEventSources(android.view.InputDevice.SOURCE_TOUCHSCREEN)
                }
            }
        }
    }

    override fun onMotionEvent(event: android.view.MotionEvent) {
        val motion = event
        if (!TouchRecorder.isActive) return
        when (motion.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE ->
                TouchRecorder.recordMotion(motion.rawX.toDouble(), motion.rawY.toDouble())
            else -> Unit
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val event = event ?: return
        if (!TouchRecorder.isActive) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val source = event.source ?: return
                val bounds = Rect()
                source.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    TouchRecorder.recordClick(
                        bounds.exactCenterX().toDouble(),
                        bounds.exactCenterY().toDouble(),
                        event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                    )
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val source = event.source ?: return
                val bounds = Rect()
                source.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    TouchRecorder.recordTouch(bounds.exactCenterX().toDouble(), bounds.exactCenterY().toDouble(), "move")
                }
            }
        }
    }

    override fun onInterrupt() = Unit
}
