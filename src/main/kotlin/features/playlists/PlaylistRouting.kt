package com.example.features.playlists

import com.example.database.PlaylistLikes
import com.example.database.PlaylistTracks
import com.example.database.Playlists
import com.example.database.Tracks
import com.example.database.Users
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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

@Serializable
data class PlaylistCreateReceive(
    val title: String,
    val isPublic: Boolean = false,
)

@Serializable
data class PlaylistCreatedRemote(
    val id: Int,
    val title: String?,
)

@Serializable
data class PlaylistUpdateReceive(
    val title: String? = null,
    val isPublic: Boolean? = null,
)

@Serializable
data class PlaylistTrackOrderReceive(
    val trackIds: List<Long>,
)

@Serializable
data class PlaylistListItemRemote(
    val id: Int,
    val title: String?,
    val isPublic: Boolean?,
    val trackCount: Int,
)

@Serializable
data class PlaylistTrackEntryRemote(
    val position: Int,
    val trackId: Int,
    val title: String?,
    val artist: String?,
)

@Serializable
data class PlaylistDetailRemote(
    val id: Int,
    val title: String?,
    val isPublic: Boolean?,
    val ownerUserId: Long,
    val ownerNickname: String?,
    val likesCount: Int,
    val tracks: List<PlaylistTrackEntryRemote>,
)

@Serializable
data class PublicPlaylistItemRemote(
    val id: Int,
    val title: String?,
    val ownerUserId: Long,
    val ownerNickname: String?,
    val likesCount: Int,
    val trackCount: Int,
)

@Serializable
data class PlaylistLikeStatusRemote(
    val liked: Boolean,
    val likesCount: Int,
)

private suspend fun existingTrackIdSet(ids: Collection<Long>): Set<Long> = newSuspendedTransaction {
    if (ids.isEmpty()) return@newSuspendedTransaction emptySet()
    Tracks.selectAll().where { Tracks.id inList ids.distinct() }
        .map { it[Tracks.id] }
        .toSet()
}

private suspend fun playlistLikesCount(playlistId: Long): Int = newSuspendedTransaction {
    PlaylistLikes.selectAll().where { PlaylistLikes.playlistId eq playlistId }.count().toInt()
}

