package dev.dogsc.app.android

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.dogsc.app.ui.Loc
import kotlin.math.roundToInt

/**
 * Floating recording controls. FLAG_SECURE keeps this window out of the
 * MediaProjection capture, so the finished video never contains the controls.
 */
internal class RecordingOverlay(
    private val context: Context,
    private val coordinator: RecordingCoordinator,
    private val memoText: String = "",
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var timerView: TextView? = null
    private var pauseView: TextView? = null
    private var markerView: TextView? = null
    private var micView: TextView? = null
    private var cameraView: TextView? = null
    private var memoView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show() {
        if (!Settings.canDrawOverlays(context)) return
        if (root != null) return
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(235, 24, 25, 29))
            }
        }
        val dot = TextView(context).apply {
            text = "●"
            setTextColor(Color.rgb(255, 69, 58))
            textSize = 12f
        }
        timerView = TextView(context).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(6), 0, dp(10), 0)
        }
        pauseView = action(Loc.tShort("暂停")) {
            if (coordinator.isPaused()) {
                coordinator.resume()
            } else {
                coordinator.pause()
            }
        }
        markerView = action(Loc.tShort("标记")) { coordinator.addMarker() }
        micView = action(Loc.tShort("麦克风")) {
            val next = !coordinator.microphoneEnabled()
            // Route to the live coordinator: after an Activity recreation the manager instance
            // is fresh and holds no coordinator, so going through it silently did nothing.
            coordinator.setMicrophoneEnabled(next)
            AndroidRecorderManager.instance?.syncRecordingToggles()
        }
        cameraView = action(Loc.tShort("摄像头")) {
            val next = !coordinator.cameraEnabled()
            coordinator.setCameraEnabled(next)
            AndroidRecorderManager.instance?.syncRecordingToggles()
        }
        val memoButton = action(Loc.tShort("备忘")) { toggleMemo() }
        val stop = action(Loc.tShort("停止")) {
            // Go through the manager so isFinishing disables discard while encoders finalize.
            AndroidRecorderManager.instance?.stop() ?: coordinator.stop()
        }.apply {
            setTextColor(Color.rgb(255, 120, 112))
        }
        container.addView(dot)
        container.addView(timerView)
        container.addView(pauseView)
        container.addView(markerView)
        if (coordinator.microphoneAvailable()) container.addView(micView)
        if (coordinator.cameraAvailable()) container.addView(cameraView)
        if (memoText.isNotBlank()) container.addView(memoButton)
        container.addView(stop)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).roundToInt()
                    params.y = initialY + (event.rawY - touchY).roundToInt()
                    runCatching { windowManager.updateViewLayout(container, params) }
                    true
                }
                else -> false
            }
        }
        root = container
        layoutParams = params
        runCatching { windowManager.addView(container, params) }
        container.post { updateTime(coordinator.elapsedSecondsPublic()) }
    }

    fun hide() {
        hideMemo()
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        layoutParams = null
    }

    /** Floating notes window. FLAG_SECURE keeps it out of the MediaProjection capture. */
    private fun toggleMemo() {
        if (memoView != null) {
            hideMemo()
            return
        }
        if (memoText.isBlank() || !Settings.canDrawOverlays(context)) return
        val view = TextView(context).apply {
            text = memoText
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(220, 20, 21, 25))
            }
            maxWidth = dp(320)
            setLineSpacing(0f, 1.15f)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(140)
        }
        memoView = view
        runCatching { windowManager.addView(view, params) }
    }

    private fun hideMemo() {
        memoView?.let { runCatching { windowManager.removeView(it) } }
        memoView = null
    }

    fun setPaused(paused: Boolean) {
        pauseView?.text = Loc.tShort(if (paused) "继续" else "暂停")
    }

    fun updateTime(seconds: Double) {
        val total = seconds.coerceAtLeast(0.0).roundToInt()
        timerView?.text = "%02d:%02d".format(total / 60, total % 60)
    }

    fun flashMarker() {
        val view = markerView ?: return
        view.text = Loc.tShort("已标记")
        view.postDelayed({ view.text = Loc.tShort("标记") }, 900)
    }

    private fun action(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding(dp(8), dp(4), dp(8), dp(4))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
