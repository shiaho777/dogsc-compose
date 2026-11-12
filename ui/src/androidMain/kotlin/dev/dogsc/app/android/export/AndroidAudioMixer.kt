package dev.dogsc.app.android.export

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import dev.dogsc.app.android.AndroidProjectRepository
import dev.dogsc.app.platform.ExportProgress
import dev.dogsc.core.model.MediaTimelinePlacement
import dev.dogsc.core.model.ProjectMediaReference
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.TimelineMap
import dev.dogsc.core.model.TimelineMediaPlan
import dev.dogsc.core.model.TimelineMediaSlice
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
private const val AAC_FRAME = 1024

/** Typical AAC-LC encoder priming on Android when the probe cannot measure it. */
private const val DEFAULT_PRIMING_FRAMES = 2048

/** Encoder priming is a property of the device codec, so it is measured once per process. */
@Volatile
private var cachedAacPrimingPackets: Int? = null

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
        trimStart: Double = 0.0,
        trimEnd: Double? = null,
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        val sources = mutableListOf<AudioSource>()
        try {
            return mixSources(project, sourceDuration, timelineMap, output, onProgress, trimStart, trimEnd, sources, isCancelled)
        } finally {
            // Decoders are a scarce system resource; release them on success, failure and cancel.
            for (source in sources) {
                source.stretchers.values.forEach { runCatching { it.release() } }
                runCatching { source.track.release() }
            }
        }
    }

    private fun mixSources(
        project: RecorderProject,
        sourceDuration: Double,
        timelineMap: TimelineMap,
        output: File,
        onProgress: (ExportProgress) -> Unit,
        trimStart: Double,
        trimEnd: Double?,
        sources: MutableList<AudioSource>,
        isCancelled: () -> Boolean,
    ): Boolean {
        val dir = repository.projectDirectory(project.id)
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
                val plan = TimelineMediaPlan.auxiliary(
                    timelineMap,
                    placement,
                    syncAnchors = systemReference?.syncAnchors ?: emptyList(),
                )
                sources += AudioSource(track, true, plan.slices)
            }
        } else if (screenFile != null && screenFile.exists()) {
            val track = runCatching { PcmTrack(screenFile) }.getOrNull()
            if (track != null) {
                sources += AudioSource(track, true, TimelineMediaPlan.primary(timelineMap).slices)
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
                val plan = TimelineMediaPlan.auxiliary(
                    timelineMap,
                    placement,
                    syncAnchors = microphoneReference?.syncAnchors ?: emptyList(),
                )
                sources += AudioSource(track, false, plan.slices)
            }
        }
        if (sources.isEmpty()) return false
        Log.d("DogSCAudio", "sources=${sources.size} system=${systemFile?.length()} mic=${microphoneFile?.length()}")
        // Fast path: one untouched source can be copied without decode/re-encode.
        val singleIsSystem = systemFile?.exists() == true || microphoneFile?.exists() != true
        val singleIsAudible = if (singleIsSystem) !project.audio.isSystemMuted else !project.audio.isMicrophoneMuted
        val simplePrimary = timelineMap.segments.size == 1 &&
            timelineMap.segments[0].sourceStart < 0.001 &&
            kotlin.math.abs(timelineMap.segments[0].playbackRate - 1.0) < 0.001
        val singleReference = when {
            systemFile?.exists() == true -> systemReference
            microphoneFile?.exists() == true -> microphoneReference
            else -> project.media?.screen
        }
        val hasStartOffset = (singleReference?.startOffset ?: 0.0) > 0.001
        if (sources.size == 1 &&
            singleIsAudible &&
            simplePrimary &&
            !hasStartOffset &&
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
                val copied = runCatching {
                    copyAudioTrack(sourceFile, output, trimStart, trimEnd, isCancelled)
                }.getOrDefault(false)
                if (copied) {
                    Log.d("DogSCAudio", "copied audio track size=${output.length()}")
                    return true
                }
            }
        }
        if (isCancelled()) return false

        val duration = timelineMap.outputDuration
        if (duration <= 0.01) return false
        val startTime = trimStart.coerceIn(0.0, (duration - 0.01).coerceAtLeast(0.0))
        val endTime = (trimEnd ?: duration).coerceIn((startTime + 0.01).coerceAtMost(duration), duration)
        val totalFrames = ((endTime - startTime) * outputSampleRate).roundToInt().coerceAtLeast(1)
        val chunkFrames = outputSampleRate // one second
        val wavFile = File(output.parentFile, output.nameWithoutExtension + ".wav")
        val mixBuffer = FloatArray(chunkFrames * outputChannels)
        val sourceBuffer = FloatArray(chunkFrames * outputChannels)

        try {
        FileOutputStream(wavFile).use { stream ->
            writeWavHeader(stream, totalFrames)
            var written = 0
            while (written < totalFrames) {
                if (isCancelled()) return false
                val frames = min(chunkFrames, totalFrames - written)
                java.util.Arrays.fill(mixBuffer, 0f)
                val chunkStart = startTime + written.toDouble() / outputSampleRate
                val chunkEnd = chunkStart + frames.toDouble() / outputSampleRate
                for (source in sources) {
                    // Each slice is one primary segment (or part of one): constant speed and gain,
                    // so cuts, speed changes and per-segment volume switch on the exact sample.
                    for (slice in source.slices) {
                        val from = max(slice.outputStart, chunkStart)
                        val to = min(slice.outputEnd, chunkEnd)
                        if (to <= from) continue
                        val (systemVolume, micVolume) = volumesAt(project, timelineMap, (from + to) / 2)
                        val gain = (if (source.isSystem) systemVolume else micVolume).toFloat()
                        if (gain <= 0.0001f) continue
                        var frameFrom = ((from - chunkStart) * outputSampleRate).roundToInt().coerceIn(0, frames)
                        val frameTo = ((to - chunkStart) * outputSampleRate).roundToInt().coerceIn(frameFrom, frames)
                        val scale = slice.sourceTimeScale
                        if (kotlin.math.abs(scale - 1.0) > 0.000001) {
                            // Upstream renders retimed audio with a pitch-preserving stretch (.spectral);
                            // Sonic (WSOLA) is the Android equivalent, one stateful instance per slice.
                            val stretcher = source.stretchers.getOrPut(slice.id) {
                                SliceStretcher(source.track, slice, outputSampleRate)
                            }
                            val outputTime = chunkStart + frameFrom.toDouble() / outputSampleRate
                            val sliceOffset = ((outputTime - slice.outputStart) * outputSampleRate).roundToInt().toLong()
                            val count = frameTo - frameFrom
                            stretcher.read(sliceOffset, count, sourceBuffer)
                            val base = frameFrom * outputChannels
                            for (i in 0 until count * outputChannels) {
                                mixBuffer[base + i] = mixBuffer[base + i] + sourceBuffer[i] * gain
                            }
                            continue
                        }
                        // Read at most ~1 s of source per call so the decode window stays bounded at 100×.
                        val maxPiece = max(1, (outputSampleRate / max(scale, 1.0)).toInt())
                        while (frameFrom < frameTo) {
                            val count = min(maxPiece, frameTo - frameFrom)
                            val outputTime = chunkStart + frameFrom.toDouble() / outputSampleRate
                            val sourceTime = max(slice.sourceStart + (outputTime - slice.outputStart) * scale, 0.0)
                            source.track.readStereo(sourceTime, count, outputSampleRate, scale, sourceBuffer)
                            val base = frameFrom * outputChannels
                            for (i in 0 until count * outputChannels) {
                                mixBuffer[base + i] = mixBuffer[base + i] + sourceBuffer[i] * gain
                            }
                            frameFrom += count
                        }
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
        encodeWavToM4a(wavFile, output, isCancelled)
        Log.d("DogSCAudio", "encode done size=${output.length()}")
        return true
        } finally {
            // The intermediate WAV is uncompressed (hundreds of MB for long recordings).
            runCatching { wavFile.delete() }
        }
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

    private fun copyAudioTrack(
        source: File,
        output: File,
        trimStart: Double,
        trimEnd: Double?,
        isCancelled: () -> Boolean,
    ): Boolean {
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
            val startUs = (trimStart * 1_000_000).toLong()
            val endUs = ((trimEnd ?: Double.MAX_VALUE) * 1_000_000).toLong()
            var wrote = false
            while (true) {
                if (isCancelled()) return false
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleUs = extractor.sampleTime
                if (sampleUs >= endUs) break
                if (sampleUs >= startUs) {
                    info.set(0, size, sampleUs - startUs, extractor.sampleFlags)
                    muxer.writeSampleData(outTrack, buffer, info)
                    wrote = true
                }
                extractor.advance()
            }
            muxer.stop()
            return wrote && output.length() > 0
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

    /**
     * MediaMuxer cannot write the edit list that tells players to skip AAC encoder priming, so the
     * priming packets are dropped and the remaining packets are re-stamped from zero. Without this
     * the whole audio track lands ~43 ms after the video.
     */
    private fun encodeWavToM4a(wav: File, output: File, isCancelled: () -> Boolean) {
        val primingPackets = aacPrimingPackets()
        val bytesPerFrame = outputChannels * 2
        val pcmFrames = ((wav.length() - 44).coerceAtLeast(0) / bytesPerFrame)
        // The encoder holds back roughly its priming delay; trailing silence flushes the real tail out,
        // and the packet cap trims the silence so the track ends exactly where the mix ends.
        val maxPackets = (pcmFrames + AAC_FRAME - 1) / AAC_FRAME
        var flushBytes = (primingPackets + 2L) * AAC_FRAME * bytesPerFrame
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var seen = 0
        var kept = 0L
        val keptInfo = MediaCodec.BufferInfo()
        try {
            RandomAccessFile(wav, "r").use { file ->
                file.seek(44)
                encodeAac(
                    readPcm = { bytes ->
                        if (isCancelled()) return@encodeAac -1
                        val read = file.read(bytes)
                        if (read > 0) {
                            read
                        } else if (flushBytes > 0) {
                            val count = min(bytes.size.toLong(), flushBytes).toInt()
                            java.util.Arrays.fill(bytes, 0, count, 0)
                            flushBytes -= count
                            count
                        } else {
                            -1
                        }
                    },
                    onFormat = { format ->
                        if (track < 0) {
                            track = muxer.addTrack(format)
                            muxer.start()
                        }
                    },
                    onPacket = { buffer, info ->
                        if (seen++ >= primingPackets && track >= 0 && kept < maxPackets) {
                            val ptsUs = kept * AAC_FRAME * 1_000_000L / outputSampleRate
                            keptInfo.set(info.offset, info.size, ptsUs, info.flags)
                            muxer.writeSampleData(track, buffer, keptInfo)
                            kept++
                        }
                    },
                )
            }
            if (track >= 0) muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
    }

    /** Feeds PCM from [readPcm] (returns bytes read, ≤0 at end) through an AAC-LC encoder. */
    private fun encodeAac(
        readPcm: (ByteArray) -> Int,
        onFormat: (MediaFormat) -> Unit,
        onPacket: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    ) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outputSampleRate, outputChannels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val bytes = ByteArray(AAC_FRAME * outputChannels * 2)
            var pending = 0
            var framesQueued = 0L
            var inputDone = false
            var idle = 0
            while (true) {
                if (!inputDone) {
                    if (pending == 0) pending = readPcm(bytes).let { if (it <= 0) -1 else it }
                    // Keep the chunk until the codec accepts it; a busy codec must never drop PCM.
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val ptsUs = framesQueued * 1_000_000L / outputSampleRate
                        if (pending < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val input = codec.getInputBuffer(inputIndex) ?: error("AAC encoder input unavailable")
                            input.clear()
                            input.put(bytes, 0, pending)
                            codec.queueInputBuffer(inputIndex, 0, pending, ptsUs, 0)
                            framesQueued += pending / (outputChannels * 2)
                            pending = 0
                        }
                    }
                }
                var finished = false
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (inputDone && ++idle > 500) error("AAC encoder stalled")
                        break
                    }
                    idle = 0
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        onFormat(codec.outputFormat)
                    } else if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && info.size > 0 && !isConfig) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            onPacket(buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            finished = true
                            break
                        }
                    }
                }
                if (finished) break
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /** Number of leading AAC packets that only carry encoder priming on this device. */
    private fun aacPrimingPackets(): Int {
        cachedAacPrimingPackets?.let { return it }
        val delay = runCatching { measureAacDelayFrames() }
            .onFailure { Log.w("DogSCAudio", "AAC delay probe failed", it) }
            .getOrNull()
        val packets = if (delay != null) (delay + AAC_FRAME / 2) / AAC_FRAME else DEFAULT_PRIMING_FRAMES / AAC_FRAME
        Log.d("DogSCAudio", "AAC priming delay=$delay frames → drop $packets packets")
        cachedAacPrimingPackets = packets
        return packets
    }

    /** Encodes and decodes a noise burst, then finds the lag that best aligns output with input. */
    private fun measureAacDelayFrames(): Int? {
        val frames = outputSampleRate / 2
        val random = java.util.Random(7)
        val probe = FloatArray(frames) { (random.nextFloat() * 2f - 1f) * 0.3f }
        val pcm = ByteBuffer.allocate(frames * outputChannels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (value in probe) {
            val sample = (value * 32767f).roundToInt().toShort()
            repeat(outputChannels) { pcm.putShort(sample) }
        }
        val pcmBytes = pcm.array()
        var offset = 0
        var format: MediaFormat? = null
        val packets = ArrayList<ByteArray>()
        encodeAac(
            readPcm = { dst ->
                val count = min(dst.size, pcmBytes.size - offset)
                if (count <= 0) -1 else {
                    System.arraycopy(pcmBytes, offset, dst, 0, count)
                    offset += count
                    count
                }
            },
            onFormat = { format = it },
            onPacket = { buffer, _ -> packets += ByteArray(buffer.remaining()).also { buffer.get(it) } },
        )
        val decoded = decodeAacFirstChannel(format ?: return null, packets) ?: return null
        val start = 4096
        val window = 4096
        val maxLag = 6144
        if (decoded.size < start + window + maxLag) return null
        var probeEnergy = 0.0
        for (n in start until start + window) probeEnergy += probe[n].toDouble() * probe[n]
        var bestLag = -1
        var bestScore = 0.0
        for (lag in 0..maxLag) {
            var dot = 0.0
            var energy = 0.0
            for (n in start until start + window) {
                val y = decoded[n + lag].toDouble()
                dot += probe[n] * y
                energy += y * y
            }
            val score = dot / kotlin.math.sqrt(energy * probeEnergy + 1e-12)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        return if (bestScore > 0.5) bestLag else null
    }

    private fun decodeAacFirstChannel(format: MediaFormat, packets: List<ByteArray>): FloatArray? {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var out = FloatArray(packets.size * AAC_FRAME + AAC_FRAME)
        var size = 0
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var next = 0
            var inputDone = false
            var idle = 0
            while (idle < 300) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        if (next < packets.size) {
                            val input = codec.getInputBuffer(inputIndex) ?: return null
                            input.clear()
                            input.put(packets[next])
                            codec.queueInputBuffer(inputIndex, 0, packets[next].size, next * AAC_FRAME * 1_000_000L / outputSampleRate, 0)
                            next++
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    index >= 0 -> {
                        idle = 0
                        val output = codec.getOutputBuffer(index)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val shorts = output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = shorts.remaining() / channels
                            if (size + frames > out.size) out = out.copyOf(max(out.size * 2, size + frames))
                            for (i in 0 until frames) out[size + i] = shorts.get(i * channels) / 32768f
                            size += frames
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return out.copyOf(size)
                    }
                    else -> idle++
                }
            }
            return null
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private data class AudioSource(
        val track: PcmTrack,
        val isSystem: Boolean,
        val slices: List<TimelineMediaSlice>,
    ) {
        val stretchers = HashMap<String, SliceStretcher>()
    }
}

/**
 * Pitch-preserving time stretch for one retimed slice. Source audio is read at 1× and fed
 * through Sonic at the slice's speed; output frames are handed out sequentially.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private class SliceStretcher(
    private val track: PcmTrack,
    private val slice: TimelineMediaSlice,
    private val outputRate: Int,
) {
    private val processor = SonicAudioProcessor().apply {
        setSpeed(slice.sourceTimeScale.toFloat())
        configure(AudioProcessor.AudioFormat(outputRate, 2, C.ENCODING_PCM_16BIT))
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }
    private val feedFrames = 4096
    private val readBuffer = FloatArray(feedFrames * 2)
    private val inputBytes = ByteBuffer.allocateDirect(feedFrames * 4).order(ByteOrder.nativeOrder())
    private var queue = FloatArray(feedFrames * 4)
    private var queueStart = 0
    private var queueEnd = 0
    private var sourceCursor = slice.sourceStart
    private var produced = -1L
    private var endQueued = false
    private var ended = false

    fun read(sliceOffsetFrames: Long, frames: Int, output: FloatArray) {
        if (produced < 0 || kotlin.math.abs(sliceOffsetFrames - produced) > 2) restart(sliceOffsetFrames)
        var filled = 0
        while (filled < frames) {
            val available = (queueEnd - queueStart) / 2
            if (available > 0) {
                val take = min(available, frames - filled)
                System.arraycopy(queue, queueStart, output, filled * 2, take * 2)
                queueStart += take * 2
                filled += take
                continue
            }
            if (ended) {
                java.util.Arrays.fill(output, filled * 2, frames * 2, 0f)
                break
            }
            feed()
        }
        produced = sliceOffsetFrames + frames
    }

    private fun restart(sliceOffsetFrames: Long) {
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        queueStart = 0
        queueEnd = 0
        endQueued = false
        ended = false
        sourceCursor = slice.sourceStart + sliceOffsetFrames.toDouble() / outputRate * slice.sourceTimeScale
        produced = sliceOffsetFrames
    }

    private fun feed() {
        val remaining = ((slice.sourceEnd - sourceCursor) * outputRate).toInt()
        if (remaining > 0) {
            val n = min(feedFrames, remaining)
            track.readStereo(sourceCursor, n, outputRate, 1.0, readBuffer)
            inputBytes.clear()
            for (i in 0 until n * 2) {
                inputBytes.putShort((readBuffer[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
            }
            inputBytes.flip()
            processor.queueInput(inputBytes)
            sourceCursor += n.toDouble() / outputRate
        } else if (!endQueued) {
            processor.queueEndOfStream()
            endQueued = true
        }
        drain()
        if (endQueued && processor.isEnded) ended = true
    }

    private fun drain() {
        val out = processor.output
        if (!out.hasRemaining()) return
        val shorts = out.order(ByteOrder.nativeOrder()).asShortBuffer()
        val count = shorts.remaining()
        if (queueStart > 0) {
            System.arraycopy(queue, queueStart, queue, 0, queueEnd - queueStart)
            queueEnd -= queueStart
            queueStart = 0
        }
        if (queueEnd + count > queue.size) queue = queue.copyOf(max(queue.size * 2, queueEnd + count))
        for (i in 0 until count) queue[queueEnd + i] = shorts.get(i) / 32768f
        queueEnd += count
        out.position(out.limit())
    }

    fun release() {
        processor.reset()
    }
}

private class PcmTrack(file: File) {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val trackIndex: Int
    val durationSeconds: Double
    private val sourceSampleRate: Int
    private val sourceChannels: Int
    private var buffer = ShortArray(0)
    private var bufferSize = 0
    private var bufferStartTime = 0.0
    private var bufferFrames = 0
    private var readFrame = 0
    private var inputDone = false
    private var outputDone = false
    private var lastSeekTime = -1.0
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        var created: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("没有音频轨道")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            durationSeconds = (format.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)) / 1_000_000.0
            sourceSampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            sourceChannels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            created = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm")
            created.configure(format, null, null, 0)
            created.start()
            codec = created
        } catch (error: Throwable) {
            runCatching { created?.stop() }
            runCatching { created?.release() }
            runCatching { extractor.release() }
            throw error
        }
    }

    /** Reads [frames] output frames starting at [sourceTime], advancing [rate] source seconds per output second. */
    fun readStereo(sourceTime: Double, frames: Int, outputRate: Int, rate: Double, output: FloatArray) {
        val neededEnd = sourceTime + frames.toDouble() * rate / outputRate + 2.0 / sourceSampleRate
        ensureRange(sourceTime, neededEnd)
        val startPos = (sourceTime - bufferStartTime) * sourceSampleRate
        val step = sourceSampleRate.toDouble() * rate / outputRate
        for (i in 0 until frames) {
            val sourcePos = startPos + i * step
            if (sourcePos < 0) {
                output[i * 2] = 0f
                output[i * 2 + 1] = 0f
                continue
            }
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
        if (index < 0 || index >= bufferSize) return 0f
        return buffer[index] / 32768f
    }

    private fun ensureRange(startTime: Double, endTime: Double) {
        val bufferEnd = bufferStartTime + bufferFrames.toDouble() / sourceSampleRate
        if (bufferFrames <= 0 || startTime < bufferStartTime - 0.002 || startTime > bufferEnd + 0.5) {
            seekTo(startTime)
        } else {
            dropBefore(startTime - 0.05)
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
        bufferSize = 0
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
        if (bufferSize == 0) bufferStartTime = startTime
        if (bufferSize + shorts.size > buffer.size) {
            buffer = buffer.copyOf(max(buffer.size * 2, bufferSize + shorts.size))
        }
        System.arraycopy(shorts, 0, buffer, bufferSize, shorts.size)
        bufferSize += shorts.size
        bufferFrames = bufferSize / sourceChannels
    }

    /** Drops consumed head samples. Only compacts once ≥1 s is stale, so the copy cost is amortized. */
    private fun dropBefore(time: Double) {
        val dropFrames = ((time - bufferStartTime) * sourceSampleRate).toInt().coerceIn(0, bufferFrames)
        if (dropFrames < sourceSampleRate) return
        val dropSamples = dropFrames * sourceChannels
        System.arraycopy(buffer, dropSamples, buffer, 0, bufferSize - dropSamples)
        bufferSize -= dropSamples
        bufferFrames = bufferSize / sourceChannels
        bufferStartTime += dropFrames.toDouble() / sourceSampleRate
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
