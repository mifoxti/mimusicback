package com.example.features.albums

import com.example.database.AlbumGenres
import com.example.database.AlbumTracks
import com.example.database.Albums
import com.example.database.Tracks
import com.example.services.TrackGenreService
import com.example.utils.currentUserId
import com.example.utils.primaryArtist
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

@Serializable
data class AlbumCreateReceive(
    val title: String,
    val genreSlugs: List<String> = emptyList(),
    val isPublic: Boolean = false,
    val normalizeGenreWeights: Boolean = false,
)

@Serializable
data class AlbumCreatedRemote(
    val id: Int,
    val title: String?,
)

@Serializable
data class AlbumUpdateReceive(
    val title: String? = null,
    val isPublic: Boolean? = null,
    val genreSlugs: List<String>? = null,
    val normalizeGenreWeights: Boolean = false,
)

@Serializable
data class AlbumTrackOrderReceive(
    val trackIds: List<Long>,
)

@Serializable
data class AlbumListItemRemote(
    val id: Int,
    val title: String?,
    val isPublic: Boolean?,
    val trackCount: Int,
)

@Serializable
data class AlbumTrackEntryRemote(
    val position: Int,
    val trackId: Int,
    val title: String?,
    val artist: String?,
)

@Serializable
data class AlbumDetailRemote(
    val id: Int,
    val title: String?,
    val isPublic: Boolean?,
    val ownerUserId: Long,
    val genreSlugs: List<String>,
    val tracks: List<AlbumTrackEntryRemote>,
)

private suspend fun existingTrackIdSet(ids: Collection<Long>): Set<Long> = newSuspendedTransaction {
    if (ids.isEmpty()) return@newSuspendedTransaction emptySet()
    Tracks.selectAll().where { Tracks.id inList ids.distinct() }
        .map { it[Tracks.id] }
        .toSet()
}

