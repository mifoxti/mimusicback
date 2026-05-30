package com.example.features.friends

import com.example.colisten.ColistenRoomManager
import com.example.database.FriendRequests
import com.example.database.Friendships
import com.example.database.Notifications
import com.example.database.Tracks
import com.example.database.UserNowPlaying
import com.example.database.UserPresence
import com.example.database.Users
import com.example.utils.primaryArtist
import com.example.utils.currentUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime

private const val NOTIFICATION_FRIEND_REQUEST = 10
private const val NOTIFICATION_FRIEND_ACCEPTED = 11

private fun canonicalPair(a: Long, b: Long): Pair<Long, Long> =
    if (a < b) a to b else b to a

private suspend fun insertNotification(
    recipientId: Long,
    actorId: Long?,
    type: Int,
    entityRef: String?,
    entityId: Long?,
) {
    newSuspendedTransaction {
        Notifications.insert {
            it[Notifications.recipientUserId] = recipientId
            it[Notifications.actorUserId] = actorId
            it[Notifications.type] = type
            it[Notifications.entityRef] = entityRef
            it[Notifications.entityId] = entityId
            it[Notifications.payloadJson] = null
            it[Notifications.readAt] = null
            it[Notifications.createdAt] = OffsetDateTime.now()
        }
    }
}

private suspend fun areFriends(a: Long, b: Long): Boolean {
    val (l, h) = canonicalPair(a, b)
    return newSuspendedTransaction {
        Friendships.selectAll().where {
            (Friendships.userLow eq l) and (Friendships.userHigh eq h)
        }.any()
    }
}

private suspend fun insertFriendship(a: Long, b: Long) {
    val (l, h) = canonicalPair(a, b)
    newSuspendedTransaction {
        if (Friendships.selectAll().where {
                (Friendships.userLow eq l) and (Friendships.userHigh eq h)
            }.any()
        ) {
            return@newSuspendedTransaction
        }
        Friendships.insert {
            it[Friendships.userLow] = l
            it[Friendships.userHigh] = h
            it[Friendships.createdAt] = OffsetDateTime.now()
        }
    }
}

private suspend fun nicknameById(userId: Long): String =
    newSuspendedTransaction {
        Users.selectAll().where { Users.id eq userId }.singleOrNull()?.get(Users.nickname).orEmpty()
    }

