package dev.dogsc.app.android.recording

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.File
import kotlin.math.max

class ScreenRecorder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val frameRate: Int,
    private val outputFile: File,
    codecMime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
) {
    private val codec: MediaCodec
    private val inputSurface: Surface
    private val writer: FragmentedRecordingWriter
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var muxerStarted = false
    @Volatile var lastPresentationTimeUs: Long = 0
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var resolvedMime: String = MediaFormat.MIMETYPE_VIDEO_AVC
        private set

    init {
        val requested = if (codecMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true)) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val candidates = if (requested == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
        } else {
            listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
        }
        var encoder: MediaCodec? = null
        for (candidate in candidates) {
            val codecCandidate = runCatching { MediaCodec.createEncoderByType(candidate) }.getOrNull()
                ?: continue
            val format = MediaFormat.createVideoFormat(candidate, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(width, height, frameRate))
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            if (runCatching {
                    codecCandidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }.isSuccess
            ) {
                encoder = codecCandidate
                resolvedMime = candidate
                break
            }
            runCatching { codecCandidate.release() }
        }
        codec = encoder ?: throw IllegalStateException("设备没有可用的视频编码器")
        inputSurface = codec.createInputSurface()
        // Fragments are flushed every 2 s, so a crash keeps all footage up to the last fragment.
        writer = FragmentedRecordingWriter(FragmentedRecordingRecovery.partFileFor(outputFile))
        codec.start()
    }

    fun start() {
        running = true
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                error = "系统已停止屏幕捕获"
                stop()
            }
        }, Handler(Looper.getMainLooper()))
        virtualDisplay = projection.createVirtualDisplay(
            "DogSC",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null,
        )
        drainThread = Thread({ drainLoop() }, "DogSC-ScreenEncoder").apply { start() }
    }

    fun stop() {
        if (!running) return
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { codec.signalEndOfInputStream() }
        drainThread?.join(2500)
        running = false
        drainThread = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
        writer.closeFragments()
        FragmentedRecordingRecovery.finish(FragmentedRecordingRecovery.partFileFor(outputFile), outputFile)
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        writer.addTrack(codec.outputFormat)
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && muxerStarted) {
                        writer.writeSample(buffer, info)
                        lastPresentationTimeUs = max(lastPresentationTimeUs, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    companion object {
        fun bitrateFor(width: Int, height: Int, frameRate: Int): Int {
            val pixels = width.toLong() * height
            val ceiling = when {
                pixels >= 7_000_000 -> 60_000_000
                pixels >= 3_500_000 -> 40_000_000
                else -> 24_000_000
            }
            return (pixels * frameRate / 6).coerceIn(8_000_000, ceiling.toLong()).toInt()
        }
    }
}