fun Application.configureAlbumRouting() {
    routing {
        post("/albums") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val body = try {
                call.receive<AlbumCreateReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                return@post
            }
            val title = body.title.trim().ifEmpty { "Untitled" }
            val albumId = newSuspendedTransaction {
                Albums.insert {
                    it[Albums.userId] = uid
                    it[Albums.title] = title
                    it[Albums.isPublic] = body.isPublic
                    it[Albums.coverStorageKey] = null
                    it[Albums.createdAt] = OffsetDateTime.now()
                    it[Albums.updatedAt] = OffsetDateTime.now()
                } get Albums.id
            }
            TrackGenreService.replaceAlbumGenres(
                albumId = albumId,
                slugs = body.genreSlugs,
                source = "owner",
                normalizeWeights = body.normalizeGenreWeights,
            )
            call.respond(AlbumCreatedRemote(id = albumId.toInt(), title = title))
        }

        get("/me/albums") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val rows = newSuspendedTransaction {
                Albums.selectAll()
                    .where { Albums.userId eq uid }
                    .orderBy(Albums.id, SortOrder.DESC)
                    .map { it }
            }
            val ids = rows.map { it[Albums.id] }
            val trackCounts = newSuspendedTransaction {
                if (ids.isEmpty()) emptyMap()
                else {
                    AlbumTracks.selectAll().where { AlbumTracks.albumId inList ids }
                        .groupBy { it[AlbumTracks.albumId] }
                        .mapValues { it.value.size }
                }
            }
            call.respond(
                rows.map {
                    AlbumListItemRemote(
                        id = it[Albums.id].toInt(),
                        title = it[Albums.title],
                        isPublic = it[Albums.isPublic],
                        trackCount = trackCounts[it[Albums.id]] ?: 0,
                    )
                },
            )
        }

        get("/albums/{id}") {
            val albumId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid album id")
                return@get
            }
            val uid = call.currentUserId()?.toLong()
            val row = newSuspendedTransaction {
                Albums.selectAll().where { Albums.id eq albumId }.singleOrNull()
            } ?: run {
                call.respond(HttpStatusCode.NotFound, "Альбом не найден")
                return@get
            }
            val owner = row[Albums.userId]
            val isPublic = row[Albums.isPublic] == true
            when {
                owner == uid -> { }
                isPublic -> { }
                uid == null -> {
                    call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                    return@get
                }
                else -> {
                    call.respond(HttpStatusCode.Forbidden, "Альбом недоступен")
                    return@get
                }
            }
            val genreSlugs = TrackGenreService.loadGenreSlugsForAlbum(albumId)
            val ordered = newSuspendedTransaction {
                AlbumTracks.selectAll()
                    .where { AlbumTracks.albumId eq albumId }
                    .orderBy(AlbumTracks.position to SortOrder.ASC)
                    .map { it[AlbumTracks.position] to it[AlbumTracks.trackId] }
            }
            val tidSet = ordered.map { it.second }.distinct()
            val byId = newSuspendedTransaction {
                if (tidSet.isEmpty()) emptyMap()
                else Tracks.selectAll().where { Tracks.id inList tidSet }
                    .associate { it[Tracks.id] to it }
            }
            val tracks = ordered.mapNotNull { (pos, tid) ->
                val tr = byId[tid] ?: return@mapNotNull null
                AlbumTrackEntryRemote(
                    position = pos,
                    trackId = tr[Tracks.id].toInt(),
                    title = tr[Tracks.title],
                    artist = tr[Tracks.artists].primaryArtist().ifBlank { null },
                )
            }
            call.respond(
                AlbumDetailRemote(
                    id = row[Albums.id].toInt(),
                    title = row[Albums.title],
                    isPublic = row[Albums.isPublic],
                    ownerUserId = owner,
                    genreSlugs = genreSlugs,
                    tracks = tracks,
                ),
            )
        }

        put("/albums/{id}") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@put
            }
            val albumId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid album id")
                return@put
            }
            val body = try {
                call.receive<AlbumUpdateReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                return@put
            }
            val updated = newSuspendedTransaction {
                val r = Albums.selectAll().where { Albums.id eq albumId }.singleOrNull()
                    ?: return@newSuspendedTransaction null
                if (r[Albums.userId] != uid) return@newSuspendedTransaction false
                val title = body.title?.trim()
                Albums.update({ Albums.id eq albumId }) {
                    if (title != null) it[Albums.title] = title.ifEmpty { "Untitled" }
                    if (body.isPublic != null) it[Albums.isPublic] = body.isPublic
                    it[Albums.updatedAt] = OffsetDateTime.now()
                }
                true
            }
            when (updated) {
                null -> call.respond(HttpStatusCode.NotFound, "Альбом не найден")
                false -> call.respond(HttpStatusCode.Forbidden, "Только владелец может менять альбом")
                else -> {
                    if (body.genreSlugs != null) {
                        TrackGenreService.replaceAlbumGenres(
                            albumId = albumId,
                            slugs = body.genreSlugs,
                            source = "owner",
                            normalizeWeights = body.normalizeGenreWeights,
                        )
                    }
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        delete("/albums/{id}") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@delete
            }
            val albumId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid album id")
                return@delete
            }
            val deleted = newSuspendedTransaction {
                val r = Albums.selectAll().where { Albums.id eq albumId }.singleOrNull()
                    ?: return@newSuspendedTransaction null
                if (r[Albums.userId] != uid) return@newSuspendedTransaction false
                AlbumGenres.deleteWhere { AlbumGenres.albumId eq albumId }
                AlbumTracks.deleteWhere { AlbumTracks.albumId eq albumId }
                Albums.deleteWhere { Albums.id eq albumId }
                true
            }
            when (deleted) {
                null -> call.respond(HttpStatusCode.NotFound, "Альбом не найден")
                false -> call.respond(HttpStatusCode.Forbidden, "Только владелец может удалить альбом")
                else -> call.respond(HttpStatusCode.NoContent)
            }
        }

        put("/albums/{id}/tracks") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@put
            }
            val albumId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid album id")
                return@put
            }
            val body = try {
                call.receive<AlbumTrackOrderReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                return@put
            }
            val ownerOk = newSuspendedTransaction {
                val r = Albums.selectAll().where { Albums.id eq albumId }.singleOrNull()
                    ?: return@newSuspendedTransaction null
                if (r[Albums.userId] != uid) return@newSuspendedTransaction false
                true
            }
            when (ownerOk) {
                null -> {
                    call.respond(HttpStatusCode.NotFound, "Альбом не найден")
                    return@put
                }
                false -> {
                    call.respond(HttpStatusCode.Forbidden, "Только владелец может менять состав")
                    return@put
                }
                else -> { }
            }
            val distinct = body.trackIds.toSet()
            if (distinct.size != body.trackIds.size) {
                call.respond(HttpStatusCode.BadRequest, "track_id в альбоме не должны повторяться")
                return@put
            }
            val existing = existingTrackIdSet(distinct)
            if (existing.size != distinct.size) {
                call.respond(HttpStatusCode.BadRequest, "Не все track_id существуют")
                return@put
            }
            newSuspendedTransaction {
                AlbumTracks.deleteWhere { AlbumTracks.albumId eq albumId }
                body.trackIds.forEachIndexed { index, tid ->
                    AlbumTracks.insert {
                        it[AlbumTracks.albumId] = albumId
                        it[AlbumTracks.trackId] = tid
                        it[AlbumTracks.position] = index
                    }
                }
                Albums.update({ Albums.id eq albumId }) {
                    it[Albums.updatedAt] = OffsetDateTime.now()
                }
            }
            call.respond(HttpStatusCode.OK)
        }
    }
}
