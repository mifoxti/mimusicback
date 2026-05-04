package com.example.features.upload

import com.example.config.fileStorageRoot
import com.example.config.musicStorageDirectory
import com.example.config.uploadMaxBytes
import com.example.database.Albums
import com.example.database.Playlists
import com.example.database.Tracks
import com.example.database.Users
import com.example.utils.currentUserId
import com.example.utils.looksLikeMp3Prefix
import com.example.utils.readAtMostBytesStrict
import com.mpatric.mp3agic.Mp3File
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.io.File
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import com.example.utils.SafeImage

private val allowedImageContentTypes = setOf(
    ContentType.Image.JPEG,
    ContentType.Image.PNG,
    ContentType.Image.GIF,
    ContentType.parse("image/webp"),
)

private fun ContentType?.isAllowedImage(): Boolean {
    if (this == null) return false
    return allowedImageContentTypes.any { it.match(this) }
}

private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(8192)
        var n: Int
        while (stream.read(buffer).also { n = it } != -1) {
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private suspend fun ApplicationCall.receivePngFromMultipartOrRespond(maxBytes: Int): ByteArray? {
    val r = receiveMultipartSingleFile(maxBytes)
    if (r.tooLargeMessage != null) {
        respond(HttpStatusCode.PayloadTooLarge, r.tooLargeMessage)
        return null
    }
    val bytes = r.bytes ?: run {
        respond(HttpStatusCode.BadRequest, "Expected multipart part \"file\"")
        return null
    }
    if (!r.contentType.isAllowedImage()) {
        respond(
            HttpStatusCode.UnsupportedMediaType,
            "Ожидается изображение (jpeg/png/gif/webp)",
        )
        return null
    }
    return try {
        SafeImage.rasterToLosslessPngBytes(bytes, maxBytes)
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, e.message ?: "Некорректное изображение")
        null
    }
}

