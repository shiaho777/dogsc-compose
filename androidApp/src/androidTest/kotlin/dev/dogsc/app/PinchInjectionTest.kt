package dev.dogsc.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opens the first project, injects a real two-finger pinch into the timeline, and asserts that
 * the timeline region changed. This exercises the multi-touch path that `input` cannot drive.
 */
class PinchInjectionTest {
    @Test
    fun pinchTimeline() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val ui = instrumentation.uiAutomation
            val target = instrumentation.targetContext

            waitForText(ui, "Recent projects", 15_000)
            var card: AccessibilityNodeInfo? = null
            val cardDeadline = SystemClock.uptimeMillis() + 15_000
            while (card == null && SystemClock.uptimeMillis() < cardDeadline) {
                card = findFirstProject(ui.rootInActiveWindow)
                if (card == null) SystemClock.sleep(300)
            }
            checkNotNull(card) { "no project card" }
            tap(ui, 540f, 1_300f)

            waitForText(ui, "Timeline", 15_000)
            SystemClock.sleep(1_500)
            val before = ui.takeScreenshot()
            save(before, File(target.getExternalFilesDir(null), "pinch_before.png"))
            injectPinch(ui)
            SystemClock.sleep(1_500)
            val after = ui.takeScreenshot()
            save(after, File(target.getExternalFilesDir(null), "pinch_after.png"))

            val changed = timelineDiff(before, after)
            assertTrue("timeline did not react to the pinch ($changed pixels)", changed > 800)
        }
    }

    private fun tap(ui: android.app.UiAutomation, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f },
        )
        var event = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        ui.injectInputEvent(event, true)
        event.recycle()
        SystemClock.sleep(60)
        event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1, props,
            arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0f; size = 1f }),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        ui.injectInputEvent(event, true)
        event.recycle()
    }

    private fun injectPinch(ui: android.app.UiAutomation) {
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        fun coords(x1: Float, x2: Float, pressure: Float = 1f) = arrayOf(
            MotionEvent.PointerCoords().apply { x = x1; y = 1900f; this.pressure = pressure; size = 1f },
            MotionEvent.PointerCoords().apply { x = x2; y = 1900f; this.pressure = pressure; size = 1f },
        )
        fun send(action: Int, x1: Float, x2: Float) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 2, props, coords(x1, x2),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            ui.injectInputEvent(event, true)
            event.recycle()
        }
        val singleProps = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val singleCoords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 440f; y = 1900f; pressure = 1f; size = 1f },
        )
        var event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 1,
            singleProps, singleCoords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        ui.injectInputEvent(event, true)
        event.recycle()
        send(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            440f, 640f,
        )
        for (step in 1..12) {
            send(
                MotionEvent.ACTION_MOVE,
                440f + (140f - 440f) * step / 12f,
                640f + (940f - 640f) * step / 12f,
            )
            SystemClock.sleep(40)
        }
        send(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            140f, 940f,
        )
        event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1,
            singleProps,
            arrayOf(MotionEvent.PointerCoords().apply { x = 240f; y = 1900f; pressure = 0f; size = 1f }),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        ui.injectInputEvent(event, true)
        event.recycle()
    }

    private fun waitForText(ui: android.app.UiAutomation, text: String, timeoutMs: Long) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (containsText(ui.rootInActiveWindow, text)) return
            SystemClock.sleep(250)
        }
        error("timed out waiting for '$text'")
    }

    private fun containsText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.contains(text) == true) return true
        for (index in 0 until node.childCount) {
            if (containsText(node.getChild(index), text)) return true
        }
        return false
    }

    private fun findFirstProject(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val value = node.text?.toString().orEmpty()
        if (value.startsWith("录制") || value.startsWith("导入的视频")) return node
        for (index in 0 until node.childCount) {
            findFirstProject(node.getChild(index))?.let { return it }
        }
        return null
    }

    private fun timelineDiff(before: Bitmap, after: Bitmap): Int {
        val top = 1_780
        val bottom = minOf(2_250, before.height, after.height)
        var changed = 0
        for (y in top until bottom step 2) {
            for (x in 0 until minOf(before.width, after.width) step 2) {
                val a = before.getPixel(x, y)
                val b = after.getPixel(x, y)
                if (abs((a and 0xFF) - (b and 0xFF)) +
                    abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
                    abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) > 40
                ) {
                    changed++
                }
            }
        }
        return changed
    }

    private fun save(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
