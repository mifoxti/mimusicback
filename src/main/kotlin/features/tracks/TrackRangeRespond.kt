package com.example.features.tracks

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

private data class SatisfiableRange(val start: Long, val endInclusive: Long)

/**
 * Один интервал `bytes=…` (без multipart). Возвращает null, если диапазон не пересекается с файлом.
 */
private fun satisfiableByteRange(spec: String, fileLength: Long): SatisfiableRange? {
    if (fileLength <= 0L) return null
    val last = fileLength - 1L
    val trimmed = spec.trim()
    if (trimmed.startsWith('-')) {
        val suffixLen = trimmed.removePrefix("-").toLongOrNull() ?: return null
        if (suffixLen <= 0L) return null
        val start = (fileLength - suffixLen).coerceAtLeast(0L)
        return SatisfiableRange(start, last)
    }
    val dash = trimmed.indexOf('-')
    if (dash < 0) return null
    val startPart = trimmed.substring(0, dash).trim()
    val endPart = trimmed.substring(dash + 1).trim()
    val start = if (startPart.isEmpty()) 0L else startPart.toLongOrNull() ?: return null
    if (start >= fileLength) return null
    val endInclusive = if (endPart.isEmpty()) {
        last
    } else {
        endPart.toLongOrNull() ?: return null
    }
    if (endInclusive < start) return null
    val endClamped = min(endInclusive, last)
    return SatisfiableRange(start, endClamped)
}

/**
 * Отдаёт MP3 с диска: без `Range` — как раньше [LocalFileContent]; с одним `bytes=…` — **206** и **Content-Range**.
 * Multipart-range не поддерживаем (**400**).
 */
suspend fun ApplicationCall.respondTrackAudioWithOptionalRange(file: File) {
    if (!file.isFile) {
        respond(HttpStatusCode.NotFound)
        return
    }
    val len = file.length()
    if (len == 0L) {
        respond(HttpStatusCode.NotFound)
        return
    }

    response.header(HttpHeaders.AcceptRanges, "bytes")

    val rangeHeader = request.headers[HttpHeaders.Range]
    if (rangeHeader.isNullOrBlank()) {
        respond(LocalFileContent(file, ContentType.Audio.MPEG))
        return
    }
    if (!rangeHeader.startsWith("bytes=")) {
        respond(LocalFileContent(file, ContentType.Audio.MPEG))
        return
    }
    if (',' in rangeHeader) {
        respond(HttpStatusCode.BadRequest)
        return
    }

    val spec = rangeHeader.removePrefix("bytes=").trim()
    val range = satisfiableByteRange(spec, len)
    if (range == null) {
        response.header(HttpHeaders.ContentRange, "bytes */$len")
        respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        return
    }

    val sliceLen = (range.endInclusive - range.start + 1L).toInt()
    if (sliceLen <= 0) {
        response.header(HttpHeaders.ContentRange, "bytes */$len")
        respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        return
    }

    val maxSlice = 64 * 1024 * 1024
    if (sliceLen > maxSlice) {
        respond(HttpStatusCode.PayloadTooLarge, "Requested range too large")
        return
    }

    val bytes = RandomAccessFile(file, "r").use { raf ->
        raf.seek(range.start)
        ByteArray(sliceLen).also { buf -> raf.readFully(buf) }
    }

    response.header(
        HttpHeaders.ContentRange,
        "bytes ${range.start}-${range.endInclusive}/$len",
    )
    respondBytes(
        bytes = bytes,
        contentType = ContentType.Audio.MPEG,
        status = HttpStatusCode.PartialContent,
    )
}