fun Application.configureUploadRouting() {
    val maxBytes = uploadMaxBytes()

    routing {
        post("/upload/track") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val multipart = call.receiveMultipart()
            var fileBytes: ByteArray? = null
            var declaredContentType: ContentType? = null
            var titleOverride: String? = null
            var uploadTooLargeMessage: String? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        if (part.name == "file" || fileBytes == null) {
                            val ct = part.contentType
                            val stream = part.streamProvider()
                            fileBytes = try {
                                stream.use { it.readAtMostBytesStrict(maxBytes) }
                            } catch (e: IllegalArgumentException) {
                                part.dispose()
                                uploadTooLargeMessage = e.message ?: "File too large"
                                return@forEachPart
                            }
                            declaredContentType = ct
                        }
                    }
                    is PartData.FormItem -> {
                        if (part.name == "title") {
                            titleOverride = part.value.takeIf { it.isNotBlank() }
                        }
                    }
                    else -> Unit
                }
                part.dispose()
            }

            if (uploadTooLargeMessage != null) {
                call.respond(HttpStatusCode.PayloadTooLarge, uploadTooLargeMessage)
                return@post
            }

            val bytes = fileBytes ?: run {
                call.respond(HttpStatusCode.BadRequest, "Expected multipart part \"file\"")
                return@post
            }

            val mime = declaredContentType ?: ContentType.Application.OctetStream
            if (!mime.match(ContentType.Audio.MPEG) &&
                !mime.match(ContentType.parse("audio/mp3")) &&
                mime != ContentType.Application.OctetStream
            ) {
                call.respond(HttpStatusCode.UnsupportedMediaType, "Ожидается audio/mpeg или application/octet-stream (MP3)")
                return@post
            }
            if (!looksLikeMp3Prefix(bytes)) {
                call.respond(HttpStatusCode.BadRequest, "Файл не похож на MP3")
                return@post
            }

            val musicRoot = musicStorageDirectory()
            val dir = File(musicRoot, "uploads/$uid").apply { mkdirs() }
            val fileName = "${UUID.randomUUID()}.mp3"
            val dest = File(dir, fileName)
            dest.writeBytes(bytes)

            val mp3 = try {
                Mp3File(dest)
            } catch (e: Exception) {
                dest.delete()
                call.respond(HttpStatusCode.BadRequest, "Не удалось разобрать MP3: ${e.message}")
                return@post
            }

            val title = titleOverride?.trim()?.takeIf { it.isNotEmpty() }
                ?: mp3.id3v2Tag?.title?.takeIf { it.isNotBlank() }
                ?: dest.nameWithoutExtension
            val artist = mp3.id3v2Tag?.artist
            val artistsList = if (artist.isNullOrBlank()) emptyList() else listOf(artist)
            val durationMs = mp3.lengthInSeconds.toInt().coerceAtLeast(0) * 1000
            val relativeKey = "uploads/$uid/$fileName"
            val hash = sha256File(dest)

            val trackId = newSuspendedTransaction {
                Tracks.insert {
                    it[Tracks.uploaderUserId] = uid
                    it[Tracks.title] = title
                    it[Tracks.artists] = artistsList
                    it[Tracks.audioStorageKey] = relativeKey
                    it[Tracks.coverStorageKey] = null
                    it[Tracks.hash] = hash
                    it[Tracks.durationMs] = durationMs
                    it[Tracks.createdAt] = OffsetDateTime.now()
                    it[Tracks.updatedAt] = OffsetDateTime.now()
                } get Tracks.id
            }

            call.respond(
                UploadTrackResponseRemote(
                    trackId = trackId,
                    audioStorageKey = relativeKey,
                    title = title,
                    durationSec = durationMs / 1000,
                ),
            )
        }

        post("/upload/avatar") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val pngBytes = call.receivePngFromMultipartOrRespond(maxBytes) ?: return@post

            val storageRoot = fileStorageRoot()
            val relativeKey = "avatars/$uid.png"
            val oldKey = newSuspendedTransaction {
                Users.selectAll().where { Users.id eq uid }.singleOrNull()?.let { it[Users.avatarStorageKey] }
            }
            val dest = File(storageRoot, relativeKey)
            dest.parentFile?.mkdirs()
            dest.writeBytes(pngBytes)
            if (!oldKey.isNullOrBlank() && oldKey != relativeKey) {
                File(storageRoot, oldKey).takeIf { it.isFile }?.delete()
            }

            newSuspendedTransaction {
                Users.update({ Users.id eq uid }) {
                    it[Users.avatarStorageKey] = relativeKey
                    it[Users.updatedAt] = OffsetDateTime.now()
                }
            }

            call.respond(UploadAvatarResponseRemote(avatarStorageKey = relativeKey))
        }

        post("/upload/tracks/{id}/cover") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val trackId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid track id")
                return@post
            }

            val trackOwner = newSuspendedTransaction {
                Tracks.selectAll().where { Tracks.id eq trackId }.singleOrNull()
                    ?.let { it[Tracks.uploaderUserId] }
            }
            when {
                trackOwner == null -> {
                    call.respond(HttpStatusCode.NotFound, "Трек не найден")
                    return@post
                }
                trackOwner != uid -> {
                    call.respond(HttpStatusCode.Forbidden, "Можно менять обложку только своего трека")
                    return@post
                }
            }

            val pngBytes = call.receivePngFromMultipartOrRespond(maxBytes) ?: return@post

            val storageRoot = fileStorageRoot()
            val relativeKey = "covers/tracks/$trackId.png"
            val dest = File(storageRoot, relativeKey)
            dest.parentFile?.mkdirs()
            dest.writeBytes(pngBytes)

            newSuspendedTransaction {
                Tracks.update({ Tracks.id eq trackId }) {
                    it[Tracks.coverStorageKey] = relativeKey
                    it[Tracks.updatedAt] = OffsetDateTime.now()
                }
            }

            call.respond(UploadedCoverResponseRemote(coverStorageKey = relativeKey))
        }

        post("/upload/playlists/{id}/cover") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val playlistId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid playlist id")
                return@post
            }
            val playlistOwner = newSuspendedTransaction {
                Playlists.selectAll().where { Playlists.id eq playlistId }.singleOrNull()
                    ?.let { it[Playlists.userId] }
            }
            when {
                playlistOwner == null -> {
                    call.respond(HttpStatusCode.NotFound, "Плейлист не найден")
                    return@post
                }
                playlistOwner != uid -> {
                    call.respond(HttpStatusCode.Forbidden, "Можно менять обложку только своего плейлиста")
                    return@post
                }
            }

            val pngBytes = call.receivePngFromMultipartOrRespond(maxBytes) ?: return@post
            val storageRoot = fileStorageRoot()
            val relativeKey = "covers/playlists/$playlistId.png"
            val dest = File(storageRoot, relativeKey)
            dest.parentFile?.mkdirs()
            dest.writeBytes(pngBytes)

            newSuspendedTransaction {
                Playlists.update({ Playlists.id eq playlistId }) {
                    it[Playlists.coverStorageKey] = relativeKey
                    it[Playlists.updatedAt] = OffsetDateTime.now()
                }
            }

            call.respond(UploadedCoverResponseRemote(coverStorageKey = relativeKey))
        }

        post("/upload/albums/{id}/cover") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val albumId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid album id")
                return@post
            }
            val albumOwner = newSuspendedTransaction {
                Albums.selectAll().where { Albums.id eq albumId }.singleOrNull()
                    ?.let { it[Albums.userId] }
            }
            when {
                albumOwner == null -> {
                    call.respond(HttpStatusCode.NotFound, "Альбом не найден")
                    return@post
                }
                albumOwner != uid -> {
                    call.respond(HttpStatusCode.Forbidden, "Можно менять обложку только своего альбома")
                    return@post
                }
            }

            val pngBytes = call.receivePngFromMultipartOrRespond(maxBytes) ?: return@post
            val storageRoot = fileStorageRoot()
            val relativeKey = "covers/albums/$albumId.png"
            val dest = File(storageRoot, relativeKey)
            dest.parentFile?.mkdirs()
            dest.writeBytes(pngBytes)

            newSuspendedTransaction {
                Albums.update({ Albums.id eq albumId }) {
                    it[Albums.coverStorageKey] = relativeKey
                    it[Albums.updatedAt] = OffsetDateTime.now()
                }
            }

            call.respond(UploadedCoverResponseRemote(coverStorageKey = relativeKey))
        }
    }
}
