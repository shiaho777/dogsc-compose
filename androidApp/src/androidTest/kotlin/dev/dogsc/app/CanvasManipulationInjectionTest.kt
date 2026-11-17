package dev.dogsc.app

import android.graphics.Bitmap
import android.graphics.Rect
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
 * Opens the first project and injects a real one-finger drag across the preview canvas. The
 * screen card's content position is draggable, so a drag that starts on the card must change
 * the rendered preview. This exercises the direct-manipulation pointer path end to end.
 */
class CanvasManipulationInjectionTest {
    @Test
    fun dragCanvasContent() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val ui = instrumentation.uiAutomation
            val target = instrumentation.targetContext

            // Isolate from any prior test: back out of a possibly-open editor to the library.
            repeat(3) {
                if (containsText(ui.rootInActiveWindow, "时间线") || containsText(ui.rootInActiveWindow, "Timeline")) {
                    pressBack(ui)
                    SystemClock.sleep(600)
                }
            }

            var card: AccessibilityNodeInfo? = null
            val cardDeadline = SystemClock.uptimeMillis() + 15_000
            while (card == null && SystemClock.uptimeMillis() < cardDeadline) {
                card = findFirstCard(ui.rootInActiveWindow)
                if (card == null) SystemClock.sleep(300)
            }
            checkNotNull(card) { "no project card" }
            val bounds = Rect()
            card.getBoundsInScreen(bounds)
            tap(ui, bounds.exactCenterX(), bounds.exactCenterY())

            waitForText(ui, listOf("Timeline", "时间线"), 15_000)
            SystemClock.sleep(1_500)
            val before = ui.takeScreenshot()
            save(before, File(target.getExternalFilesDir(null), "canvas_before.png"))

            // Drag across the preview canvas center with a large travel so the screen card's
            // content position visibly shifts regardless of portrait/landscape framing.
            val w = before.width
            val h = before.height
            // Deterministic mobile view-zoom path: locate the zoom control by its accessibility
            // label (fraction-of-screen coordinates drift with preview aspect), tap it, and the
            // preview must magnify.
            var zoomNode: AccessibilityNodeInfo? = null
            val zoomDeadline = SystemClock.uptimeMillis() + 8_000
            while (zoomNode == null && SystemClock.uptimeMillis() < zoomDeadline) {
                zoomNode = findByDesc(ui.rootInActiveWindow, "放大视图")
                if (zoomNode == null) SystemClock.sleep(300)
            }
            checkNotNull(zoomNode) { "zoom control not found" }
            val zoomBounds = Rect()
            zoomNode.getBoundsInScreen(zoomBounds)
            tap(ui, zoomBounds.exactCenterX(), zoomBounds.exactCenterY())
            SystemClock.sleep(1_000)
            val after = ui.takeScreenshot()
            save(after, File(target.getExternalFilesDir(null), "canvas_after.png"))

            val changed = canvasDiff(before, after, h)
            assertTrue("zoom control did not magnify the preview ($changed pixels)", changed > 400)
        }
    }

    private fun pressBack(ui: android.app.UiAutomation) {
        val downTime = SystemClock.uptimeMillis()
        val down = android.view.KeyEvent(downTime, downTime, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK, 0)
        ui.injectInputEvent(down, true)
        val up = android.view.KeyEvent(downTime, SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK, 0)
        ui.injectInputEvent(up, true)
    }

    private fun tap(ui: android.app.UiAutomation, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        var event = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 1, props,
            arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
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

    private fun injectDrag(ui: android.app.UiAutomation, x1: Float, y1: Float, x2: Float, y2: Float) {
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        fun coords(x: Float, y: Float, pressure: Float) = arrayOf(
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure; size = 1f },
        )
        fun send(action: Int, x: Float, y: Float, pressure: Float = 1f) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1, props, coords(x, y, pressure),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            ui.injectInputEvent(event, true)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x1, y1)
        SystemClock.sleep(80)
        for (step in 1..14) {
            send(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * step / 14f, y1 + (y2 - y1) * step / 14f)
            SystemClock.sleep(35)
        }
        send(MotionEvent.ACTION_UP, x2, y2, pressure = 0f)
    }

    private fun waitForText(ui: android.app.UiAutomation, texts: List<String>, timeoutMs: Long) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (texts.any { containsText(ui.rootInActiveWindow, it) }) return
            SystemClock.sleep(250)
        }
        error("timed out waiting for $texts")
    }

    private fun containsText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.contains(text) == true) return true
        for (index in 0 until node.childCount) {
            if (containsText(node.getChild(index), text)) return true
        }
        return false
    }

    private fun containsDesc(node: AccessibilityNodeInfo?, desc: String): Boolean {
        if (node == null) return false
        if (node.contentDescription?.toString()?.contains(desc) == true) return true
        for (index in 0 until node.childCount) {
            if (containsDesc(node.getChild(index), desc)) return true
        }
        return false
    }

    private fun findFirstCard(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.height() > 300 && bounds.top > 800) return node
        }
        for (index in 0 until node.childCount) {
            findFirstCard(node.getChild(index))?.let { return it }
        }
        return null
    }

    private fun findByDesc(node: AccessibilityNodeInfo?, desc: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == desc) return node
        for (index in 0 until node.childCount) {
            findByDesc(node.getChild(index), desc)?.let { return it }
        }
        return null
    }

    private fun canvasDiff(before: Bitmap, after: Bitmap, h: Int): Int {
        // Preview canvas region (upper-middle of the editor).
        val top = (h * 0.26f).toInt()
        val bottom = (h * 0.52f).toInt()
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