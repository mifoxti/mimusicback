package com.example.features.thoughts

import com.example.database.Comments
import com.example.database.Friendships
import com.example.database.Playlists
import com.example.database.ThoughtLikes
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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
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

private data class ThoughtEngagement(
    val likesCount: Int,
    val likedByMe: Boolean,
    val commentsCount: Int,
)

private suspend fun engagementForThoughts(
    thoughtIds: List<Long>,
    viewerId: Long?,
): Map<Long, ThoughtEngagement> {
    if (thoughtIds.isEmpty()) return emptyMap()
    return newSuspendedTransaction {
        val likeCounts = ThoughtLikes.selectAll()
            .where { ThoughtLikes.thoughtId inList thoughtIds }
            .groupBy { it[ThoughtLikes.thoughtId] }
            .mapValues { it.value.size }
        val likedByViewer = if (viewerId == null) {
            emptySet()
        } else {
            ThoughtLikes.selectAll()
                .where {
                    (ThoughtLikes.thoughtId inList thoughtIds) and (ThoughtLikes.userId eq viewerId)
                }
                .map { it[ThoughtLikes.thoughtId] }
                .toSet()
        }
        val commentCounts = Comments.selectAll()
            .where { Comments.thoughtId inList thoughtIds }
            .groupBy { it[Comments.thoughtId] }
            .mapValues { it.value.size }
        thoughtIds.associateWith { tid ->
            ThoughtEngagement(
                likesCount = likeCounts[tid] ?: 0,
                likedByMe = likedByViewer.contains(tid),
                commentsCount = commentCounts[tid] ?: 0,
            )
        }
    }
}

private suspend fun thoughtOwnedBy(thoughtId: Long, uid: Long): ResultRow? {
    val row = newSuspendedTransaction {
        Thoughts.selectAll().where { Thoughts.id eq thoughtId }.singleOrNull()
    } ?: return null
    if (row[Thoughts.authorUserId] != uid) return null
    val nick = nicknameByIds(listOf(uid))[uid].orEmpty()
    if (nick.startsWith("__")) return null
    return row
}

/** Лайк/комментарий: мысль существует и автор не служебный (как в [GET /users/{id}/thoughts]). */
private suspend fun thoughtOpenForInteraction(thoughtId: Long): Boolean {
    val row = newSuspendedTransaction {
        Thoughts.selectAll().where { Thoughts.id eq thoughtId }.singleOrNull()
    } ?: return false
    val authorId = row[Thoughts.authorUserId]
    val nick = nicknameByIds(listOf(authorId))[authorId].orEmpty()
    return !nick.startsWith("__")
}

