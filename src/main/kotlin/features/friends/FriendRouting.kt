package com.example.features.friends

import com.example.database.FriendRequests
import com.example.database.Users
import com.example.utils.currentUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime

fun Application.configureFriendRouting() {
    routing {
        get("/friends") {
            val userId = call.currentUserId() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val uid = userId.toLong()
            val list = newSuspendedTransaction {
                val rows = FriendRequests.selectAll().where {
                    (FriendRequests.status eq "accepted") and
                        ((FriendRequests.fromUserId eq uid) or (FriendRequests.toUserId eq uid))
                }.toList()
                val otherIds = rows.map { fr ->
                    if (fr[FriendRequests.fromUserId] == uid) fr[FriendRequests.toUserId] else fr[FriendRequests.fromUserId]
                }.distinct()
                if (otherIds.isEmpty()) return@newSuspendedTransaction emptyList()
                val names = Users.selectAll().where { Users.id inList otherIds }
                    .associate { it[Users.id] to it[Users.nickname] }
                otherIds.map { oid ->
                    FriendRemote(
                        id = oid.toInt(),
                        username = names[oid].orEmpty(),
                    )
                }
            }
            call.respond(list)
        }

        post("/friends") {
            val userId = call.currentUserId() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val uid = userId.toLong()
            val body = try {
                call.receive<AddFriendRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid body: expected { friendId: Int }")
                return@post
            }
            val friendId = body.friendId.toLong()
            if (friendId == uid) {
                call.respond(HttpStatusCode.BadRequest, "Cannot add yourself as friend")
                return@post
            }
            val exists = newSuspendedTransaction {
                Users.selectAll().where { Users.id eq friendId }.any()
            }
            if (!exists) {
                call.respond(HttpStatusCode.NotFound, "User not found")
                return@post
            }
            val now = OffsetDateTime.now()
            newSuspendedTransaction {
                FriendRequests.insert {
                    it[FriendRequests.fromUserId] = uid
                    it[FriendRequests.toUserId] = friendId
                    it[FriendRequests.status] = "accepted"
                    it[FriendRequests.createdAt] = now
                    it[FriendRequests.respondedAt] = now
                }
                try {
                    FriendRequests.insert {
                        it[FriendRequests.fromUserId] = friendId
                        it[FriendRequests.toUserId] = uid
                        it[FriendRequests.status] = "accepted"
                        it[FriendRequests.createdAt] = now
                        it[FriendRequests.respondedAt] = now
                    }
                } catch (_: Exception) { /* уже есть симметричная строка */ }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
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
            newSuspendedTransaction {
                FriendRequests.deleteWhere {
                    (FriendRequests.fromUserId eq uid) and (FriendRequests.toUserId eq friendId) and (FriendRequests.status eq "accepted")
                }
                FriendRequests.deleteWhere {
                    (FriendRequests.fromUserId eq friendId) and (FriendRequests.toUserId eq uid) and (FriendRequests.status eq "accepted")
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