fun Application.configureFriendRouting() {
    routing {
        get("/friends/requests/incoming") {
            val userId = call.currentUserId() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val uid = userId.toLong()
            val list = newSuspendedTransaction {
                FriendRequests.selectAll().where {
                    (FriendRequests.toUserId eq uid) and (FriendRequests.status eq "pending")
                }.map { row ->
                    val fromId = row[FriendRequests.fromUserId]
                    FriendIncomingDto(
                        fromUserId = fromId.toInt(),
                        nickname = Users.selectAll().where { Users.id eq fromId }.singleOrNull()
                            ?.get(Users.nickname).orEmpty(),
                        createdAt = row[FriendRequests.createdAt]?.toString(),
                    )
                }
            }
            call.respond(list)
        }

        post("/friends/requests/{fromUserId}/accept") {
            val userId = call.currentUserId() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val uid = userId.toLong()
            val fromId = call.parameters["fromUserId"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid fromUserId")
                return@post
            }
            val ok = newSuspendedTransaction {
                val pending = FriendRequests.selectAll().where {
                    (FriendRequests.fromUserId eq fromId) and
                        (FriendRequests.toUserId eq uid) and
                        (FriendRequests.status eq "pending")
                }.any()
                if (!pending) return@newSuspendedTransaction false
                FriendRequests.deleteWhere {
                    ((FriendRequests.fromUserId eq fromId) and (FriendRequests.toUserId eq uid)) or
                        ((FriendRequests.fromUserId eq uid) and (FriendRequests.toUserId eq fromId))
                }
                true
            }
            if (!ok) {
                call.respond(HttpStatusCode.NotFound, "No pending request from this user")
                return@post
            }
            insertFriendship(uid, fromId)
            insertNotification(
                recipientId = fromId,
                actorId = uid,
                type = NOTIFICATION_FRIEND_ACCEPTED,
                entityRef = "friendship",
                entityId = uid,
            )
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/friends/requests/{fromUserId}/decline") {
            val userId = call.currentUserId() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val uid = userId.toLong()
            val fromId = call.parameters["fromUserId"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid fromUserId")
                return@post
            }
            val removed = newSuspendedTransaction {
                FriendRequests.deleteWhere {
                    (FriendRequests.fromUserId eq fromId) and
                        (FriendRequests.toUserId eq uid) and
                        (FriendRequests.status eq "pending")
                }
            }
            if (removed == 0) {
                call.respond(HttpStatusCode.NotFound, "No pending request from this user")
                return@post
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/friends/requests") {
            val userId = call.currentUserId() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val uid = userId.toLong()
            val toId = try {
                call.receive<FriendRequestCreateDto>().toUserId.toLong()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON: expected { \"toUserId\": number }")
                return@post
            }
            if (toId == uid) {
                call.respond(HttpStatusCode.BadRequest, "Cannot add yourself as friend")
                return@post
            }
            val targetExists = newSuspendedTransaction {
                Users.selectAll().where { Users.id eq toId }.any()
            }
            if (!targetExists) {
                call.respond(HttpStatusCode.NotFound, "User not found")
                return@post
            }
            if (areFriends(uid, toId)) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_friends"))
                return@post
            }
            val reversePending = newSuspendedTransaction {
                FriendRequests.selectAll().where {
                    (FriendRequests.fromUserId eq toId) and
                        (FriendRequests.toUserId eq uid) and
                        (FriendRequests.status eq "pending")
                }.any()
            }
            if (reversePending) {
                call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "incoming_request_exists", "hint" to "Use POST /friends/requests/{fromUserId}/accept"),
                )
                return@post
            }
            val duplicateOutgoing = newSuspendedTransaction {
                FriendRequests.selectAll().where {
                    (FriendRequests.fromUserId eq uid) and
                        (FriendRequests.toUserId eq toId) and
                        (FriendRequests.status eq "pending")
                }.any()
            }
            if (duplicateOutgoing) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "request_already_sent"))
                return@post
            }
            val now = OffsetDateTime.now()
            try {
                newSuspendedTransaction {
                    FriendRequests.insert {
                        it[FriendRequests.fromUserId] = uid
                        it[FriendRequests.toUserId] = toId
                        it[FriendRequests.status] = "pending"
                        it[FriendRequests.createdAt] = now
                        it[FriendRequests.respondedAt] = null
                    }
                }
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "request_exists"))
                return@post
            }
            insertNotification(
                recipientId = toId,
                actorId = uid,
                type = NOTIFICATION_FRIEND_REQUEST,
                entityRef = "friend_request",
                entityId = uid,
            )
            call.respond(HttpStatusCode.Created, mapOf("status" to "ok"))
        }

        post("/friends") {
            val userId = call.currentUserId() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val uid = userId.toLong()
            val toId = try {
                call.receive<AddFriendRequest>().friendId.toLong()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid body: expected { friendId: Int }")
                return@post
            }
            if (toId == uid) {
                call.respond(HttpStatusCode.BadRequest, "Cannot add yourself as friend")
                return@post
            }
            if (!newSuspendedTransaction { Users.selectAll().where { Users.id eq toId }.any() }) {
                call.respond(HttpStatusCode.NotFound, "User not found")
                return@post
            }
            if (areFriends(uid, toId)) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_friends"))
                return@post
            }
            if (newSuspendedTransaction {
                    FriendRequests.selectAll().where {
                        (FriendRequests.fromUserId eq toId) and
                            (FriendRequests.toUserId eq uid) and
                            (FriendRequests.status eq "pending")
                    }.any()
                }
            ) {
                call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "incoming_request_exists"),
                )
                return@post
            }
            if (newSuspendedTransaction {
                    FriendRequests.selectAll().where {
                        (FriendRequests.fromUserId eq uid) and
                            (FriendRequests.toUserId eq toId) and
                            (FriendRequests.status eq "pending")
                    }.any()
                }
            ) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "request_already_sent"))
                return@post
            }
            val now = OffsetDateTime.now()
            try {
                newSuspendedTransaction {
                    FriendRequests.insert {
                        it[FriendRequests.fromUserId] = uid
                        it[FriendRequests.toUserId] = toId
                        it[FriendRequests.status] = "pending"
                        it[FriendRequests.createdAt] = now
                        it[FriendRequests.respondedAt] = null
                    }
                }
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "request_exists"))
                return@post
            }
            insertNotification(
                recipientId = toId,
                actorId = uid,
                type = NOTIFICATION_FRIEND_REQUEST,
                entityRef = "friend_request",
                entityId = uid,
            )
            call.respond(HttpStatusCode.Created, mapOf("status" to "ok"))
        }

        get("/friends") {
            val userId = call.currentUserId() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val uid = userId.toLong()
            val list = newSuspendedTransaction {
                val rows = Friendships.selectAll().where {
                    (Friendships.userLow eq uid) or (Friendships.userHigh eq uid)
                }.toList()
                val otherIds = rows.map { row ->
                    if (row[Friendships.userLow] == uid) row[Friendships.userHigh] else row[Friendships.userLow]
                }.distinct()
                if (otherIds.isEmpty()) return@newSuspendedTransaction emptyList()
                val names = Users.selectAll().where { Users.id inList otherIds }
                    .associate { it[Users.id] to it[Users.nickname] }
                val onlineThreshold = OffsetDateTime.now().minusSeconds(20)
                val onlineUserIds = UserPresence.selectAll()
                    .where {
                        (UserPresence.userId inList otherIds) and
                            (UserPresence.lastSeenAt greaterEq onlineThreshold)
                    }
                    .map { it[UserPresence.userId] }
                    .toSet()
                val freshNowPlayingThreshold = OffsetDateTime.now().minusSeconds(120)
                val playingByUser = if (onlineUserIds.isEmpty()) {
                    emptyMap()
                } else {
                    UserNowPlaying.selectAll()
                        .where {
                            (UserNowPlaying.userId inList onlineUserIds.toList()) and
                                (UserNowPlaying.updatedAt greaterEq freshNowPlayingThreshold)
                        }
                        .associateBy { it[UserNowPlaying.userId] }
                }
                val trackIds = playingByUser.values.mapNotNull { it[UserNowPlaying.trackId] }.distinct()
                val trackRows = if (trackIds.isEmpty()) {
                    emptyMap()
                } else {
                    Tracks.selectAll().where { Tracks.id inList trackIds }
                        .associate { r ->
                            r[Tracks.id] to Pair(
                                r[Tracks.title],
                                r[Tracks.artists].primaryArtist().ifBlank { null },
                            )
                        }
                }
                otherIds.map { oid ->
                    val online = onlineUserIds.contains(oid)
                    val np = playingByUser[oid]?.let { prow ->
                        val tid = prow[UserNowPlaying.trackId] ?: return@let null
                        trackRows[tid]?.let { (title, artist) ->
                            NowPlayingRemote(
                                trackId = tid.toInt(),
                                title = title,
                                artist = artist,
                            )
                        }
                    }
                    FriendRemote(
                        id = oid.toInt(),
                        username = names[oid].orEmpty(),
                        online = online,
                        nowPlaying = np,
                        activeColistenRoomId = ColistenRoomManager.getActiveRoomIdForUser(oid.toInt()),
                    )
                }
            }
            call.respond(list)
        }

        delete("/friends/{friendId}") {
            val userId = call.currentUserId() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@delete
            }
            val uid = userId.toLong()
            val friendId = call.parameters["friendId"]?.toLongOrNull()
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, "Invalid friendId")
                    return@delete
                }
            val (l, h) = canonicalPair(uid, friendId)
            val removed = newSuspendedTransaction {
                Friendships.deleteWhere {
                    (Friendships.userLow eq l) and (Friendships.userHigh eq h)
                }
            }
            if (removed == 0) {
                call.respond(HttpStatusCode.NotFound, "Not friends with this user")
                return@delete
            }
            newSuspendedTransaction {
                FriendRequests.deleteWhere {
                    ((FriendRequests.fromUserId eq uid) and (FriendRequests.toUserId eq friendId)) or
                        ((FriendRequests.fromUserId eq friendId) and (FriendRequests.toUserId eq uid))
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