private suspend fun mapThoughtRows(
    rows: List<ResultRow>,
    viewerId: Long?,
    friendSet: Set<Long>,
): List<ThoughtFeedItemRemote> {
    if (rows.isEmpty()) return emptyList()
    val thoughtIds = rows.map { it[Thoughts.id] }
    val engagement = engagementForThoughts(thoughtIds, viewerId)
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
        val eng = engagement[tr[Thoughts.id]] ?: ThoughtEngagement(0, false, 0)
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
            likesCount = eng.likesCount,
            likedByMe = eng.likedByMe,
            commentsCount = eng.commentsCount,
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
            var visible = rows.filter { row ->
                val nick = nickMap[row[Thoughts.authorUserId]].orEmpty()
                !nick.startsWith("__")
            }
            if (scope == "popular") {
                val engagement = engagementForThoughts(visible.map { it[Thoughts.id] }, uid)
                visible = visible.sortedWith(
                    compareByDescending<ResultRow> { engagement[it[Thoughts.id]]?.likesCount ?: 0 }
                        .thenByDescending { it[Thoughts.id] },
                )
            }
            call.respond(mapThoughtRows(visible.take(limit), uid, friendSet))
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

        put("/thoughts/{thoughtId}") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@put
            }
            val thoughtId = call.parameters["thoughtId"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid thought id")
                return@put
            }
            if (thoughtOwnedBy(thoughtId, uid) == null) {
                call.respond(HttpStatusCode.NotFound, "Thought not found")
                return@put
            }
            val body = try {
                call.receive<ThoughtUpdateReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON body")
                return@put
            }
            val text = body.bodyText.trim()
            if (text.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "bodyText is required")
                return@put
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
                        return@put
                    }
                    val exists = newSuspendedTransaction {
                        Tracks.selectAll().where { Tracks.id eq tid }.any()
                    }
                    if (!exists) {
                        call.respond(HttpStatusCode.BadRequest, "Track not found")
                        return@put
                    }
                }
                ATTACH_PLAYLIST -> {
                    trackId = null
                    val pid = playlistId ?: run {
                        call.respond(HttpStatusCode.BadRequest, "attachmentPlaylistId required")
                        return@put
                    }
                    val row = newSuspendedTransaction {
                        Playlists.selectAll().where { Playlists.id eq pid }.singleOrNull()
                    } ?: run {
                        call.respond(HttpStatusCode.BadRequest, "Playlist not found")
                        return@put
                    }
                    val owner = row[Playlists.userId]
                    val isPublic = row[Playlists.isPublic] == true
                    if (owner != uid && !isPublic) {
                        call.respond(HttpStatusCode.Forbidden, "Cannot attach private foreign playlist")
                        return@put
                    }
                }
                else -> {
                    call.respond(HttpStatusCode.BadRequest, "Invalid attachmentType")
                    return@put
                }
            }
            val now = OffsetDateTime.now()
            newSuspendedTransaction {
                Thoughts.update({ Thoughts.id eq thoughtId }) {
                    it[Thoughts.bodyText] = text
                    it[Thoughts.attachmentType] = attachType
                    it[Thoughts.attachmentTrackId] = trackId
                    it[Thoughts.attachmentPlaylistId] = playlistId
                    it[Thoughts.updatedAt] = now
                }
            }
            val updated = newSuspendedTransaction {
                Thoughts.selectAll().where { Thoughts.id eq thoughtId }.single()
            }
            val friendSet = otherFriendIds(uid)
            val item = mapThoughtRows(listOf(updated), uid, friendSet).first()
            call.respond(item)
        }

        delete("/thoughts/{thoughtId}") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@delete
            }
            val thoughtId = call.parameters["thoughtId"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid thought id")
                return@delete
            }
            if (thoughtOwnedBy(thoughtId, uid) == null) {
                call.respond(HttpStatusCode.NotFound, "Thought not found")
                return@delete
            }
            newSuspendedTransaction {
                ThoughtLikes.deleteWhere { ThoughtLikes.thoughtId eq thoughtId }
                Comments.deleteWhere { Comments.thoughtId eq thoughtId }
                Thoughts.deleteWhere { Thoughts.id eq thoughtId }
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/thoughts/{thoughtId}/like") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val thoughtId = call.parameters["thoughtId"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid thought id")
                return@post
            }
            if (!thoughtOpenForInteraction(thoughtId)) {
                call.respond(HttpStatusCode.NotFound, "Thought not found")
                return@post
            }
            val liked = newSuspendedTransaction {
                val exists = ThoughtLikes.selectAll().where {
                    (ThoughtLikes.thoughtId eq thoughtId) and (ThoughtLikes.userId eq uid)
                }.any()
                if (exists) {
                    ThoughtLikes.deleteWhere {
                        (ThoughtLikes.thoughtId eq thoughtId) and (ThoughtLikes.userId eq uid)
                    }
                    false
                } else {
                    ThoughtLikes.insert {
                        it[ThoughtLikes.thoughtId] = thoughtId
                        it[ThoughtLikes.userId] = uid
                    }
                    true
                }
            }
            val likesCount = newSuspendedTransaction {
                ThoughtLikes.selectAll().where { ThoughtLikes.thoughtId eq thoughtId }.count().toInt()
            }
            call.respond(ThoughtLikeRemote(status = liked, likesCount = likesCount))
        }

        get("/thoughts/{thoughtId}/comments") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val thoughtId = call.parameters["thoughtId"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid thought id")
                return@get
            }
            if (!thoughtOpenForInteraction(thoughtId)) {
                call.respond(HttpStatusCode.NotFound, "Thought not found")
                return@get
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 100
            val rows = newSuspendedTransaction {
                Comments.selectAll()
                    .where { Comments.thoughtId eq thoughtId }
                    .orderBy(Comments.id, SortOrder.ASC)
                    .limit(limit)
                    .toList()
            }
            val authorIds = rows.map { it[Comments.authorUserId] }.distinct()
            val nickByUser = nicknameByIds(authorIds)
            call.respond(
                rows.map { cr ->
                    ThoughtCommentRemote(
                        id = cr[Comments.id],
                        authorUserId = cr[Comments.authorUserId].toInt(),
                        authorNickname = nickByUser[cr[Comments.authorUserId]].orEmpty(),
                        bodyText = cr[Comments.bodyText],
                        createdAt = cr[Comments.createdAt]?.toString(),
                    )
                },
            )
        }

        post("/thoughts/{thoughtId}/comments") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val thoughtId = call.parameters["thoughtId"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid thought id")
                return@post
            }
            if (!thoughtOpenForInteraction(thoughtId)) {
                call.respond(HttpStatusCode.NotFound, "Thought not found")
                return@post
            }
            val body = try {
                call.receive<ThoughtCommentCreateReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON body")
                return@post
            }
            val text = body.bodyText.trim()
            if (text.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "bodyText is required")
                return@post
            }
            val now = OffsetDateTime.now()
            val newId = newSuspendedTransaction {
                Comments.insert {
                    it[Comments.thoughtId] = thoughtId
                    it[Comments.authorUserId] = uid
                    it[Comments.bodyText] = text
                    it[Comments.createdAt] = now
                    it[Comments.updatedAt] = now
                } get Comments.id
            }
            val nick = nicknameByIds(listOf(uid))[uid].orEmpty()
            call.respond(
                HttpStatusCode.Created,
                ThoughtCommentRemote(
                    id = newId,
                    authorUserId = uid.toInt(),
                    authorNickname = nick,
                    bodyText = text,
                    createdAt = now.toString(),
                ),
            )
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
