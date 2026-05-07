package com.example.features.thoughts

import com.example.database.Friendships
import com.example.database.Playlists
import com.example.database.Thoughts
import com.example.database.Tracks
import com.example.database.Users
import com.example.utils.currentUserId
import com.example.utils.primaryArtist
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime

private const val ATTACH_NONE = 0
private const val ATTACH_TRACK = 1
private const val ATTACH_PLAYLIST = 2

private suspend fun otherFriendIds(uid: Long): Set<Long> = newSuspendedTransaction {
    Friendships.selectAll().where {
        (Friendships.userLow eq uid) or (Friendships.userHigh eq uid)
    }.map { row ->
        if (row[Friendships.userLow] == uid) row[Friendships.userHigh] else row[Friendships.userLow]
    }.toSet()
}

private suspend fun nicknameByIds(ids: Collection<Long>): Map<Long, String> {
    if (ids.isEmpty()) return emptyMap()
    return newSuspendedTransaction {
        Users.selectAll().where { Users.id inList ids.distinct() }
            .associate { it[Users.id] to it[Users.nickname] }
    }
}

private suspend fun mapThoughtRows(
    rows: List<ResultRow>,
    viewerId: Long?,
    friendSet: Set<Long>,
): List<ThoughtFeedItemRemote> {
    if (rows.isEmpty()) return emptyList()
    val authorIds = rows.map { it[Thoughts.authorUserId] }.distinct()
    val nickByUser = nicknameByIds(authorIds)
    val trackIds = rows.mapNotNull { it[Thoughts.attachmentTrackId] }.distinct()
    val playlistIds = rows.mapNotNull { it[Thoughts.attachmentPlaylistId] }.distinct()
    val trackMetaById = newSuspendedTransaction {
        if (trackIds.isEmpty()) emptyMap()
        else {
            Tracks.selectAll().where { Tracks.id inList trackIds }
                .associate {
                    it[Tracks.id] to Pair(
                        it[Tracks.title],
                        it[Tracks.artists].primaryArtist().ifBlank { null },
                    )
                }
        }
    }
    val playlistTitleById = newSuspendedTransaction {
        if (playlistIds.isEmpty()) emptyMap()
        else {
            Playlists.selectAll().where { Playlists.id inList playlistIds }
                .associate { it[Playlists.id] to it[Playlists.title] }
        }
    }
    return rows.map { tr ->
        val aid = tr[Thoughts.authorUserId]
        val tid = tr[Thoughts.attachmentTrackId]
        val pid = tr[Thoughts.attachmentPlaylistId]
        val isFriend = viewerId != null && (aid == viewerId || friendSet.contains(aid))
        ThoughtFeedItemRemote(
            id = tr[Thoughts.id],
            authorUserId = aid.toInt(),
            authorNickname = nickByUser[aid].orEmpty(),
            bodyText = tr[Thoughts.bodyText],
            createdAt = tr[Thoughts.createdAt]?.toString(),
            attachmentType = tr[Thoughts.attachmentType],
            attachmentTrackId = tid?.toInt(),
            attachmentPlaylistId = pid?.toInt(),
            attachmentTrackTitle = tid?.let { trackMetaById[it]?.first },
            attachmentTrackArtist = tid?.let { trackMetaById[it]?.second },
            attachmentPlaylistTitle = pid?.let { playlistTitleById[it] },
            isFriend = isFriend,
        )
    }
}

