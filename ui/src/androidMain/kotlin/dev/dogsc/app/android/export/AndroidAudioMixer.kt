package dev.dogsc.app.android.export

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.dogsc.app.android.AndroidProjectRepository
import dev.dogsc.app.platform.ExportProgress
import dev.dogsc.core.model.MediaTimelinePlacement
import dev.dogsc.core.model.ProjectMediaReference
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.TimelineMap
import java.io.File
import java.io.FileOutputStream
import android.util.Log
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Chunked PCM mixer. Each source is decoded on demand at the output clock's
 * mapped source time, so cuts, trims and speed changes stay in sync without
 * materialising an hour of PCM in memory.
 */
class AndroidAudioMixer(
    private val context: android.content.Context,
    private val repository: AndroidProjectRepository,
) {
    private val outputSampleRate = 48_000
    private val outputChannels = 2

    fun exportAudio(
        project: RecorderProject,
        sourceDuration: Double,
        timelineMap: TimelineMap,
        output: File,
        onProgress: (ExportProgress) -> Unit,
    ): Boolean {
        val dir = repository.projectDirectory(project.id)
        val sources = mutableListOf<AudioSource>()
        val screenFile = project.media?.screen?.let { File(dir, it.relativePath) }
        val systemFile = project.media?.systemAudio?.let { File(dir, it.relativePath) }
        val microphoneFile = project.media?.microphone?.let { File(dir, it.relativePath) }

        val systemReference = project.media?.systemAudio
        val microphoneReference = project.media?.microphone
        val cameraReference = project.media?.camera

        if (systemFile != null && systemFile.exists()) {
            val track = runCatching { PcmTrack(systemFile) }.getOrNull()
            if (track != null) {
                val placement = MediaTimelinePlacement.from(
                    systemReference ?: ProjectMediaReference(systemFile.name),
                    sourceAvailableDuration = track.durationSeconds,
                    timelineDuration = sourceDuration,
                )
                sources += AudioSource(track, true) { time -> placement.sourceTimeAt(time) }
            }
        } else if (screenFile != null && screenFile.exists()) {
            val track = runCatching { PcmTrack(screenFile) }.getOrNull()
            if (track != null) {
                sources += AudioSource(track, true) { time -> timelineMap.sourceTimeAtOutput(time) }
            }
        }
        if (microphoneFile != null && microphoneFile.exists()) {
            val track = runCatching { PcmTrack(microphoneFile) }.getOrNull()
            if (track != null) {
                val placement = MediaTimelinePlacement.from(
                    microphoneReference ?: ProjectMediaReference(microphoneFile.name),
                    sourceAvailableDuration = track.durationSeconds,
                    timelineDuration = sourceDuration,
                )
                sources += AudioSource(track, false) { time -> placement.sourceTimeAt(time) }
            }
        }
        if (sources.isEmpty()) return false
        Log.d("DogSCAudio", "sources=${sources.size} system=${systemFile?.length()} mic=${microphoneFile?.length()}")
        // Fast path: one untouched source can be copied without decode/re-encode.
        if (sources.size == 1 &&
            project.timeline.primarySegmentAudioOverrides.isEmpty() &&
            project.audio.systemVolume >= 0.999 &&
            project.audio.microphoneVolume >= 0.999
        ) {
            val sourceFile = when {
                systemFile?.exists() == true -> systemFile
                microphoneFile?.exists() == true -> microphoneFile
                screenFile?.exists() == true -> screenFile
                else -> null
            }
            if (sourceFile != null) {
                val copied = runCatching { copyAudioTrack(sourceFile, output) }.getOrDefault(false)
                if (copied) {
                    Log.d("DogSCAudio", "copied audio track size=${output.length()}")
                    return true
                }
            }
        }

        val duration = timelineMap.outputDuration
        if (duration <= 0.01) return false
        val totalFrames = (duration * outputSampleRate).roundToInt().coerceAtLeast(1)
        val chunkFrames = outputSampleRate // one second
        val wavFile = File(output.parentFile, output.nameWithoutExtension + ".wav")
        val mixBuffer = FloatArray(chunkFrames * outputChannels)
        val sourceBuffer = FloatArray(chunkFrames * outputChannels)

        FileOutputStream(wavFile).use { stream ->
            writeWavHeader(stream, totalFrames)
            var written = 0
            while (written < totalFrames) {
                val frames = min(chunkFrames, totalFrames - written)
                java.util.Arrays.fill(mixBuffer, 0f)
                val time = written.toDouble() / outputSampleRate
                for (source in sources) {
                    val sourceTime = source.sourceTimeAt(time)
                    if (sourceTime == null) continue
                    source.track.readStereo(sourceTime, frames, outputSampleRate, sourceBuffer)
                    val (systemVolume, micVolume) = volumesAt(project, timelineMap, time)
                    val gain = (if (source.isSystem) systemVolume else micVolume).toFloat()
                    if (gain <= 0.0001) continue
                    for (i in 0 until frames * outputChannels) {
                        mixBuffer[i] = mixBuffer[i] + sourceBuffer[i] * gain
                    }
                }
                val bytes = ByteArray(frames * outputChannels * 2)
                var byteIndex = 0
                for (i in 0 until frames * outputChannels) {
                    val sample = (mixBuffer[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                    bytes[byteIndex++] = (sample.toInt() and 0xFF).toByte()
                    bytes[byteIndex++] = ((sample.toInt() shr 8) and 0xFF).toByte()
                }
                stream.write(bytes)
                written += frames
                if (written % (outputSampleRate * 10) == 0) {
                    Log.d("DogSCAudio", "mix $written/$totalFrames")
                }
                if (written % (outputSampleRate * 4) == 0) {
                    onProgress(ExportProgress(0.88f + 0.05f * written / totalFrames, "正在处理音频"))
                }
            }
        }
        Log.d("DogSCAudio", "wav done size=${wavFile.length()}")
        encodeWavToM4a(wavFile, output)
        Log.d("DogSCAudio", "encode done size=${output.length()}")
        wavFile.delete()
        return true
    }

    private fun volumesAt(project: RecorderProject, timelineMap: TimelineMap, time: Double): Pair<Double, Double> {
        val segment = timelineMap.segmentAtOutputTime(time)
        val overrides = segment?.let { project.timeline.primarySegmentAudioOverrides[it.id] }
        val systemMuted = overrides?.isSystemMuted ?: project.audio.isSystemMuted
        val micMuted = overrides?.isMicrophoneMuted ?: project.audio.isMicrophoneMuted
        val system = if (systemMuted) 0.0 else overrides?.systemVolume ?: project.audio.systemVolume
        val mic = if (micMuted) 0.0 else overrides?.microphoneVolume ?: project.audio.microphoneVolume
        return system to mic
    }

    private fun copyAudioTrack(source: File, output: File): Boolean {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val outTrack = muxer.addTrack(format)
            muxer.start()
            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else 1 shl 16
            val buffer = ByteBuffer.allocateDirect(maxSize.coerceAtLeast(1 shl 16))
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            return output.length() > 0
        } finally {
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
    }

    private fun writeWavHeader(stream: FileOutputStream, frames: Int) {
        val dataSize = frames * outputChannels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(outputChannels.toShort())
        header.putInt(outputSampleRate)
        header.putInt(outputSampleRate * outputChannels * 2)
        header.putShort((outputChannels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataSize)
        stream.write(header.array())
    }

    private fun encodeWavToM4a(wav: File, output: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outputSampleRate, outputChannels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        Log.d("DogSCAudio", "aac encode start")
        RandomAccessFile(wav, "r").use { file ->
            file.seek(44)
            val frameBytes = 1024 * outputChannels * 2
            val bytes = ByteArray(frameBytes)
            var ptsUs = 0L
            while (true) {
                val read = file.read(bytes)
                if (read <= 0) break
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: continue
                    input.clear()
                    input.put(bytes, 0, read)
                    codec.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                    ptsUs += (read / (outputChannels * 2).toLong()) * 1_000_000L / outputSampleRate
                }
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!started) {
                                track = muxer.addTrack(codec.outputFormat)
                                muxer.start()
                                started = true
                            }
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0 && started) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !started) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && started) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private data class AudioSource(
        val track: PcmTrack,
        val isSystem: Boolean,
        val sourceTimeAt: (Double) -> Double?,
    )
}

private class PcmTrack(file: File) {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val trackIndex: Int
    val durationSeconds: Double
    private val sourceSampleRate: Int
    private val sourceChannels: Int
    private var buffer = ShortArray(0)
    private var bufferStartTime = 0.0
    private var bufferFrames = 0
    private var readFrame = 0
    private var inputDone = false
    private var outputDone = false
    private var lastSeekTime = -1.0
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        extractor.setDataSource(file.absolutePath)
        trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("没有音频轨道")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        durationSeconds = (format.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)) / 1_000_000.0
        sourceSampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 48_000)
        sourceChannels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm")
        codec.configure(format, null, null, 0)
        codec.start()
        Log.d("DogSCAudio", "PcmTrack init rate=$sourceSampleRate ch=$sourceChannels dur=$durationSeconds")
    }

    fun readStereo(sourceTime: Double, frames: Int, outputRate: Int, output: FloatArray) {
        val neededEnd = sourceTime + frames.toDouble() * sourceSampleRate / outputRate
        ensureRange(sourceTime, neededEnd)
        val startFrame = ((sourceTime - bufferStartTime) * sourceSampleRate).roundToInt().coerceAtLeast(0)
        val step = sourceSampleRate.toDouble() / outputRate
        for (i in 0 until frames) {
            val sourcePos = startFrame + i * step
            val lower = sourcePos.toInt()
            val upper = min(lower + 1, bufferFrames - 1)
            val fraction = (sourcePos - lower).toFloat()
            val base = lower * sourceChannels
            val next = upper * sourceChannels
            val left = if (sourceChannels == 1) {
                sample(base) * (1 - fraction) + sample(next) * fraction
            } else {
                sample(base) * (1 - fraction) + sample(next) * fraction
            }
            val right = if (sourceChannels == 1) left else {
                sample(base + 1) * (1 - fraction) + sample(next + 1) * fraction
            }
            output[i * 2] = left
            output[i * 2 + 1] = right
        }
    }

    private fun sample(index: Int): Float {
        if (index < 0 || index >= buffer.size) return 0f
        return buffer[index] / 32768f
    }

    private fun ensureRange(startTime: Double, endTime: Double) {
        if (bufferFrames <= 0 || startTime < bufferStartTime - 0.05 || startTime > bufferStartTime + 1.8) {
            Log.d("DogSCAudio", "seek $startTime (bufferStart=$bufferStartTime frames=$bufferFrames)")
            seekTo(startTime)
        }
        var idle = 0
        while (bufferStartTime + bufferFrames.toDouble() / sourceSampleRate < endTime && !outputDone) {
            val before = bufferFrames
            decodeMore()
            if (bufferFrames == before) {
                idle++
                if (idle > 200) break
                Thread.sleep(2)
            } else {
                idle = 0
            }
        }
    }

    private fun seekTo(time: Double) {
        val safe = max(time - 0.25, 0.0)
        extractor.seekTo((safe * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        runCatching { codec.flush() }
        buffer = ShortArray(0)
        bufferFrames = 0
        readFrame = 0
        bufferStartTime = safe
        inputDone = false
        outputDone = false
        lastSeekTime = safe
    }

    private fun decodeMore() {
        var attempts = 0
        while (attempts < 40) {
            attempts++
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: continue
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(bufferInfo, 2_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (attempts == 1) Log.d("DogSCAudio", "decoder try again inputDone=$inputDone outputDone=$outputDone")
                    return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)
                    if (output != null && bufferInfo.size > 0) {
                        val shorts = ShortArray(bufferInfo.size / 2)
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        append(shorts, bufferInfo.presentationTimeUs / 1_000_000.0)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    return
                }
            }
        }
    }

    private fun append(shorts: ShortArray, startTime: Double) {
        if (bufferFrames == 0) {
            bufferStartTime = startTime
            buffer = shorts.copyOf()
            bufferFrames = shorts.size / sourceChannels
        } else {
            val combined = ShortArray(buffer.size + shorts.size)
            System.arraycopy(buffer, 0, combined, 0, buffer.size)
            System.arraycopy(shorts, 0, combined, buffer.size, shorts.size)
            buffer = combined
            bufferFrames += shorts.size / sourceChannels
        }
        // Keep at most ~3 seconds to bound memory; drop consumed head.
        Log.d("DogSCAudio", "append ${shorts.size} at $startTime bufferStart=$bufferStartTime frames=$bufferFrames")
        val maxSamples = sourceSampleRate * sourceChannels * 3
        if (buffer.size > maxSamples) {
            val dropSamples = buffer.size - maxSamples
            val dropFrames = dropSamples / sourceChannels
            buffer = buffer.copyOfRange(dropSamples, buffer.size)
            bufferStartTime += dropFrames.toDouble() / sourceSampleRate
            bufferFrames = buffer.size / sourceChannels
        }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    private fun MediaFormat.getLongOrDefault(key: String, default: Long): Long =
        if (containsKey(key)) getLong(key) else default

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}