fun Application.configurePlaylistRouting() {
    routing {
        get("/playlists/public") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
            val qRaw = call.request.queryParameters["q"]?.trim()?.lowercase().orEmpty()
            val qSafe = qRaw.replace("%", "").replace("_", "").take(64)
            val rows = newSuspendedTransaction {
                Playlists.selectAll()
                    .where {
                        val public = Playlists.isPublic eq true
                        if (qSafe.isEmpty()) public
                        else public and (Playlists.title.lowerCase() like "%$qSafe%")
                    }
                    .orderBy(Playlists.id, SortOrder.DESC)
                    .limit(limit)
                    .map { it }
            }
            val ids = rows.map { it[Playlists.id] }
            val likeCounts = newSuspendedTransaction {
                if (ids.isEmpty()) emptyMap()
                else {
                    PlaylistLikes.selectAll().where { PlaylistLikes.playlistId inList ids }
                        .groupBy { it[PlaylistLikes.playlistId] }
                        .mapValues { it.value.size }
                }
            }
            val trackCounts = newSuspendedTransaction {
                if (ids.isEmpty()) emptyMap()
                else {
                    PlaylistTracks.selectAll().where { PlaylistTracks.playlistId inList ids }
                        .groupBy { it[PlaylistTracks.playlistId] }
                        .mapValues { it.value.size }
                }
            }
            val ownerIds = rows.map { it[Playlists.userId] }.distinct()
            val nickByOwner = newSuspendedTransaction {
                if (ownerIds.isEmpty()) emptyMap()
                else {
                    Users.selectAll().where { Users.id inList ownerIds }
                        .associate { it[Users.id] to it[Users.nickname] }
                }
            }
            call.respond(
                rows.map {
                    PublicPlaylistItemRemote(
                        id = it[Playlists.id].toInt(),
                        title = it[Playlists.title],
                        ownerUserId = it[Playlists.userId],
                        ownerNickname = nickByOwner[it[Playlists.userId]],
                        likesCount = likeCounts[it[Playlists.id]] ?: 0,
                        trackCount = trackCounts[it[Playlists.id]] ?: 0,
                    )
                },
            )
        }

        post("/playlists") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val body = try {
                call.receive<PlaylistCreateReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                return@post
            }
            val title = body.title.trim().ifEmpty { "Untitled" }
            val pid = newSuspendedTransaction {
                Playlists.insert {
                    it[Playlists.userId] = uid
                    it[Playlists.title] = title
                    it[Playlists.isPublic] = body.isPublic
                    it[Playlists.coverStorageKey] = null
                    it[Playlists.createdAt] = OffsetDateTime.now()
                    it[Playlists.updatedAt] = OffsetDateTime.now()
                } get Playlists.id
            }
            call.respond(PlaylistCreatedRemote(id = pid.toInt(), title = title))
        }

        get("/me/playlists") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val rows = newSuspendedTransaction {
                Playlists.selectAll()
                    .where { Playlists.userId eq uid }
                    .orderBy(Playlists.id, SortOrder.DESC)
                    .map { it }
            }
            val ids = rows.map { it[Playlists.id] }
            val trackCounts = newSuspendedTransaction {
                if (ids.isEmpty()) emptyMap()
                else {
                    PlaylistTracks.selectAll().where { PlaylistTracks.playlistId inList ids }
                        .groupBy { it[PlaylistTracks.playlistId] }
                        .mapValues { it.value.size }
                }
            }
            call.respond(
                rows.map {
                    PlaylistListItemRemote(
                        id = it[Playlists.id].toInt(),
                        title = it[Playlists.title],
                        isPublic = it[Playlists.isPublic],
                        trackCount = trackCounts[it[Playlists.id]] ?: 0,
                    )
                },
            )
        }

        get("/playlists/{id}") {
            val playlistId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid playlist id")
                return@get
            }
            val uid = call.currentUserId()?.toLong()
            val row = newSuspendedTransaction {
                Playlists.selectAll().where { Playlists.id eq playlistId }.singleOrNull()
            } ?: run {
                call.respond(HttpStatusCode.NotFound, "Плейлист не найден")
                return@get
            }
            val owner = row[Playlists.userId]
            val isPublic = row[Playlists.isPublic] == true
            when {
                owner == uid -> { }
                isPublic -> { }
                uid == null -> {
                    call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                    return@get
                }
                else -> {
                    call.respond(HttpStatusCode.Forbidden, "Плейлист недоступен")
                    return@get
                }
            }
            val likesCount = playlistLikesCount(playlistId)
            val ordered = newSuspendedTransaction {
                PlaylistTracks.selectAll()
                    .where { PlaylistTracks.playlistId eq playlistId }
                    .orderBy(PlaylistTracks.position to SortOrder.ASC)
                    .map { it[PlaylistTracks.position] to it[PlaylistTracks.trackId] }
            }
            val tidSet = ordered.map { it.second }.distinct()
            val byId = newSuspendedTransaction {
                if (tidSet.isEmpty()) emptyMap()
                else Tracks.selectAll().where { Tracks.id inList tidSet }
                    .associate { it[Tracks.id] to it }
            }
            val tracks = ordered.mapNotNull { (pos, tid) ->
                val tr = byId[tid] ?: return@mapNotNull null
                PlaylistTrackEntryRemote(
                    position = pos,
                    trackId = tr[Tracks.id].toInt(),
                    title = tr[Tracks.title],
                    artist = tr[Tracks.artists].primaryArtist().ifBlank { null },
                )
            }
            val ownerNick = newSuspendedTransaction {
                Users.selectAll().where { Users.id eq owner }.singleOrNull()?.get(Users.nickname)
            }
            call.respond(
                PlaylistDetailRemote(
                    id = row[Playlists.id].toInt(),
                    title = row[Playlists.title],
                    isPublic = row[Playlists.isPublic],
                    ownerUserId = owner,
                    ownerNickname = ownerNick,
                    likesCount = likesCount,
                    tracks = tracks,
                ),
            )
        }

        put("/playlists/{id}") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@put
            }
            val playlistId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid playlist id")
                return@put
            }
            val body = try {
                call.receive<PlaylistUpdateReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                return@put
            }
            val updated = newSuspendedTransaction {
                val r = Playlists.selectAll().where { Playlists.id eq playlistId }.singleOrNull()
                    ?: return@newSuspendedTransaction null
                if (r[Playlists.userId] != uid) return@newSuspendedTransaction false
                val title = body.title?.trim()
                Playlists.update({ Playlists.id eq playlistId }) {
                    if (title != null) it[Playlists.title] = title.ifEmpty { "Untitled" }
                    if (body.isPublic != null) it[Playlists.isPublic] = body.isPublic
                    it[Playlists.updatedAt] = OffsetDateTime.now()
                }
                true
            }
            when (updated) {
                null -> call.respond(HttpStatusCode.NotFound, "Плейлист не найден")
                false -> call.respond(HttpStatusCode.Forbidden, "Только владелец может менять плейлист")
                else -> call.respond(HttpStatusCode.OK)
            }
        }

        delete("/playlists/{id}") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@delete
            }
            val playlistId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid playlist id")
                return@delete
            }
            val deleted = newSuspendedTransaction {
                val r = Playlists.selectAll().where { Playlists.id eq playlistId }.singleOrNull()
                    ?: return@newSuspendedTransaction null
                if (r[Playlists.userId] != uid) return@newSuspendedTransaction false
                PlaylistTracks.deleteWhere { PlaylistTracks.playlistId eq playlistId }
                PlaylistLikes.deleteWhere { PlaylistLikes.playlistId eq playlistId }
                Playlists.deleteWhere { Playlists.id eq playlistId }
                true
            }
            when (deleted) {
                null -> call.respond(HttpStatusCode.NotFound, "Плейлист не найден")
                false -> call.respond(HttpStatusCode.Forbidden, "Только владелец может удалить плейлист")
                else -> call.respond(HttpStatusCode.NoContent)
            }
        }

        put("/playlists/{id}/tracks") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@put
            }
            val playlistId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid playlist id")
                return@put
            }
            val body = try {
                call.receive<PlaylistTrackOrderReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                return@put
            }
            val ownerOk = newSuspendedTransaction {
                val r = Playlists.selectAll().where { Playlists.id eq playlistId }.singleOrNull()
                    ?: return@newSuspendedTransaction null
                if (r[Playlists.userId] != uid) return@newSuspendedTransaction false
                true
            }
            when (ownerOk) {
                null -> {
                    call.respond(HttpStatusCode.NotFound, "Плейлист не найден")
                    return@put
                }
                false -> {
                    call.respond(HttpStatusCode.Forbidden, "Только владелец может менять состав")
                    return@put
                }
                else -> { }
            }
            val distinct = body.trackIds.toSet()
            val existing = existingTrackIdSet(distinct)
            if (existing.size != distinct.size) {
                call.respond(HttpStatusCode.BadRequest, "Не все track_id существуют")
                return@put
            }
            newSuspendedTransaction {
                PlaylistTracks.deleteWhere { PlaylistTracks.playlistId eq playlistId }
                body.trackIds.forEachIndexed { index, tid ->
                    PlaylistTracks.insert {
                        it[PlaylistTracks.playlistId] = playlistId
                        it[PlaylistTracks.trackId] = tid
                        it[PlaylistTracks.position] = index
                    }
                }
                Playlists.update({ Playlists.id eq playlistId }) {
                    it[Playlists.updatedAt] = OffsetDateTime.now()
                }
            }
            call.respond(HttpStatusCode.OK)
        }

        post("/playlists/{id}/like") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val playlistId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid playlist id")
                return@post
            }
            val meta = newSuspendedTransaction {
                Playlists.selectAll().where { Playlists.id eq playlistId }.singleOrNull()
            } ?: run {
                call.respond(HttpStatusCode.NotFound, "Плейлист не найден")
                return@post
            }
            if (meta[Playlists.isPublic] != true) {
                call.respond(HttpStatusCode.Forbidden, "Лайк доступен только для публичных плейлистов")
                return@post
            }
            val (likedNow, cnt) = newSuspendedTransaction {
                val already = PlaylistLikes.selectAll().where {
                    (PlaylistLikes.playlistId eq playlistId) and (PlaylistLikes.userId eq uid)
                }.any()
                if (already) {
                    PlaylistLikes.deleteWhere {
                        (PlaylistLikes.playlistId eq playlistId) and (PlaylistLikes.userId eq uid)
                    }
                } else {
                    PlaylistLikes.insert {
                        it[PlaylistLikes.playlistId] = playlistId
                        it[PlaylistLikes.userId] = uid
                    }
                }
                val c = PlaylistLikes.selectAll().where { PlaylistLikes.playlistId eq playlistId }.count().toInt()
                (!already) to c
            }
            call.respond(PlaylistLikeStatusRemote(liked = likedNow, likesCount = cnt))
        }

        get("/playlists/{id}/like") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val playlistId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid playlist id")
                return@get
            }
            val exists = newSuspendedTransaction {
                Playlists.selectAll().where { Playlists.id eq playlistId }.singleOrNull() != null
            }
            if (!exists) {
                call.respond(HttpStatusCode.NotFound, "Плейлист не найден")
                return@get
            }
            val liked = newSuspendedTransaction {
                PlaylistLikes.selectAll().where {
                    (PlaylistLikes.playlistId eq playlistId) and (PlaylistLikes.userId eq uid)
                }.any()
            }
            val cnt = playlistLikesCount(playlistId)
            call.respond(PlaylistLikeStatusRemote(liked = liked, likesCount = cnt))
        }
    }
}
