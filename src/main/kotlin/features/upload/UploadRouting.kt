package com.example.features.upload

import com.example.config.fileStorageRoot
import com.example.config.musicStorageDirectory
import com.example.config.uploadMaxBytes
import com.example.database.Albums
import com.example.database.Playlists
import com.example.database.Tracks
import com.example.database.Users
import com.example.services.AudioTranscodeService
import com.example.services.TrackGenreService
import com.example.utils.currentUserId
import com.example.utils.extractEmbeddedCoverPngBytes
import com.example.utils.looksLikeSupportedAudioUpload
import com.example.utils.persistTrackCoverPng
import com.example.utils.readAtMostBytesStrict
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private fun parseGenreSlugs(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val t = raw.trim()
    return try {
        Json.decodeFromString(ListSerializer(String.serializer()), t)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) {
        t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

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
            var audioBytes: ByteArray? = null
            var audioContentType: ContentType? = null
            var coverBytes: ByteArray? = null
            var coverContentType: ContentType? = null
            var titleOverride: String? = null
            var artistOverride: String? = null
            var genreSlugsRaw: String? = null
            var genreNormalizeWeights = false
            var uploadTooLargeMessage: String? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        when (part.name) {
                            "file" -> {
                                val stream = part.streamProvider()
                                audioBytes = try {
                                    stream.use { it.readAtMostBytesStrict(maxBytes) }
                                } catch (e: IllegalArgumentException) {
                                    part.dispose()
                                    uploadTooLargeMessage = e.message ?: "File too large"
                                    return@forEachPart
                                }
                                audioContentType = part.contentType
                            }
                            "cover" -> {
                                val stream = part.streamProvider()
                                coverBytes = try {
                                    stream.use { it.readAtMostBytesStrict(maxBytes) }
                                } catch (e: IllegalArgumentException) {
                                    part.dispose()
                                    uploadTooLargeMessage = e.message ?: "Cover too large"
                                    return@forEachPart
                                }
                                coverContentType = part.contentType
                            }
                        }
                    }
                    is PartData.FormItem -> {
                        when (part.name) {
                            "title" -> titleOverride = part.value.takeIf { it.isNotBlank() }
                            "artist" -> artistOverride = part.value.takeIf { it.isNotBlank() }
                            "genreSlugs" -> genreSlugsRaw = part.value
                            "genreNormalizeWeights" ->
                                genreNormalizeWeights = part.value.equals("true", ignoreCase = true)
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

            val bytes = audioBytes ?: run {
                call.respond(HttpStatusCode.BadRequest, "Expected multipart part \"file\"")
                return@post
            }

            if (!AudioTranscodeService.ffmpegAvailable()) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    "Не найден ffmpeg/ffprobe (установите в PATH или задайте FFMPEG_BIN_DIR / FFMPEG_PATH в .env) — конвертация в AAC недоступна",
                )
                return@post
            }

            val mime = audioContentType ?: ContentType.Application.OctetStream
            val mimeOk = mime.match(ContentType.Audio.MPEG) ||
                mime.match(ContentType.parse("audio/mp3")) ||
                mime.match(ContentType.parse("audio/wav")) ||
                mime.match(ContentType.parse("audio/x-wav")) ||
                mime.match(ContentType.parse("audio/mp4")) ||
                mime.match(ContentType.parse("audio/m4a")) ||
                mime.match(ContentType.parse("audio/x-m4a")) ||
                mime == ContentType.Application.OctetStream
            if (!mimeOk) {
                call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    "Ожидается audio/mpeg, audio/wav, audio/mp4|m4a или application/octet-stream",
                )
                return@post
            }
            if (!looksLikeSupportedAudioUpload(bytes)) {
                call.respond(HttpStatusCode.BadRequest, "Файл не похож на MP3/WAV/M4A")
                return@post
            }

            var customCoverPng: ByteArray? = null
            val rawCover = coverBytes
            if (rawCover != null && rawCover.isNotEmpty()) {
                if (!coverContentType.isAllowedImage()) {
                    call.respond(
                        HttpStatusCode.UnsupportedMediaType,
                        "Ожидается изображение (jpeg/png/gif/webp) в части \"cover\"",
                    )
                    return@post
                }
                customCoverPng = try {
                    SafeImage.rasterToLosslessPngBytes(rawCover, maxBytes)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "Некорректное изображение обложки")
                    return@post
                }
            }

            val musicRoot = musicStorageDirectory()
            val dir = File(musicRoot, "uploads/$uid").apply { mkdirs() }
            val work = File(dir, "_work").apply { mkdirs() }
            val inFile = File(work, "${UUID.randomUUID()}_in")
            inFile.writeBytes(bytes)
            val outName = "${UUID.randomUUID()}.m4a"
            val dest = File(dir, outName)

            val probeIn = AudioTranscodeService.probe(inFile)
            val transcodeErr = AudioTranscodeService.transcodeToM4aAac(inFile, dest)
            if (transcodeErr != null) {
                inFile.delete()
                dest.delete()
                call.respond(HttpStatusCode.BadRequest, "Конвертация в AAC: $transcodeErr")
                return@post
            }
            val probeOut = AudioTranscodeService.probe(dest)
            val embeddedCoverPng = if (customCoverPng == null) {
                extractEmbeddedCoverPngBytes(inFile, maxBytes)
            } else {
                null
            }
            inFile.delete()

            val title = titleOverride?.trim()?.takeIf { it.isNotEmpty() }
                ?: probeIn.title ?: probeOut.title
                ?: dest.nameWithoutExtension
            val artistFromUser = artistOverride?.trim()?.takeIf { it.isNotEmpty() }
            val artistFromTag = probeIn.artist ?: probeOut.artist
            val artist = artistFromUser ?: artistFromTag
            val artistsList = if (artist.isNullOrBlank()) emptyList() else listOf(artist)
            val durationSec = probeOut.durationSec ?: probeIn.durationSec ?: 0.0
            val durationMs = (durationSec * 1000.0).toLong().coerceAtLeast(0L).toInt()
            val relativeKey = "uploads/$uid/$outName"
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

            TrackGenreService.replaceTrackGenres(
                trackId = trackId,
                slugs = parseGenreSlugs(genreSlugsRaw),
                source = "uploader",
                normalizeWeights = genreNormalizeWeights,
            )

            var coverKey: String? = null
            var customApplied = false
            var embeddedApplied = false

            if (customCoverPng != null) {
                coverKey = persistTrackCoverPng(trackId, customCoverPng)
                customApplied = true
                newSuspendedTransaction {
                    Tracks.update({ Tracks.id eq trackId }) {
                        it[Tracks.coverStorageKey] = coverKey
                        it[Tracks.updatedAt] = OffsetDateTime.now()
                    }
                }
            } else if (embeddedCoverPng != null) {
                coverKey = persistTrackCoverPng(trackId, embeddedCoverPng)
                embeddedApplied = true
                newSuspendedTransaction {
                    Tracks.update({ Tracks.id eq trackId }) {
                        it[Tracks.coverStorageKey] = coverKey
                        it[Tracks.updatedAt] = OffsetDateTime.now()
                    }
                }
            }

            call.respond(
                UploadTrackResponseRemote(
                    trackId = trackId,
                    audioStorageKey = relativeKey,
                    title = title,
                    artist = artist,
                    durationSec = durationMs / 1000,
                    coverStorageKey = coverKey,
                    customCoverApplied = customApplied,
                    embeddedCoverApplied = embeddedApplied,
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
