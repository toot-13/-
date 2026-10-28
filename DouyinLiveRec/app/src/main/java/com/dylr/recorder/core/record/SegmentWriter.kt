package com.dylr.recorder.core.record

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单个切片文件的写入器。
 * 命名：<getExternalFilesDir("rec")>/<roomId>_<yyyyMMdd_HHmmss>_<index>.<ext>
 * write() 返回 false 表示已达 maxBytes 上限，调用方应切片。
 */
class SegmentWriter(
    context: Context,
    val roomId: String = "unknown",
    val index: Int = 0,
    val extension: String = "flv",
    private val maxBytes: Long = 0L
) {

    private val lock = Any()
    private var closed = false
    private var written = 0L

    val dir: File = (context.getExternalFilesDir("rec") ?: File(context.filesDir, "rec")).also { target ->
        if (!target.exists()) target.mkdirs()
    }

    val file: File = File(dir, buildFileName())

    private val stream: FileOutputStream = FileOutputStream(file)

    val bytesWritten: Long
        get() = synchronized(lock) { written }

    val path: String
        get() = file.absolutePath

    /**
     * @return false 表示本片已达 maxBytes（或写失败），上层应关闭本片并开启下一片。
     */
    fun write(bytes: ByteArray, offset: Int, len: Int): Boolean {
        if (len <= 0) return true
        return synchronized(lock) {
            if (closed) {
                false
            } else {
                var keepWriting = true
                try {
                    stream.write(bytes, offset, len)
                    written += len
                    keepWriting = maxBytes <= 0L || written < maxBytes
                } catch (ignored: IOException) {
                    closeLocked()
                    keepWriting = false
                }
                keepWriting
            }
        }
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    fun hasData(): Boolean = file.isFile && file.length() > 0L

    private fun closeLocked() {
        if (closed) return
        closed = true
        try {
            stream.flush()
        } catch (ignored: IOException) {
        }
        try {
            stream.close()
        } catch (ignored: IOException) {
        }
    }

    private fun buildFileName(): String = roomId + "_" + timeStamp() + "_" + index + "." + extension

    private fun timeStamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(System.currentTimeMillis()))
}
