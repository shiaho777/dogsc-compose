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
    }

    @Synchronized
    fun stop() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
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

    @Synchronized
    fun recordClick(x: Double, y: Double, longClick: Boolean = false) {
        val normalizedX = (x / screenWidth).coerceIn(0.0, 1.0)
        val normalizedY = (y / screenHeight).coerceIn(0.0, 1.0)
        write(normalizedX, normalizedY, if (longClick) "rightClick" else "leftClick")
    }

    private fun write(x: Double, y: Double, kind: String) {
        val target = writer ?: return
        val time = (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000_000.0
        runCatching {
            target.write(
                "{\"time\":${"%.4f".format(time)},\"x\":${"%.5f".format(x)},\"y\":${"%.5f".format(y)},\"kind\":\"$kind\"}\n"
            )
            target.flush()
        }
    }
}

class DogScTouchAccessibilityService : android.accessibilityservice.AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
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
