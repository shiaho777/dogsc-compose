package dev.dogsc.app.android.export

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import dev.dogsc.core.model.BackgroundPatternPreset
import dev.dogsc.core.model.BackgroundSource
import dev.dogsc.core.model.CursorAssetID
import dev.dogsc.core.model.DynamicBackgroundPreset
import dev.dogsc.core.model.HexColor
import dev.dogsc.core.model.MosaicEffectStyle
import dev.dogsc.core.scene.CursorRenderGeometry
import dev.dogsc.core.scene.FrameCursorScene
import dev.dogsc.core.scene.FrameScene
import dev.dogsc.core.scene.FrameScreenChromeKind
import dev.dogsc.core.scene.FrameScreenChromeScene
import dev.dogsc.core.scene.FrameScreenDecoration
import dev.dogsc.core.scene.FrameStickerScene
import dev.dogsc.core.scene.OverlayGeometry
import dev.dogsc.core.scene.RenderParams
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Software frame renderer shared by export and deterministic frame tests.
 * It consumes the same immutable FrameScene as the interactive preview.
 */
class CanvasFrameRenderer(
    private val width: Int,
    private val height: Int,
) {
    private val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val outputCanvas = Canvas(output)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val path = Path()
    private val matrix = Matrix()
    private var cardBitmap: Bitmap? = null
    private var cardCanvas: Canvas? = null
    private var blurBitmap: Bitmap? = null
    private var blurCanvas: Canvas? = null

    fun render(
        scene: FrameScene,
        screen: Bitmap?,
        camera: Bitmap?,
        background: Bitmap?,
        stickers: Map<String, Bitmap>,
    ): Bitmap {
        outputCanvas.drawColor(Color.BLACK)
        drawBackground(scene, background)
        drawScreen(scene, screen)
        if (scene.layerOrder.contains(dev.dogsc.core.scene.FrameLayerRole.CURSOR)) {
            scene.cursor?.let { drawCursor(it, scene) }
        }
        if (scene.layerOrder.contains(dev.dogsc.core.scene.FrameLayerRole.CAMERA)) {
            scene.camera?.let { drawCamera(it, camera) }
        }
        if (scene.layerOrder.contains(dev.dogsc.core.scene.FrameLayerRole.STICKERS)) {
            drawStickers(scene, scene.stickers, stickers)
        }
        return output
    }

    private fun drawBackground(scene: FrameScene, bitmap: Bitmap?) {
        // Earlier layers (shadows, borders) reuse this paint with a reduced alpha. A shader
        // inherits the paint alpha, so a stale shadow alpha would dim the whole background.
        fill.alpha = 255
        fill.color = Color.BLACK
        fill.shader = null
        fill.maskFilter = null
        when (val source = scene.background.source) {
            is BackgroundSource.Pattern -> drawPattern(source.preset, scene)
            is BackgroundSource.DynamicFlow -> drawDynamic(source.preset, scene)
            else -> {
                if (bitmap != null) {
                    drawCover(bitmap, 0f, 0f, width.toFloat(), height.toFloat())
                    if (scene.background.blurRadius > 0.1) {
                        val small = makeBlurred(bitmap, RenderParams.BackgroundBlurDivisor)
                        paint.alpha = min(255, (scene.background.blurRadius / 96 * 255).toInt()).coerceAtLeast(24)
                        drawCover(small, 0f, 0f, width.toFloat(), height.toFloat())
                        paint.alpha = 255
                        fill.color = Color.argb((scene.background.blurRadius / 96 * 90).toInt(), 0, 0, 0)
                        outputCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
                    }
                } else {
                    fill.shader = LinearGradient(
                        0f, 0f, width.toFloat(), height.toFloat(),
                        intArrayOf(Color.rgb(32, 38, 51), Color.rgb(12, 15, 22)),
                        null, Shader.TileMode.CLAMP,
                    )
                    outputCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
                    fill.shader = null
                }
            }
        }
    }

    private fun drawPattern(preset: BackgroundPatternPreset, scene: FrameScene) {
        val base = when (preset) {
            BackgroundPatternPreset.OBSIDIAN_GRID -> Color.rgb(13, 16, 23)
            BackgroundPatternPreset.ENGINEERING_WHITE_GRID -> Color.rgb(245, 244, 241)
            BackgroundPatternPreset.MIDNIGHT_DOTS -> Color.rgb(10, 12, 18)
            BackgroundPatternPreset.ARCHITECTURAL_DOTS -> Color.rgb(239, 237, 232)
            BackgroundPatternPreset.ISOMETRIC_MESH -> Color.rgb(16, 20, 28)
        }
        outputCanvas.drawColor(base)
        val light = preset == BackgroundPatternPreset.ENGINEERING_WHITE_GRID ||
            preset == BackgroundPatternPreset.ARCHITECTURAL_DOTS
        val ink = if (light) Color.argb(26, 26, 31, 46) else Color.argb(22, 255, 255, 255)
        stroke.color = ink
        stroke.strokeWidth = 1.2f
        val step = (36f * scene.background.patternScale).toFloat().coerceAtLeast(8f)
        when (preset) {
            BackgroundPatternPreset.OBSIDIAN_GRID, BackgroundPatternPreset.ENGINEERING_WHITE_GRID -> {
                var x = 0f
                while (x < width) {
                    outputCanvas.drawLine(x, 0f, x, height.toFloat(), stroke)
                    x += step
                }
                var y = 0f
                while (y < height) {
                    outputCanvas.drawLine(0f, y, width.toFloat(), y, stroke)
                    y += step
                }
            }
            BackgroundPatternPreset.MIDNIGHT_DOTS, BackgroundPatternPreset.ARCHITECTURAL_DOTS -> {
                fill.color = ink
                var y = step / 2
                while (y < height) {
                    var x = step / 2
                    while (x < width) {
                        outputCanvas.drawCircle(x, y, 1.3f * scene.background.patternScale.toFloat(), fill)
                        x += step
                    }
                    y += step
                }
            }
            BackgroundPatternPreset.ISOMETRIC_MESH -> {
                var x = -width.toFloat()
                while (x < width * 2) {
                    outputCanvas.drawLine(x, 0f, x + height * 0.58f, height.toFloat(), stroke)
                    x += step
                }
                var x2 = 0f
                while (x2 < width * 2) {
                    outputCanvas.drawLine(x2, 0f, x2 - height * 0.58f, height.toFloat(), stroke)
                    x2 += step
                }
                var y = 0f
                while (y < height) {
                    outputCanvas.drawLine(0f, y, width.toFloat(), y, stroke)
                    y += step * 0.86f
                }
            }
        }
        if (scene.background.patternOpacity < 1f) {
            fill.color = Color.argb(((1 - scene.background.patternOpacity) * 255).toInt(), Color.red(base), Color.green(base), Color.blue(base))
            outputCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
    }

    private fun drawDynamic(preset: DynamicBackgroundPreset, scene: FrameScene) {
        val geo = dev.dogsc.core.scene.DynamicBackgroundGeometry
        val colors = geo.gradientColors(preset).map { Color.rgb(Color.red(it), Color.green(it), Color.blue(it)) }.toIntArray()
        val (dx, dy) = geo.drift(scene.time)
        val driftX = (dx * width).toFloat()
        val driftY = (dy * height).toFloat()
        fill.shader = LinearGradient(driftX, driftY, width + driftX, height + driftY, colors, null, Shader.TileMode.CLAMP)
        outputCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null
        // Same pure geometry as the preview: identical starfield dots and drifting glow orbs.
        val radiusScale = min(width, height).toDouble()
        if (preset == DynamicBackgroundPreset.STARFIELD_DOTS) {
            for (star in geo.stars(scene.time, radiusScale)) {
                fill.color = Color.argb((star.alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)
                outputCanvas.drawCircle((star.x * width).toFloat(), (star.y * height).toFloat(), star.radius.toFloat(), fill)
            }
        } else {
            for (orb in geo.orbs(scene.time, radiusScale)) {
                fill.color = Color.argb((orb.alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)
                outputCanvas.drawCircle((orb.x * width).toFloat(), (orb.y * height).toFloat(), orb.radius.toFloat(), fill)
            }
        }
    }

    private fun drawScreen(scene: FrameScene, screen: Bitmap?) {
        val frame = scene.screen
        val outer = frame.decoration.projectionRect ?: frame.finalRect
        if (outer.width < 1 || outer.height < 1) return
        val cardWidth = max(2, ceil(outer.width).toInt().coerceAtMost(2400))
        val cardHeight = max(2, ceil(outer.height).toInt().coerceAtMost(2400))
        if (cardBitmap == null || cardBitmap!!.width != cardWidth || cardBitmap!!.height != cardHeight) {
            cardBitmap?.recycle()
            cardBitmap = Bitmap.createBitmap(cardWidth, cardHeight, Bitmap.Config.ARGB_8888)
            cardCanvas = Canvas(cardBitmap!!)
        }
        val canvas = cardCanvas!!
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.save()
        val scaleX = cardWidth / outer.width.toFloat()
        val scaleY = cardHeight / outer.height.toFloat()
        canvas.scale(scaleX, scaleY)
        canvas.translate(-outer.x.toFloat(), -outer.y.toFloat())
        val chrome = (frame.decoration as? FrameScreenDecoration.Chrome)?.scene
        if (chrome != null) {
            drawChrome(canvas, chrome, outer, frame.finalRect, frame.cornerRadius)
        } else {
            val contentPath = roundedPath(RectF(frame.finalRect.x.toFloat(), frame.finalRect.y.toFloat(), frame.finalRect.right.toFloat(), frame.finalRect.bottom.toFloat()), frame.cornerRadius.toFloat())
            fill.color = Color.rgb(17, 19, 24)
            canvas.drawPath(contentPath, fill)
        }
        drawScreenContent(canvas, screen, scene)
        canvas.restore()

        val card = cardBitmap ?: return
        val quad = frame.projectedQuad
        val shadow = frame.shadow
        if (shadow != null) {
            val shadowPath = Path().apply {
                moveTo(quad.topLeft.x.toFloat(), (quad.topLeft.y + shadow.offset.y).toFloat())
                lineTo(quad.topRight.x.toFloat(), (quad.topRight.y + shadow.offset.y).toFloat())
                lineTo(quad.bottomRight.x.toFloat(), (quad.bottomRight.y + shadow.offset.y).toFloat())
                lineTo(quad.bottomLeft.x.toFloat(), (quad.bottomLeft.y + shadow.offset.y).toFloat())
                close()
            }
            fill.color = Color.argb((shadow.opacity * RenderParams.ScreenShadowAlpha).toInt(), 0, 0, 0)
            fill.maskFilter = android.graphics.BlurMaskFilter(max(shadow.radius.toFloat(), RenderParams.MinShadowRadius.toFloat()), android.graphics.BlurMaskFilter.Blur.NORMAL)
            outputCanvas.drawPath(shadowPath, fill)
            fill.maskFilter = null
        }
        matrix.reset()
        val src = floatArrayOf(0f, 0f, card.width.toFloat(), 0f, card.width.toFloat(), card.height.toFloat(), 0f, card.height.toFloat())
        val dst = floatArrayOf(
            quad.topLeft.x.toFloat(), quad.topLeft.y.toFloat(),
            quad.topRight.x.toFloat(), quad.topRight.y.toFloat(),
            quad.bottomRight.x.toFloat(), quad.bottomRight.y.toFloat(),
            quad.bottomLeft.x.toFloat(), quad.bottomLeft.y.toFloat(),
        )
        if (matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            // Sticker "仅背景" suppresses the screen card; fold it into the card alpha.
            paint.alpha = (frame.opacity * (1.0 - scene.stickerBackdrop.screenSuppression) * 255).toInt().coerceIn(0, 255)
            outputCanvas.drawBitmap(card, matrix, paint)
            paint.alpha = 255
        } else {
            paint.alpha = (frame.opacity * 255).toInt().coerceIn(0, 255)
            outputCanvas.drawBitmap(card, null, RectF(frame.finalRect.x.toFloat(), frame.finalRect.y.toFloat(), frame.finalRect.right.toFloat(), frame.finalRect.bottom.toFloat()), paint)
            paint.alpha = 255
        }
    }

    private fun drawChrome(canvas: Canvas, chrome: FrameScreenChromeScene, outer: dev.dogsc.core.model.CompositionRect, content: dev.dogsc.core.model.CompositionRect, cornerRadius: Double) {
        val outerRect = RectF(outer.x.toFloat(), outer.y.toFloat(), outer.right.toFloat(), outer.bottom.toFloat())
        fill.color = chrome.surfaceColor.toArgb()
        canvas.drawPath(roundedPath(outerRect, chrome.outerCornerRadius.toFloat()), fill)
        val toolbarRect = RectF(outerRect.left, outerRect.top, outerRect.right, content.y.toFloat() + (content.y - outer.y).toFloat() * 0.08f)
        fill.color = chrome.toolbarColor.toArgb()
        canvas.drawRect(toolbarRect, fill)
        when (chrome.kind) {
            FrameScreenChromeKind.WINDOW -> {
                val radius = max(outerRect.width() * 0.006f, 2f)
                val cy = toolbarRect.centerY()
                val start = outerRect.left + outerRect.width() * 0.018f
                val gap = radius * 2.8f
                val colors = intArrayOf(Color.rgb(255, 95, 87), Color.rgb(254, 188, 46), Color.rgb(40, 200, 64))
                colors.forEachIndexed { index, color ->
                    fill.color = color
                    canvas.drawCircle(start + gap * index, cy, radius, fill)
                }
                if (chrome.title.isNotBlank()) {
                    textPaint.color = chrome.glyphColor.toArgb()
                    textPaint.textSize = max(outerRect.width() * 0.012f, 8f)
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(chrome.title, outerRect.centerX(), cy + textPaint.textSize * 0.34f, textPaint)
                }
            }
            FrameScreenChromeKind.BROWSER -> {
                val radius = max(outerRect.width() * 0.0055f, 2f)
                val cy = toolbarRect.centerY()
                val start = outerRect.left + outerRect.width() * 0.016f
                val gap = radius * 2.8f
                intArrayOf(Color.rgb(255, 95, 87), Color.rgb(254, 188, 46), Color.rgb(40, 200, 64)).forEachIndexed { index, color ->
                    fill.color = color
                    canvas.drawCircle(start + gap * index, cy, radius, fill)
                }
                val field = RectF(
                    outerRect.left + outerRect.width() * 0.09f,
                    toolbarRect.top + toolbarRect.height() * 0.24f,
                    outerRect.right - outerRect.width() * 0.03f,
                    toolbarRect.bottom - toolbarRect.height() * 0.22f,
                )
                fill.color = chrome.fieldColor.toArgb()
                canvas.drawPath(roundedPath(field, field.height() / 2), fill)
                if (chrome.browserAddress.isNotBlank()) {
                    textPaint.color = chrome.glyphColor.toArgb()
                    textPaint.textSize = max(outerRect.width() * 0.011f, 7f)
                    textPaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(chrome.browserAddress, field.left + field.width() * 0.03f, field.centerY() + textPaint.textSize * 0.34f, textPaint)
                }
            }
            else -> {
                val cy = (outerRect.top + content.y.toFloat()) / 2
                fill.color = Color.rgb(14, 15, 18)
                if (chrome.kind.isPhone) {
                    val slot = RectF(outerRect.centerX() - outerRect.width() * 0.055f, cy - outerRect.height() * 0.006f, outerRect.centerX() + outerRect.width() * 0.055f, cy + outerRect.height() * 0.006f)
                    canvas.drawPath(roundedPath(slot, slot.height() / 2), fill)
                } else {
                    canvas.drawCircle(outerRect.centerX(), cy, max(outerRect.width() * 0.0035f, 1.5f), fill)
                }
            }
        }
        val contentRect = RectF(content.x.toFloat(), content.y.toFloat(), content.right.toFloat(), content.bottom.toFloat())
        fill.color = Color.rgb(11, 12, 15)
        canvas.drawPath(roundedPath(contentRect, chrome.contentCornerRadius.toFloat()), fill)
    }

    private fun drawScreenContent(canvas: Canvas, screen: Bitmap?, scene: FrameScene) {
        val frame = scene.screen
        val content = RectF(frame.finalRect.x.toFloat(), frame.finalRect.y.toFloat(), frame.finalRect.right.toFloat(), frame.finalRect.bottom.toFloat())
        val radius = frame.cornerRadius.toFloat()
        val clip = roundedPath(content, radius)
        canvas.save()
        canvas.clipPath(clip)
        if (screen != null) {
            val src = screenSourceRect(screen, frame)
            paint.alpha = 255
            canvas.drawBitmap(screen, src, content, paint)
            // Sticker backdrop blur: paste a downscaled-blur copy over the whole screen card.
            val backdropBlur = scene.stickerBackdrop.screenBlur
            if (backdropBlur > 0.1) {
                val blurred = makeBlurred(screen, RenderParams.MosaicBlurDivisor)
                paint.alpha = (backdropBlur / 80.0 * 235).toInt().coerceIn(0, 255)
                canvas.drawBitmap(blurred, Rect(0, 0, blurred.width, blurred.height), content, paint)
                paint.alpha = 255
            }
        } else {
            fill.color = Color.rgb(35, 37, 43)
            canvas.drawRect(content, fill)
        }
        // Mosaics and focus effects.
        for (mosaic in frame.mosaics) {
            val mapped = OverlayGeometry.overlayRect(frame.finalRect, mosaic.sourceRect)
            val rect = RectF(mapped.x.toFloat(), mapped.y.toFloat(), mapped.right.toFloat(), mapped.bottom.toFloat())
            val r = (mosaic.cornerRadius * min(rect.width(), rect.height())).toFloat()
            if (mosaic.style == MosaicEffectStyle.BLUR) {
                if (screen != null) {
                    val blurred = makeBlurred(screen, RenderParams.MosaicBlurDivisor)
                    canvas.save()
                    canvas.clipPath(roundedPath(rect, r))
                    paint.alpha = (mosaic.intensity * mosaic.transitionProgress * 235).toInt().coerceIn(0, 255)
                    val src = screenSourceRect(screen, frame)
                    canvas.drawBitmap(blurred, Rect(0, 0, blurred.width, blurred.height), content, paint)
                    paint.alpha = 255
                    canvas.restore()
                }
            } else {
                val dim = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    addRect(content, Path.Direction.CW)
                    addPath(roundedPath(rect, r))
                }
                fill.color = Color.argb((mosaic.spotlightDimming * mosaic.transitionProgress * 255).toInt().coerceIn(0, 255), 0, 0, 0)
                canvas.drawPath(dim, fill)
            }
        }
        frame.focusEffect?.let { focus ->
            val region = OverlayGeometry.focusClearRegion(frame.finalRect, focus.effect, focus.center.x, focus.center.y)
            val cx = region.center.x.toFloat()
            val cy = region.center.y.toFloat()
            val half = region.halfWidth.toFloat()
            // Focus blur: paste a downscaled-blur copy of the screen outside the clear region.
            val blurStrength = focus.effect.blur * focus.progress
            if (screen != null && blurStrength > 0.01) {
                val blurred = makeBlurred(screen, RenderParams.MosaicBlurDivisor)
                val outside = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    addRect(content, Path.Direction.CW)
                    if (region.isLinear) {
                        addRect(content.left, cy - half, content.right, cy + half, Path.Direction.CW)
                    } else {
                        addCircle(cx, cy, half, Path.Direction.CW)
                    }
                }
                canvas.save()
                canvas.clipPath(outside)
                paint.alpha = (blurStrength * 235).toInt().coerceIn(0, 255)
                canvas.drawBitmap(blurred, Rect(0, 0, blurred.width, blurred.height), content, paint)
                paint.alpha = 255
                canvas.restore()
            }
            val alpha = (focus.effect.dimming * focus.progress * 255).toInt().coerceIn(0, 255)
            if (alpha > 2) {
                if (focus.effect.shape == dev.dogsc.core.model.FocusEffectShape.LINEAR) {
                    val angle = ((focus.effect.angleDegrees ?: 0.0) * PI / 180).toFloat()
                    canvas.save()
                    canvas.rotate((angle * 180 / PI).toFloat(), cx, cy)
                    fill.color = Color.argb(alpha, 0, 0, 0)
                    canvas.drawRect(content.left, content.top, content.right, cy - half, fill)
                    canvas.drawRect(content.left, cy + half, content.right, content.bottom, fill)
                    canvas.restore()
                } else {
                    val dim = Path().apply {
                        fillType = Path.FillType.EVEN_ODD
                        addRect(content, Path.Direction.CW)
                        addCircle(cx, cy, half, Path.Direction.CW)
                    }
                    fill.color = Color.argb(alpha, 0, 0, 0)
                    canvas.drawPath(dim, fill)
                }
            }
        }
        canvas.restore()
        if (frame.borderWidth > 0) {
            stroke.color = Color.argb((frame.borderOpacity * 255).toInt(), Color.red(frame.borderColor.toArgb()), Color.green(frame.borderColor.toArgb()), Color.blue(frame.borderColor.toArgb()))
            stroke.strokeWidth = max(frame.borderWidth.toFloat(), 1f)
            canvas.drawPath(clip, stroke)
        }
    }

    private fun screenSourceRect(bitmap: Bitmap, frame: dev.dogsc.core.scene.FrameScreenScene): Rect {
        val crop = frame.sourceCrop.clamped()
        val z = frame.viewport.scale.coerceAtLeast(1.0)
        val tx = frame.viewport.translation.x
        val ty = frame.viewport.translation.y
        val x0 = ((-0.5 - tx) / z + 0.5).coerceIn(0.0, 1.0)
        val x1 = ((0.5 - tx) / z + 0.5).coerceIn(0.0, 1.0)
        val y0 = ((-0.5 - ty) / z + 0.5).coerceIn(0.0, 1.0)
        val y1 = ((0.5 - ty) / z + 0.5).coerceIn(0.0, 1.0)
        val sx = ((crop.x + x0 * crop.width) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val sy = ((crop.y + y0 * crop.height) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val sw = (((x1 - x0) * crop.width) * bitmap.width).toInt().coerceAtLeast(1)
        val sh = (((y1 - y0) * crop.height) * bitmap.height).toInt().coerceAtLeast(1)
        return Rect(sx, sy, min(sx + sw, bitmap.width), min(sy + sh, bitmap.height))
    }

    private fun drawCamera(scene: dev.dogsc.core.scene.FrameCameraScene, bitmap: Bitmap?) {
        if (scene.opacity <= 0.001) return
        val rect = RectF(scene.rect.x.toFloat(), scene.rect.y.toFloat(), scene.rect.right.toFloat(), scene.rect.bottom.toFloat())
        if (rect.width() < 1 || rect.height() < 1) return
        val radius = scene.cornerRadius.toFloat()
        val clip = roundedPath(rect, radius)
        scene.shadow?.let { shadow ->
            fill.color = Color.argb((shadow.opacity * RenderParams.CameraShadowAlpha).toInt(), 0, 0, 0)
            fill.maskFilter = android.graphics.BlurMaskFilter(max(shadow.radius.toFloat(), RenderParams.MinShadowRadius.toFloat()), android.graphics.BlurMaskFilter.Blur.NORMAL)
            outputCanvas.drawPath(clip, fill)
            fill.maskFilter = null
        }
        outputCanvas.save()
        outputCanvas.clipPath(clip)
        paint.alpha = (scene.opacity * 255).toInt().coerceIn(0, 255)
        if (bitmap != null) {
            val fillLayout = scene.contentFill
            if (fillLayout != null) {
                // CameraContentFill works in source-size units (the exporter passes the aspect
                // ratio, not pixels). Scale the visible crop into the decoded bitmap.
                val sourceScaleX = bitmap.width / fillLayout.source.width.coerceAtLeast(0.0001)
                val sourceScaleY = bitmap.height / fillLayout.source.height.coerceAtLeast(0.0001)
                val src = Rect(
                    (fillLayout.visibleSource.x * sourceScaleX).toInt(),
                    (fillLayout.visibleSource.y * sourceScaleY).toInt(),
                    (fillLayout.visibleSource.right * sourceScaleX).toInt(),
                    (fillLayout.visibleSource.bottom * sourceScaleY).toInt(),
                )
                val dst = RectF(
                    fillLayout.destination.x.toFloat(),
                    fillLayout.destination.y.toFloat(),
                    fillLayout.destination.right.toFloat(),
                    fillLayout.destination.bottom.toFloat(),
                )
                if (scene.isMirrored) {
                    outputCanvas.save()
                    outputCanvas.scale(-1f, 1f, rect.centerX(), rect.centerY())
                }
                outputCanvas.drawBitmap(bitmap, src, dst, paint)
                if (scene.isMirrored) outputCanvas.restore()
            } else {
                drawCenterCrop(bitmap, rect, scene.isMirrored)
            }
        } else {
            fill.color = Color.rgb(49, 53, 61)
            outputCanvas.drawRect(rect, fill)
        }
        paint.alpha = 255
        outputCanvas.restore()
        if (scene.borderWidth > 0) {
            stroke.color = scene.borderColor.toArgb()
            stroke.strokeWidth = max(scene.borderWidth.toFloat(), 1f)
            outputCanvas.drawPath(clip, stroke)
        }
    }

    private fun drawStickers(scene: FrameScene, stickers: List<FrameStickerScene>, bitmaps: Map<String, Bitmap>) {
        for (sticker in stickers.sortedBy { it.layerIndex }) {
            val bitmap = bitmaps[sticker.relativePath] ?: continue
            if (sticker.opacity <= 0.001) continue
            val targetWidth = (sticker.width * width * sticker.scale).toFloat()
            val targetHeight = targetWidth * bitmap.height / bitmap.width
            val cx = ((sticker.position.x + sticker.offset.x) * width).toFloat()
            val cy = ((sticker.position.y + sticker.offset.y) * height).toFloat()
            outputCanvas.save()
            outputCanvas.rotate((sticker.rotationRadians * 180 / PI).toFloat(), cx, cy)
            val rect = RectF(cx - targetWidth / 2, cy - targetHeight / 2, cx + targetWidth / 2, cy + targetHeight / 2)
            // cornerRadius is authored in canvas pixels; scale into output pixels (same unit
            // contract as the preview, which normalizes by canvas width then multiplies by w).
            val stickerRadius = (sticker.cornerRadius / max(scene.canvasSize.width, 1.0) * width).toFloat()
            val clip = roundedPath(rect, stickerRadius)
            if (sticker.shadowOpacity > 0) {
                fill.color = Color.argb((sticker.shadowOpacity * RenderParams.StickerShadowAlpha).toInt(), 0, 0, 0)
                fill.maskFilter = android.graphics.BlurMaskFilter(max(sticker.shadowRadius.toFloat(), 1f), android.graphics.BlurMaskFilter.Blur.NORMAL)
                outputCanvas.drawPath(clip, fill)
                fill.maskFilter = null
            }
            outputCanvas.save()
            outputCanvas.clipPath(clip)
            paint.alpha = (sticker.opacity * 255).toInt().coerceIn(0, 255)
            outputCanvas.drawBitmap(bitmap, null, rect, paint)
            paint.alpha = 255
            outputCanvas.restore()
            if (sticker.borderWidth > 0) {
                stroke.color = sticker.borderColor.toArgb()
                stroke.strokeWidth = sticker.borderWidth.toFloat()
                outputCanvas.drawPath(clip, stroke)
            }
            outputCanvas.restore()
        }
    }

    private fun drawCursor(cursor: FrameCursorScene, scene: FrameScene) {
        val scale = width / scene.canvasSize.width.toFloat()
        val projected = scene.screen.projectedQuad.project(
            dev.dogsc.core.model.CompositionPoint(cursor.layout.pointer.x, cursor.layout.pointer.y),
            scene.screen.finalRect,
        )
        val pointerX = (projected?.x ?: cursor.layout.pointer.x).toFloat() * scale
        val pointerY = (projected?.y ?: cursor.layout.pointer.y).toFloat() * scale
        val originX = pointerX - cursor.layout.width.toFloat() * scale * cursor.metrics.hotspot.x.toFloat()
        val originY = pointerY - cursor.layout.height.toFloat() * scale * cursor.metrics.hotspot.y.toFloat()
        val w = cursor.layout.width.toFloat() * scale
        val h = cursor.layout.height.toFloat() * scale
        cursor.clickPhase?.let { phase ->
            val click = CursorRenderGeometry.clickGeometry(
                cursor.clickStyle, phase.progress, cursor.layout.clickEffectHeight,
                cursor.clickOpacity, cursor.clickScale,
            )
            if (click != null) {
                val color = cursor.effectiveClickColor.toArgb()
                fun ring(diameter: Double, lineWidth: Double, opacity: Double, filled: Boolean) {
                    if (diameter <= 0 || opacity <= 0) return
                    val r = (diameter / 2 * scale).toFloat()
                    if (filled) {
                        fill.color = Color.argb((opacity * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
                        outputCanvas.drawCircle(pointerX, pointerY, r, fill)
                    } else {
                        stroke.color = Color.argb((opacity * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
                        stroke.strokeWidth = max(1f, (lineWidth * scale).toFloat())
                        outputCanvas.drawCircle(pointerX, pointerY, r, stroke)
                    }
                }
                ring(click.primaryDiameter, click.primaryLineWidth, click.primaryOpacity, click.primaryIsFilled)
                ring(click.secondaryDiameter, click.secondaryLineWidth, click.secondaryOpacity, click.secondaryIsFilled)
            }
        }
        outputCanvas.save()
        outputCanvas.rotate((cursor.rotationRadians * 180 / PI).toFloat(), pointerX, pointerY)
        when (cursor.assetID) {
            CursorAssetID.TouchDot -> {
                fill.color = Color.argb(80, 255, 255, 255)
                outputCanvas.drawCircle(originX + w / 2, originY + h / 2, w * 0.55f, fill)
                fill.color = Color.argb(235, 255, 255, 255)
                outputCanvas.drawCircle(originX + w / 2, originY + h / 2, w * 0.36f, fill)
                fill.color = Color.argb(220, 27, 28, 32)
                outputCanvas.drawCircle(originX + w / 2, originY + h / 2, w * 0.16f, fill)
            }
            CursorAssetID.SystemCrosshair -> {
                stroke.color = Color.WHITE
                stroke.strokeWidth = max(1f, w * 0.09f)
                outputCanvas.drawLine(originX + w / 2, originY, originX + w / 2, originY + h, stroke)
                outputCanvas.drawLine(originX, originY + h / 2, originX + w, originY + h / 2, stroke)
                fill.color = Color.rgb(27, 28, 32)
                outputCanvas.drawCircle(originX + w / 2, originY + h / 2, w * 0.08f, fill)
            }
            CursorAssetID.SystemPointingHand, CursorAssetID.SystemOpenHand, CursorAssetID.SystemClosedHand -> {
                val p = Path().apply {
                    addRoundRect(RectF(originX + w * 0.22f, originY + h * 0.28f, originX + w * 0.78f, originY + h, ), w * 0.2f, w * 0.2f, Path.Direction.CW)
                    addRoundRect(RectF(originX + w * 0.34f, originY, originX + w * 0.50f, originY + h * 0.55f), w * 0.08f, w * 0.08f, Path.Direction.CW)
                }
                fill.color = Color.rgb(27, 28, 32)
                outputCanvas.drawPath(p, fill)
                stroke.color = Color.WHITE
                stroke.strokeWidth = max(1f, w * 0.06f)
                outputCanvas.drawPath(p, stroke)
            }
            CursorAssetID.SystemIBeam -> {
                fill.color = Color.rgb(27, 28, 32)
                outputCanvas.drawRect(originX + w * 0.44f, originY, originX + w * 0.56f, originY + h, fill)
                outputCanvas.drawRect(originX + w * 0.25f, originY, originX + w * 0.75f, originY + h * 0.10f, fill)
                outputCanvas.drawRect(originX + w * 0.25f, originY + h * 0.90f, originX + w * 0.75f, originY + h, fill)
            }
            CursorAssetID.SystemNotAllowed -> {
                fill.color = Color.rgb(217, 74, 68)
                outputCanvas.drawCircle(originX + w / 2, originY + h / 2, w * 0.44f, fill)
                stroke.color = Color.WHITE
                stroke.strokeWidth = max(1f, w * 0.09f)
                outputCanvas.drawCircle(originX + w / 2, originY + h / 2, w * 0.44f, stroke)
                outputCanvas.drawLine(originX + w * 0.22f, originY + h * 0.22f, originX + w * 0.78f, originY + h * 0.78f, stroke)
            }
            else -> {
                val p = Path().apply {
                    moveTo(originX, originY)
                    lineTo(originX, originY + h * 0.78f)
                    lineTo(originX + w * 0.26f, originY + h * 0.60f)
                    lineTo(originX + w * 0.44f, originY + h)
                    lineTo(originX + w * 0.62f, originY + h * 0.92f)
                    lineTo(originX + w * 0.44f, originY + h * 0.56f)
                    lineTo(originX + w * 0.78f, originY + h * 0.54f)
                    close()
                }
                fill.color = Color.rgb(27, 28, 32)
                outputCanvas.drawPath(p, fill)
                stroke.color = Color.WHITE
                stroke.strokeWidth = max(1f, w * 0.055f)
                outputCanvas.drawPath(p, stroke)
            }
        }
        outputCanvas.restore()
    }

    private fun drawCover(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float) {
        val dst = RectF(left, top, right, bottom)
        val scale = max(dst.width() / bitmap.width, dst.height() / bitmap.height)
        val visibleW = dst.width() / scale
        val visibleH = dst.height() / scale
        val src = Rect(
            ((bitmap.width - visibleW) / 2).toInt(),
            ((bitmap.height - visibleH) / 2).toInt(),
            ((bitmap.width + visibleW) / 2).toInt(),
            ((bitmap.height + visibleH) / 2).toInt(),
        )
        outputCanvas.drawBitmap(bitmap, src, dst, paint)
    }

    private fun drawCenterCrop(bitmap: Bitmap, dst: RectF, mirrored: Boolean) {
        val scale = max(dst.width() / bitmap.width, dst.height() / bitmap.height)
        val visibleW = dst.width() / scale
        val visibleH = dst.height() / scale
        val src = Rect(
            ((bitmap.width - visibleW) / 2).toInt(),
            ((bitmap.height - visibleH) / 2).toInt(),
            ((bitmap.width + visibleW) / 2).toInt(),
            ((bitmap.height + visibleH) / 2).toInt(),
        )
        if (mirrored) {
            outputCanvas.save()
            outputCanvas.scale(-1f, 1f, dst.centerX(), dst.centerY())
        }
        outputCanvas.drawBitmap(bitmap, src, dst, paint)
        if (mirrored) outputCanvas.restore()
    }

    private fun makeBlurred(bitmap: Bitmap, divisor: Int): Bitmap {
        val targetW = max(bitmap.width / divisor, 1)
        val targetH = max(bitmap.height / divisor, 1)
        val current = blurBitmap
        val small = if (current == null || current.width != targetW || current.height != targetH) {
            val created = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            blurBitmap = created
            blurCanvas = Canvas(created)
            created
        } else {
            current
        }
        val canvas = blurCanvas ?: Canvas(small)
        canvas.drawBitmap(bitmap, null, Rect(0, 0, targetW, targetH), paint)
        return small
    }

    private fun roundedPath(rect: RectF, radius: Float): Path = Path().apply {
        addRoundRect(rect, max(radius, 0f), max(radius, 0f), Path.Direction.CW)
    }

    fun release() {
        cardBitmap?.recycle()
        blurBitmap?.recycle()
        output.recycle()
    }
}

private fun HexColor.toArgb(): Int = 0xFF000000.toInt() or rgb24
