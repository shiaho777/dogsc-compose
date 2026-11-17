package dev.dogsc.app.android

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.dogsc.app.platform.DogScPlatform
import dev.dogsc.core.model.MediaTimelinePlacement
import dev.dogsc.core.model.ProjectMediaReference
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.TimelineMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AndroidWaveformLoader(private val repository: AndroidProjectRepository) {
    suspend fun load(
        project: RecorderProject,
        sourceDuration: Double,
        buckets: Int = 600,
    ): FloatArray? = withContext(Dispatchers.IO) {
        val dir = repository.projectDirectory(project.id)
        val timelineMap = TimelineMap(project.timeline.sourceSequence, sourceDuration)
        val screen = project.media?.screen?.let { File(dir, it.relativePath) }
        val system = project.media?.systemAudio?.let { File(dir, it.relativePath) }
        val microphone = project.media?.microphone?.let { File(dir, it.relativePath) }
        val candidates = listOf(
            Triple(system, project.media?.systemAudio, true),
            Triple(microphone, project.media?.microphone, false),
            Triple(screen, null, true),
        ).filter { it.first?.exists() == true }
        for ((file, reference, _) in candidates) {
            val result = try {
                decode(file!!, project, timelineMap, reference, sourceDuration, buckets)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (result != null) return@withContext result
        }
        null
    }

    private suspend fun decode(
        file: File,
        project: RecorderProject,
        timelineMap: TimelineMap,
        reference: ProjectMediaReference?,
        sourceDuration: Double,
        buckets: Int,
    ): FloatArray? {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            return null
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
            extractor.release()
            return null
        }
        var created: MediaCodec? = null
        val codec = try {
            MediaCodec.createDecoderByType(mime).also {
                created = it
                it.configure(format, null, null, 0)
                it.start()
            }
        } catch (error: Throwable) {
            runCatching { created?.stop() }
            runCatching { created?.release() }
            runCatching { extractor.release() }
            throw error
        }
        val duration = (format.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)) / 1_000_000.0
        val placement = reference?.let {
            MediaTimelinePlacement.from(
                it,
                sourceAvailableDuration = duration,
                timelineDuration = sourceDuration,
            )
        }
        val output = FloatArray(buckets)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val outputDuration = timelineMap.outputDuration.coerceAtLeast(0.01)
        try {
            while (!outputDone) {
                // Cancelling a waveform for an edited/switched project must stop the
                // MediaCodec loop too; this is not a cancellable suspension point.
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
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
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 2) {
                            val shorts = ShortArray(info.size / 2)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                            val sourceTime = info.presentationTimeUs / 1_000_000.0
                            val outputTime = when {
                                reference != null && placement != null -> placement.timelineTimeForSource(sourceTime)
                                else -> timelineMap.outputTimeForSource(sourceTime)
                            }
                            if (outputTime != null) {
                                val bucket = ((outputTime / outputDuration) * buckets).toInt().coerceIn(0, buckets - 1)
                                var peak = 0f
                                var i = 0
                                while (i < shorts.size) {
                                    peak = max(peak, abs(shorts[i] / 32768f))
                                    i += 8
                                }
                                output[bucket] = max(output[bucket], peak)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
        // Light smoothing so quiet buckets between peaks do not read as gaps.
        for (i in 1 until buckets - 1) {
            output[i] = max(output[i], (output[i - 1] + output[i + 1]) * 0.35f)
        }
        return output
    }

    private fun MediaFormat.getLongOrDefault(key: String, default: Long): Long =
        if (containsKey(key)) getLong(key) else default
}
