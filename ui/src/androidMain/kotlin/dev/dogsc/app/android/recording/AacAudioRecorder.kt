package dev.dogsc.app.android.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

enum class AudioSourceKind { MICROPHONE, SYSTEM }

/**
 * Continuous AAC recorder. Pausing the product clock never pauses the source;
 * the final timeline cuts paused ranges. `muted` writes silence with the same
 * frame count so toggling the microphone keeps audio and video aligned.
 */
class AacAudioRecorder(
    private val context: Context,
    private val kind: AudioSourceKind,
    private val projection: MediaProjection?,
    private val outputFile: File,
    private val sampleRate: Int = 48_000,
    private val channels: Int = 1,
) {
    private val codec: MediaCodec
    private val writer: FragmentedRecordingWriter
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var muxerStarted = false
    @Volatile var muted = false
    @Volatile var lastPresentationTimeUs: Long = 0
        private set
    @Volatile var startedAtElapsedMs: Long = 0L
        private set
    private val totalSamples = java.util.concurrent.atomic.AtomicLong(0)
    private val scratch = ByteArray(8192 * 2)
    @Volatile private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec = created
        try {
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            writer = FragmentedRecordingWriter(FragmentedRecordingRecovery.partFileFor(outputFile))
        } catch (error: Throwable) {
            runCatching { created.stop() }
            runCatching { created.release() }
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val bufferSize = max(minBuffer * 2, sampleRate / 2 * channels * 2)
        val record = if (kind == AudioSourceKind.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projection != null) {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate,
                if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        audioRecord = record
        running = true
        record.startRecording()
        startedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        thread = Thread({ pump(bufferSize) }, "DogSC-Audio-${kind.name}").apply { start() }
        return true
    }

    fun stop() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        running = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        thread?.join(2000)
        thread = null
        // Finalize must complete even when the codec stalls or errors: the recorded
        // .part fragments are recoverable, but only if closeFragments/finish run.
        try {
            signalEndOfStream()
            drain(true)
        } catch (error: Throwable) {
            Log.w("DogSCAudio", "Audio finalize failed; keeping recoverable fragments", error)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { writer.closeFragments() }
            runCatching {
                FragmentedRecordingRecovery.finish(FragmentedRecordingRecovery.partFileFor(outputFile), outputFile)
            }
        }
    }

    /**
     * Queues EOS with retries. A single dequeue timeout used to skip EOS entirely,
     * leaving drain(true) spinning forever on a codec that never sees end-of-stream.
     */
    private fun signalEndOfStream() {
        repeat(50) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, ptsForSamples(totalSamples.get()), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            drain(false)
        }
        Log.w("DogSCAudio", "Could not queue EOS after retries; drain will bail out on idle")
    }

    private fun pump(bufferSize: Int) {
        val buffer = ShortArray(bufferSize / 2)
        val silent = ShortArray(buffer.size)
        while (running) {
            val record = audioRecord ?: break
            val read = runCatching { record.read(buffer, 0, buffer.size) }.getOrDefault(-1)
            if (read > 0) {
                val source = if (muted) silent else buffer
                feedEncoder(source, read)
            } else if (read == 0) {
                Thread.sleep(2)
            } else {
                // DEAD_OBJECT / BAD_VALUE must not turn this loop into a CPU spin.
                Log.w("DogSCAudio", "AudioRecord read failed: $read")
                break
            }
        }
    }

    private fun feedEncoder(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex < 0) {
                drain(false)
                continue
            }
            val input = codec.getInputBuffer(inputIndex)
            if (input == null) {
                // Return the dequeued index to the codec; dropping it leaks the buffer
                // and eventually starves the encoder.
                codec.queueInputBuffer(inputIndex, 0, 0, ptsForSamples(totalSamples.get()), 0)
                return
            }
            input.clear()
            val maxSamples = min(min(input.remaining() / 2, 4096), count - offset)
            if (maxSamples <= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, ptsForSamples(totalSamples.get()), 0)
                drain(false)
                continue
            }
            val byteCount = maxSamples * 2
            for (i in 0 until maxSamples) {
                val v = samples[offset + i].toInt()
                scratch[i * 2] = (v and 0xFF).toByte()
                scratch[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            input.put(scratch, 0, byteCount)
            val pts = ptsForSamples(totalSamples.get())
            codec.queueInputBuffer(inputIndex, 0, byteCount, pts, 0)
            totalSamples.addAndGet((maxSamples / channels).toLong())
            offset += maxSamples
            drain(false)
        }
    }

    private fun ptsForSamples(samples: Long): Long = samples * 1_000_000L / sampleRate

    private fun drain(endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // Bail out instead of spinning forever when EOS never arrived
                    // (stalled codec); the .part fragments stay recoverable.
                    if (++idle > 500) {
                        Log.w("DogSCAudio", "Audio drain idle timeout; stopping finalize")
                        return
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        writer.addTrack(codec.outputFormat)
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)
                    if (output != null && info.size > 0 && muxerStarted) {
                        writer.writeSample(output, info)
                        lastPresentationTimeUs = max(lastPresentationTimeUs, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