fun Application.configureThoughtsRouting() {
    routing {
        get("/thoughts/feed") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val scope = call.request.queryParameters["scope"]?.lowercase() ?: "friends"
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 40
            val friendSet = otherFriendIds(uid)
            val allowedAuthors = friendSet + uid
            val rows = newSuspendedTransaction {
                when (scope) {
                    "popular" -> {
                        Thoughts.selectAll()
                            .orderBy(Thoughts.id, SortOrder.DESC)
                            .limit(limit * 4)
                            .toList()
                    }
                    else -> {
                        Thoughts.selectAll()
                            .where { Thoughts.authorUserId inList allowedAuthors.toList() }
                            .orderBy(Thoughts.id, SortOrder.DESC)
                            .limit(limit * 2)
                            .toList()
                    }
                }
            }
            val authorIds = rows.map { it[Thoughts.authorUserId] }.distinct()
            val nickMap = nicknameByIds(authorIds)
            val visible = rows.filter { row ->
                val nick = nickMap[row[Thoughts.authorUserId]].orEmpty()
                !nick.startsWith("__")
            }.take(limit)
            call.respond(mapThoughtRows(visible, uid, friendSet))
        }

        post("/thoughts") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val body = try {
                call.receive<ThoughtCreateReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON body")
                return@post
            }
            val text = body.bodyText.trim()
            if (text.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "bodyText is required")
                return@post
            }
            var attachType: Int? = body.attachmentType
            var trackId: Long? = body.attachmentTrackId
            var playlistId: Long? = body.attachmentPlaylistId
            when (attachType) {
                null, ATTACH_NONE -> {
                    attachType = null
                    trackId = null
                    playlistId = null
                }
                ATTACH_TRACK -> {
                    playlistId = null
                    val tid = trackId ?: run {
                        call.respond(HttpStatusCode.BadRequest, "attachmentTrackId required")
                        return@post
                    }
                    val exists = newSuspendedTransaction {
                        Tracks.selectAll().where { Tracks.id eq tid }.any()
                    }
                    if (!exists) {
                        call.respond(HttpStatusCode.BadRequest, "Track not found")
                        return@post
                    }
                }
                ATTACH_PLAYLIST -> {
                    trackId = null
                    val pid = playlistId ?: run {
                        call.respond(HttpStatusCode.BadRequest, "attachmentPlaylistId required")
                        return@post
                    }
                    val row = newSuspendedTransaction {
                        Playlists.selectAll().where { Playlists.id eq pid }.singleOrNull()
                    } ?: run {
                        call.respond(HttpStatusCode.BadRequest, "Playlist not found")
                        return@post
                    }
                    val owner = row[Playlists.userId]
                    val isPublic = row[Playlists.isPublic] == true
                    if (owner != uid && !isPublic) {
                        call.respond(HttpStatusCode.Forbidden, "Cannot attach private foreign playlist")
                        return@post
                    }
                }
                else -> {
                    call.respond(HttpStatusCode.BadRequest, "Invalid attachmentType")
                    return@post
                }
            }
            val now = OffsetDateTime.now()
            val newId = newSuspendedTransaction {
                Thoughts.insert {
                    it[Thoughts.authorUserId] = uid
                    it[Thoughts.bodyText] = text
                    it[Thoughts.attachmentType] = attachType
                    it[Thoughts.attachmentTrackId] = trackId
                    it[Thoughts.attachmentPlaylistId] = playlistId
                    it[Thoughts.createdAt] = now
                    it[Thoughts.updatedAt] = now
                } get Thoughts.id
            }
            val inserted = newSuspendedTransaction {
                Thoughts.selectAll().where { Thoughts.id eq newId }.single()
            }
            val friendSet = otherFriendIds(uid)
            val item = mapThoughtRows(listOf(inserted), uid, friendSet).first()
            call.respond(HttpStatusCode.Created, item)
        }

        get("/users/{id}/thoughts") {
            val userId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid user id")
                return@get
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
            val viewerId = call.currentUserId()?.toLong()
            val friendSet = if (viewerId != null) otherFriendIds(viewerId) else emptySet()
            val userOk = newSuspendedTransaction {
                Users.selectAll().where { Users.id eq userId }.singleOrNull()
                    ?.let { !it[Users.nickname].startsWith("__") } == true
            }
            if (!userOk) {
                call.respond(HttpStatusCode.NotFound, "User not found")
                return@get
            }
            val rows = newSuspendedTransaction {
                Thoughts.selectAll()
                    .where { Thoughts.authorUserId eq userId }
                    .orderBy(Thoughts.id, SortOrder.DESC)
                    .limit(limit)
                    .toList()
            }
            call.respond(mapThoughtRows(rows, viewerId, friendSet))
        }

        post("/users/{id}/thought") {
            val userId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "User ID is required")
                return@post
            }

            val newThought = call.request.queryParameters["th"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Thought parameter 'th' is required")
                return@post
            }

            try {
                val userExists = newSuspendedTransaction {
                    Users.selectAll().where { Users.id eq userId }.count() > 0
                }

                if (!userExists) {
                    call.respond(HttpStatusCode.NotFound, "User not found")
                    return@post
                }

                newSuspendedTransaction {
                    Thoughts.insert {
                        it[Thoughts.authorUserId] = userId
                        it[Thoughts.bodyText] = newThought
                        it[Thoughts.attachmentType] = null
                        it[Thoughts.attachmentTrackId] = null
                        it[Thoughts.attachmentPlaylistId] = null
                        it[Thoughts.createdAt] = OffsetDateTime.now()
                        it[Thoughts.updatedAt] = OffsetDateTime.now()
                    }
                }

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "status" to "success",
                        "message" to "Thought updated successfully",
                    ),
                )
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error updating thought")
            }
        }

        get("/users/{id}/thought") {
            val userId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "User ID is required")
                return@get
            }

            try {
                val thought = newSuspendedTransaction {
                    Thoughts.selectAll().where { Thoughts.authorUserId eq userId }
                        .orderBy(Thoughts.id, SortOrder.DESC)
                        .limit(1)
                        .map { it[Thoughts.bodyText] }
                        .firstOrNull()
                }

                if (thought != null) {
                    call.respond(HttpStatusCode.OK, mapOf("thought" to thought))
                } else {
                    call.respond(HttpStatusCode.NotFound, "User not found or no thought")
                }
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error fetching thought")
            }
        }
    }
}
