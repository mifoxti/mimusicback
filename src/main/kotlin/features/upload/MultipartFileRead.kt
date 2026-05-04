package com.example.features.upload

import com.example.utils.readAtMostBytesStrict
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*

internal data class MultipartSingleFileRead(
    val bytes: ByteArray?,
    val contentType: ContentType?,
    val tooLargeMessage: String?,
)

/** Одна file-часть с именем `file` (или первая file-часть), не больше [maxBytes] байт. */
internal suspend fun ApplicationCall.receiveMultipartSingleFile(maxBytes: Int): MultipartSingleFileRead {
    val multipart = receiveMultipart()
    var fileBytes: ByteArray? = null
    var contentType: ContentType? = null
    var uploadTooLargeMessage: String? = null
    multipart.forEachPart { part ->
        if (part is PartData.FileItem && (part.name == "file" || fileBytes == null)) {
            contentType = part.contentType
            fileBytes = try {
                part.streamProvider().use { it.readAtMostBytesStrict(maxBytes) }
            } catch (e: IllegalArgumentException) {
                part.dispose()
                uploadTooLargeMessage = e.message ?: "File too large"
                return@forEachPart
            }
        }
        part.dispose()
    }
    return MultipartSingleFileRead(fileBytes, contentType, uploadTooLargeMessage)
}
