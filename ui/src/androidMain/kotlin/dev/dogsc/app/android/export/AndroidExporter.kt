package dev.dogsc.app.android.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.StatFs
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dev.dogsc.app.android.AndroidProjectRepository
import dev.dogsc.app.platform.ExportProgress
import dev.dogsc.app.platform.ExportRequest
import dev.dogsc.app.platform.ExportResult
import dev.dogsc.core.editor.TimelineEditor
import dev.dogsc.core.model.CameraMotionTrack
import dev.dogsc.core.model.CanvasDimensions
import dev.dogsc.core.model.CompositionSize
import dev.dogsc.core.model.ExportEncodingPolicy
import dev.dogsc.core.model.MediaTimelinePlacement
import dev.dogsc.core.model.ProjectPointerTrack
import dev.dogsc.core.model.ScreenMotionTrack
import dev.dogsc.core.model.StickerTransitionTrack
import dev.dogsc.core.model.TimelineMap
import dev.dogsc.core.model.TimelineMediaPlan
import dev.dogsc.core.model.ZoomAnimationTrack
import dev.dogsc.core.scene.FrameSceneEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AndroidExporter(
    private val context: Context,
    private val repository: AndroidProjectRepository,
) {
    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    suspend fun export(request: ExportRequest, onProgress: (ExportProgress) -> Unit): ExportResult =
        withContext(Dispatchers.Default) {
            cancelled = false
            try {
                val project = request.project
                val dir = repository.projectDirectory(project.id)
                val screenRef = project.media?.screen ?: return@withContext ExportResult.Failure("项目缺少录屏素材")
                val screenFile = File(dir, screenRef.relativePath)
                if (!screenFile.exists()) return@withContext ExportResult.Failure("录屏文件已丢失")
                val timelineMap = TimelineMap(project.timeline.sourceSequence, request.sourceDuration)
                val outputDuration = timelineMap.outputDuration
                if (outputDuration <= 0.01) return@withContext ExportResult.Failure("当前剪辑没有可导出的时长")
                val screenSource = VideoFrameSource(screenFile)
                val sourceAspect = screenSource.width.toDouble() / max(screenSource.height, 1)
                val trimStart = request.trimStart.coerceIn(0.0, (outputDuration - 0.01).coerceAtLeast(0.0))
                val trimEnd = (request.trimEnd ?: outputDuration).coerceIn(
                    (trimStart + 0.01).coerceAtMost(outputDuration),
                    outputDuration,
                )
                val exportDuration = (trimEnd - trimStart).coerceAtLeast(0.01)
                val pointerEvents = repository.loadPointerEvents(project)
                val pointerTrack = ProjectPointerTrack(timelineMap, pointerEvents)
                val screenTrack = ScreenMotionTrack(project.timeline.screenMotionClips)
                val cameraTrack = CameraMotionTrack(project.timeline.cameraMotionClips)
                val zoomTrack = ZoomAnimationTrack(project.timeline.zoomClips, outputDuration)
                val stickerTrack = StickerTransitionTrack(project.timeline.stickerClips)

                val cameraRef = project.media?.camera
                val cameraFile = cameraRef?.let { File(dir, it.relativePath) }
                val cameraSource = cameraFile?.takeIf { it.exists() }?.let { runCatching { VideoFrameSource(it) }.getOrNull() }
                val cameraPlacement = cameraRef?.let {
                    MediaTimelinePlacement.from(
                        it,
                        sourceAvailableDuration = cameraSource?.durationSeconds ?: request.sourceDuration,
                        timelineDuration = request.sourceDuration,
                    )
                }
                val cameraPlan = cameraPlacement?.let {
                    TimelineMediaPlan.auxiliary(
                        timelineMap,
                        it,
                        syncAnchors = cameraRef?.syncAnchors ?: emptyList(),
                    )
                }
                val cameraAspect = cameraSource?.let { it.width.toDouble() / max(it.height, 1) }

                val backgroundSource = project.canvas.backgroundSource
                val backgroundFile = when (backgroundSource) {
                    is dev.dogsc.core.model.BackgroundSource.ProjectImage -> File(dir, backgroundSource.relativePath)
                    is dev.dogsc.core.model.BackgroundSource.ProjectVideo -> File(dir, backgroundSource.relativePath)
                    is dev.dogsc.core.model.BackgroundSource.SystemImage -> File(backgroundSource.absolutePath)
                    is dev.dogsc.core.model.BackgroundSource.SystemVideo -> File(backgroundSource.absolutePath)
                    else -> null
                }
                val backgroundBitmap = backgroundFile?.takeIf { it.exists() && !backgroundSource.isVideo }?.let {
                    dev.dogsc.app.android.decodeBitmapCapped(it, 3_072)
                }
                val backgroundVideo = backgroundFile?.takeIf { it.exists() && backgroundSource.isVideo }?.let {
                    runCatching { VideoFrameSource(it) }.getOrNull()
                }

                val stickerBitmaps = mutableMapOf<String, Bitmap>()
                for (clip in project.timeline.stickerClips) {
                    val file = File(dir, clip.relativePath)
                    if (file.exists()) {
                        dev.dogsc.app.android.decodeBitmapCapped(file, 2_048)?.let { stickerBitmaps[clip.relativePath] = it }
                    }
                }

                val dimensions = if (request.audioOnly) {
                    CanvasDimensions(2, 2)
                } else {
                    project.canvas.pixelDimensions(
                        project.exportSettings.resolution,
                        sourceAspect,
                        CanvasDimensions(screenSource.width, screenSource.height),
                    )
                }
                val requestedWidth = (dimensions.width / 2 * 2).coerceAtLeast(2)
                val requestedHeight = (dimensions.height / 2 * 2).coerceAtLeast(2)
                val (outputWidth, outputHeight) = resolveEncoderSize(requestedWidth, requestedHeight)
                if (outputWidth != requestedWidth || outputHeight != requestedHeight) {
                    Log.w("DogSCExport", "encoder cannot do ${requestedWidth}x$requestedHeight; using ${outputWidth}x$outputHeight")
                }

                val cacheDir = File(context.cacheDir, "DogSCExport").apply { mkdirs() }
                val tempVideo = File(cacheDir, "video-${System.currentTimeMillis()}.mp4")
                val tempAudio = File(cacheDir, "audio-${System.currentTimeMillis()}.m4a")
                val finalFile = File(cacheDir, "final-${System.currentTimeMillis()}.mp4")
                fun closeSources() {
                    runCatching { screenSource.close() }
                    runCatching { cameraSource?.close() }
                    runCatching { backgroundVideo?.close() }
                }

                try {
                var audioReady = false
                val audioMixer = AndroidAudioMixer(context, repository)
                if (request.audioOnly) {
                    onProgress(ExportProgress(0.02f, "正在准备音频"))
                    audioReady = audioMixer.exportAudio(
                        project,
                        request.sourceDuration,
                        timelineMap,
                        tempAudio,
                        onProgress,
                        trimStart,
                        trimEnd,
                        isCancelled = { cancelled },
                    )
                    if (cancelled) { discardCancelledOutput(context, request); return@withContext ExportResult.Cancelled }
                    if (!audioReady) return@withContext ExportResult.Failure("当前剪辑没有可导出的声音")
                    copyToDestination(tempAudio, request.outputUri)
                    if (cancelled) { discardCancelledOutput(context, request); return@withContext ExportResult.Cancelled }
                    onProgress(ExportProgress(1f, "导出完成"))
                    closeSources()
                    return@withContext ExportResult.Success(request.outputUri, tempAudio.length())
                }

                onProgress(ExportProgress(0.02f, "正在准备素材"))
                val frameRate = EncoderCapabilities.frameRate(
                    outputWidth,
                    outputHeight,
                    project.exportSettings.frameRate.value,
                )
                val videoBitrate = EncoderCapabilities.bitrate(
                    outputWidth,
                    outputHeight,
                    frameRate,
                    ExportEncodingPolicy.videoBitrate(outputWidth, outputHeight, frameRate),
                )
                val requiredBytes = videoBitrate
                    .toLong() / 8L * exportDuration.toLong().coerceAtLeast(1L) + 32L * 1024 * 1024
                val freeBytes = runCatching { StatFs(context.cacheDir.path).availableBytes }
                    .getOrDefault(Long.MAX_VALUE)
                if (freeBytes < requiredBytes) {
                    return@withContext ExportResult.Failure("存储空间不足，请清理后重试")
                }
                Log.d("DogSCExport", "video start ${outputWidth}x${outputHeight} range=$trimStart..$trimEnd duration=$exportDuration")
                val encoder = createVideoEncoder(
                    outputWidth,
                    outputHeight,
                    frameRate,
                    videoBitrate,
                    tempVideo,
                )
                val renderer = CanvasFrameRenderer(outputWidth, outputHeight)
                val frameCount = ceil(exportDuration * frameRate).toInt().coerceAtLeast(1)
                val sourceFps = screenSource.frameRate.coerceIn(1.0, 120.0)
                val renderFps = min(frameRate.toDouble(), sourceFps)
                val frameStep = max(1, (frameRate / renderFps).roundToInt())
                var lastSourceFrame: Bitmap? = null
                var lastCameraFrame: Bitmap? = null
                var lastRenderedBitmap: Bitmap? = null

                try {
                    for (index in 0 until frameCount) {
                        if (cancelled) { discardCancelledOutput(context, request); return@withContext ExportResult.Cancelled }
                        val time = trimStart + min(index.toDouble() / frameRate, exportDuration)
                        val sourceTime = timelineMap.sourceTimeAtOutput(time)
                            ?: timelineMap.sourceTimeAtOutput(min(time, outputDuration - 0.0001))
                            ?: 0.0
                        if (lastRenderedBitmap != null && index % frameStep != 0) {
                            encoder.encodeFrame(lastRenderedBitmap!!, (index.toDouble() / frameRate * 1_000_000).toLong())
                            if (index % 12 == 0) {
                                val fraction = 0.03f + 0.82f * (index + 1) / frameCount
                                onProgress(ExportProgress(fraction, "正在合成与编码 ${index + 1}/$frameCount"))
                            }
                            continue
                        }
                        val decodeStart = android.os.SystemClock.elapsedRealtime()
                        val screenBitmap = if (lastSourceFrame != null && absTime(sourceTime, lastSourceTime) < 0.004) {
                            lastSourceFrame
                        } else {
                            screenSource.frameAt(sourceTime) ?: lastSourceFrame
                        }
                        val decodeEnd = android.os.SystemClock.elapsedRealtime()
                        lastSourceFrame = screenBitmap
                        lastSourceTime = sourceTime
                        val cameraTime = cameraPlan?.sourceTimeAtOutput(time) ?: sourceTime
                        val cameraBitmap = cameraSource?.let { source ->
                            if (lastCameraFrame != null && absTime(cameraTime, lastCameraTime) < 0.004) {
                                lastCameraFrame
                            } else {
                                source.frameAt(cameraTime) ?: lastCameraFrame
                            }
                        }
                        lastCameraFrame = cameraBitmap
                        lastCameraTime = cameraTime
                        val background = if (backgroundVideo != null) {
                            backgroundVideo.frameAt(time % max(backgroundVideo.durationSeconds, 0.1))
                        } else {
                            backgroundBitmap
                        }
                        val renderStart = android.os.SystemClock.elapsedRealtime()
                        val scene = FrameSceneEvaluator.renderPlan(
                            project = project,
                            presentationTime = time,
                            outputDuration = outputDuration,
                            frameRate = frameRate,
                            canvasSize = CompositionSize(outputWidth.toDouble(), outputHeight.toDouble()),
                            sourceAspectRatio = sourceAspect,
                            cameraSourceSize = cameraAspect?.let { CompositionSize(it, 1.0) },
                            pointerTrack = pointerTrack,
                            zoomTrack = zoomTrack,
                            screenMotionTrack = screenTrack,
                            cameraMotionTrack = cameraTrack,
                            stickerTrack = stickerTrack,
                            cameraAvailable = cameraSource != null,
                        ).scene
                        val bitmap = renderer.render(scene, screenBitmap, cameraBitmap, background, stickerBitmaps)
                        lastRenderedBitmap = bitmap
                        val renderEnd = android.os.SystemClock.elapsedRealtime()
                        encoder.encodeFrame(bitmap, (index.toDouble() / frameRate * 1_000_000).toLong())
                        val encodeEnd = android.os.SystemClock.elapsedRealtime()
                        if (index % 30 == 0) {
                            Log.d("DogSCExport", "frame $index decode=${decodeEnd - decodeStart}ms render=${renderEnd - renderStart}ms encode=${encodeEnd - renderEnd}ms")
                        }
                        if (index % 12 == 0) {
                            val fraction = 0.03f + 0.82f * (index + 1) / frameCount
                            onProgress(
                                ExportProgress(
                                    fraction,
                                    "正在合成与编码 ${index + 1}/$frameCount",
                                    if (index > 8) (trimEnd - time) * 0.7 else null,
                                )
                            )
                        }
                    }
                    encoder.finish()
                } finally {
                    renderer.release()
                    encoder.release()
                }
                if (cancelled) { discardCancelledOutput(context, request); return@withContext ExportResult.Cancelled }

                Log.d("DogSCExport", "video encoded, audio start")
                onProgress(ExportProgress(0.88f, "正在处理音频"))
                audioReady = audioMixer.exportAudio(
                    project,
                    request.sourceDuration,
                    timelineMap,
                    tempAudio,
                    onProgress,
                    trimStart,
                    trimEnd,
                    isCancelled = { cancelled },
                )
                if (cancelled) { discardCancelledOutput(context, request); return@withContext ExportResult.Cancelled }

                onProgress(ExportProgress(0.94f, "正在完成文件"))
                if (audioReady) {
                    muxVideoAndAudio(tempVideo, tempAudio, finalFile)
                } else {
                    tempVideo.copyTo(finalFile, overwrite = true)
                }
                if (cancelled) { discardCancelledOutput(context, request); return@withContext ExportResult.Cancelled }
                copyToDestination(finalFile, request.outputUri)
                if (cancelled) { discardCancelledOutput(context, request); return@withContext ExportResult.Cancelled }
                val size = finalFile.length()
                tempVideo.delete()
                tempAudio.delete()
                finalFile.delete()
                onProgress(ExportProgress(1f, "导出完成"))
                closeSources()
                ExportResult.Success(request.outputUri, size, outputWidth, outputHeight, frameRate)
                } finally {
                    // Success, failure and cancellation all leave through here. Without this,
                    // a failed export kept MediaCodec decoders and multi-hundred-MB temp files.
                    runCatching { closeSources() }
                    runCatching { tempVideo.delete() }
                    runCatching { tempAudio.delete() }
                    runCatching { finalFile.delete() }
                }
            } catch (error: Throwable) {
                Log.e("DogSCExport", "export failed", error)
                if (cancelled) {
                    discardCancelledOutput(context, request)
                    ExportResult.Cancelled
                } else {
                    ExportResult.Failure(error.message ?: "导出失败")
                }
            }
        }

    private var lastSourceTime = -1.0
    private var lastCameraTime = -1.0

    private fun absTime(a: Double, b: Double): Double = kotlin.math.abs(a - b)

    private fun copyToDestination(source: File, outputUri: String) {
        val uri = Uri.parse(outputUri)
        val output = if (uri.scheme == "file") {
            FileOutputStream(File(uri.path ?: error("无效的输出路径")))
        } else {
            context.contentResolver.openOutputStream(uri) ?: error("无法写入导出文件")
        }
        output.use { target ->
            source.inputStream().use { input -> input.copyTo(target, 1 shl 20) }
        }
    }

    private fun muxVideoAndAudio(video: File, audio: File, output: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)
            val videoTrack = (0 until videoExtractor.trackCount).first {
                videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val audioTrack = (0 until audioExtractor.trackCount).first {
                audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = muxer.addTrack(audioFormat)
            muxer.start()
            copyTrack(videoExtractor, videoTrack, muxer, outVideo)
            copyTrack(audioExtractor, audioTrack, muxer, outAudio)
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun copyTrack(extractor: MediaExtractor, track: Int, muxer: MediaMuxer, outTrack: Int) {
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            1 shl 20
        }
        val buffer = ByteBuffer.allocateDirect(maxSize.coerceAtLeast(1 shl 16))
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(outTrack, buffer, info)
            extractor.advance()
        }
    }
}

    /** A cancelled SAF export leaves an empty placeholder; remove it instead of the result. */
    private fun discardCancelledOutput(context: Context, request: ExportRequest) {
        runCatching {
            val uri = Uri.parse(request.outputUri)
            if (uri.scheme == "content") {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else {
                File(uri.path ?: request.outputUri).delete()
            }
        }
    }

    /** Not every Android encoder supports 4K; fall back to the largest size it reports. */
    private fun resolveEncoderSize(width: Int, height: Int): Pair<Int, Int> =
        EncoderCapabilities.clamp(width, height)

internal class CanvasVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitrate: Int,
    private val output: File,
) : VideoEncoder {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private val bufferInfo = MediaCodec.BufferInfo()
    private val argb = IntArray(width * height)
    private var yuv = ByteArray(width * height * 3 / 2)

    init {
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
        val codecInfo = codec.codecInfo ?: throw IllegalStateException("设备没有可用的 H.264 编码器")
        val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        colorFormat = when {
            capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            else -> capabilities.colorFormats.firstOrNull {
                it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            } ?: throw IllegalStateException("编码器不支持 YUV420 输入")
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val configured = runCatching {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }.isSuccess || runCatching {
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }.isSuccess
        if (!configured) error("编码器不支持当前导出参数")
        codec.start()
        } catch (error: Throwable) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            throw error
        }
        muxer = try {
            MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (error: Throwable) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            throw error
        }
    }

    override fun encodeFrame(bitmap: Bitmap, presentationTimeUs: Long) {
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        argbToYuv420()
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val input = codec.getInputBuffer(inputIndex) ?: return
            input.clear()
            input.put(yuv)
            codec.queueInputBuffer(inputIndex, 0, yuv.size, presentationTimeUs, 0)
        }
        drain(false)
    }

    override fun finish() {
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(true)
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    override fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // A broken encoder must not hang export forever after EOS was queued.
                    if (++idle > 500) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    idle = 0
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && bufferInfo.size > 0 && muxerStarted) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun argbToYuv420() {
        val frameSize = width * height
        if (yuv.size != frameSize * 3 / 2) yuv = ByteArray(frameSize * 3 / 2)
        if (NativeColor.isAvailable) {
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                NativeColor.argbToNv12(argb, yuv, width, height)
            } else {
                NativeColor.argbToI420(argb, yuv, width, height)
            }
            return
        }
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            var yIndex = 0
            var uvIndex = frameSize
            for (j in 0 until height) {
                for (i in 0 until width) {
                    val color = argb[j * width + i]
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                    if (j % 2 == 0 && i % 2 == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        } else {
            var yIndex = 0
            var uIndex = frameSize
            var vIndex = frameSize + frameSize / 4
            for (j in 0 until height) {
                for (i in 0 until width) {
                    val color = argb[j * width + i]
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                    if (j % 2 == 0 && i % 2 == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        yuv[uIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[vIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }
}
