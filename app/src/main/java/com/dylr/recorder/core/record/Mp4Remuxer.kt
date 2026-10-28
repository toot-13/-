package com.dylr.recorder.core.record

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 无损重封装：只搬 sample，不转码。
 * 阻塞函数，调用方必须放在 IO 线程。
 */
object Mp4Remuxer {

    private const val MIN_BUFFER_SIZE = 1 shl 20
    private const val FRAME_GAP_US = 33_000L

    fun remux(srcPath: String, dstPath: String): Boolean {
        val src = File(srcPath)
        if (!src.isFile || src.length() <= 0L) return false
        val dst = File(dstPath)
        if (dst.absolutePath == src.absolutePath) return false
        dst.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) return false
        }
        if (dst.exists() && !dst.delete()) return false

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var success = false
        try {
            extractor.setDataSource(srcPath)
            val trackCount = extractor.trackCount
            if (trackCount <= 0) return false

            val trackMap = IntArray(trackCount) { -1 }
            val lastPts = LongArray(trackCount) { -1L }
            var outTracks = 0

            muxer = MediaMuxer(dstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = if (format.containsKey(MediaFormat.KEY_MIME)) format.getString(MediaFormat.KEY_MIME) else null
                if (mime == null) continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                extractor.selectTrack(i)
                trackMap[i] = muxer.addTrack(format)
                outTracks++
            }
            if (outTracks == 0) return false

            muxer.start()
            started = true

            val buffer = ByteBuffer.allocateDirect(bufferSize(extractor))
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                if (track >= trackCount) {
                    extractor.advance()
                    continue
                }
                val dstTrack = trackMap[track]
                if (dstTrack < 0) {
                    extractor.advance()
                    continue
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                var pts = extractor.sampleTime
                if (pts < 0L) pts = 0L
                val previous = lastPts[track]
                // 多段拼接后时间戳可能回退，强制单调递增，避免 muxer 丢帧
                if (previous >= 0L && pts <= previous) pts = previous + FRAME_GAP_US
                lastPts[track] = pts

                val flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, pts, flags)
                muxer.writeSampleData(dstTrack, buffer, info)
                extractor.advance()
            }

            muxer.stop()
            started = false
            success = dst.isFile && dst.length() > 0L
        } catch (ignored: Throwable) {
            success = false
        } finally {
            if (started) {
                try {
                    muxer?.stop()
                } catch (ignored: Throwable) {
                }
            }
            try {
                muxer?.release()
            } catch (ignored: Throwable) {
            }
            try {
                extractor.release()
            } catch (ignored: Throwable) {
            }
            if (!success) {
                // 失败保留源文件，只清理半成品
                dst.delete()
            }
        }
        return success
    }

    private fun bufferSize(extractor: MediaExtractor): Int {
        var size = MIN_BUFFER_SIZE
        for (i in 0 until extractor.trackCount) {
            try {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    val value = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    if (value > size) size = value
                }
            } catch (ignored: Throwable) {
            }
        }
        return size
    }
}
