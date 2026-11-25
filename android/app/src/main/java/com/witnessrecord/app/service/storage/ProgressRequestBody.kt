package com.witnessrecord.app.service.storage

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File

/**
 * Streams a file and reports how much of it has gone out.
 *
 * The queue sheet's whole purpose is answering "is anything actually moving",
 * which a count of waiting chunks cannot. On a failing connection - the case
 * this app exists for - that is the difference between a queue that is slow
 * and one that is stuck, and they look identical without this.
 *
 * Reports are throttled rather than emitted per write: OkHttp hands out
 * 8 KB at a time, so an unthrottled callback would fire thousands of times a
 * second and compete with the transfer it is measuring.
 */
class ProgressRequestBody(
    private val file: File,
    private val contentType: MediaType,
    /** Bytes already on the server from a previous attempt, skipped on resume. */
    private val offset: Long = 0,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType = contentType

    override fun contentLength(): Long = file.length() - offset

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var sent = 0L
        var lastReportedAt = 0L

        file.inputStream().use { stream ->
            if (offset > 0) stream.skip(offset)
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                sink.write(buffer, 0, read)
                sent += read

                val now = System.currentTimeMillis()
                if (now - lastReportedAt >= REPORT_INTERVAL_MILLIS || sent >= total) {
                    lastReportedAt = now
                    onProgress(sent, total)
                }
            }
        }
    }

    private companion object {
        /** Four times a second is smooth to watch and costs nothing. */
        const val REPORT_INTERVAL_MILLIS = 250L
    }
}
