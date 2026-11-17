package dev.dogsc.app.android.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.muxer.FragmentedMp4Muxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Records into a fragmented MP4 while the capture is running, so a process kill or crash
 * leaves every already-written fragment readable. A normal stop remuxes the fragments into
 * a regular MP4, which keeps editing, probing and export on the well-tested path.
 */
internal class FragmentedRecordingWriter(
    private val partFile: File,
    fragmentDurationMs: Long = 2_000,
) {
    private val stream = FileOutputStream(partFile)
    private val muxer = try {
        FragmentedMp4Muxer.Builder(stream)
            .setFragmentDurationMs(fragmentDurationMs)
            .build()
    } catch (error: Throwable) {
        runCatching { stream.close() }
        throw error
    }
    private var trackIndex = -1

    val started: Boolean get() = trackIndex >= 0

    fun addTrack(format: MediaFormat) {
        trackIndex = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(format))
    }

    fun writeSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (trackIndex < 0 || info.size <= 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val flags = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
            androidx.media3.common.C.BUFFER_FLAG_KEY_FRAME
        } else {
            0
        }
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        muxer.writeSampleData(
            trackIndex,
            buffer,
            androidx.media3.muxer.BufferInfo(info.presentationTimeUs, info.size, flags),
        )
    }

    /** Closes the fragmented file (flushing its last fragment) without remuxing. */
    fun closeFragments() {
        runCatching { muxer.close() }.onFailure { Log.w("DogSCRecovery", "close fragments failed", it) }
        runCatching { stream.close() }
    }
}

internal object FragmentedRecordingRecovery {
    private const val PartSuffix = ".part"

    fun partFileFor(target: File): File = File(target.parentFile, target.name + PartSuffix)

    /**
     * Remuxes [partFile] into a regular MP4 at [target] and deletes the part on success.
     * Returns true when [target] now holds a readable file.
     */
    fun finish(partFile: File, target: File): Boolean {
        if (!partFile.exists() || partFile.length() <= 0) return false
        if (!remux(partFile, target)) {
            Log.w("DogSCRecovery", "remux failed for ${partFile.name}")
            return false
        }
        partFile.delete()
        return true
    }

    /** Best-effort recovery for a recording interrupted by a crash or process death. */
    fun recover(target: File): Boolean {
        val part = partFileFor(target)
        if (!part.exists() || part.length() <= 0) return false
        if (target.exists() && target.length() > 0) {
            part.delete()
            return true
        }
        Log.d("DogSCRecovery", "recovering ${target.name} from ${part.length()} bytes")
        truncateToLastCompleteBox(part)
        return finish(part, target)
    }

    /** A kill mid-fragment leaves a half-written box; drop it so the rest stays parseable. */
    private fun truncateToLastCompleteBox(file: File) {
        runCatching {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                val length = raf.length()
                var position = 0L
                var lastComplete = 0L
                while (position + 8 <= length) {
                    raf.seek(position)
                    val size32 = raf.readInt()
                    raf.readInt()
                    val size = when (size32) {
                        0 -> length - position
                        1 -> {
                            if (position + 16 > length) break
                            raf.readLong()
                        }
                        else -> size32.toLong() and 0xFFFF_FFFFL
                    }
                    if (size < 8 || position + size > length) break
                    position += size
                    lastComplete = position
                }
                if (lastComplete in 1 until length) {
                    Log.w("DogSCRecovery", "truncating ${file.name} $length → $lastComplete")
                    raf.setLength(lastComplete)
                }
            }
        }
    }

    private fun remux(source: File, target: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val tracks = mutableListOf<Int>()
            for (index in 0 until extractor.trackCount) {
                extractor.selectTrack(index)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                if (extractor.sampleTime >= 0) tracks += index
                extractor.unselectTrack(index)
            }
            if (tracks.isEmpty()) return false
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTracks = HashMap<Int, Int>()
            var maxSize = 1 shl 16
            for (index in tracks) {
                val format = extractor.getTrackFormat(index)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxSize = maxOf(maxSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                outputTracks[index] = muxer.addTrack(format)
                extractor.selectTrack(index)
            }
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(maxSize.coerceAtMost(8 shl 20))
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val track = outputTracks[extractor.sampleTrackIndex] ?: continue
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, extractor.sampleTime, flags)
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            return target.length() > 0
        } catch (error: Throwable) {
            Log.w("DogSCRecovery", "remux ${source.name} failed", error)
            target.delete()
            return false
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
