package com.example.features.friends

import com.example.database.Friends
import com.example.database.Users
import com.example.utils.currentUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

fun Application.configureFriendRouting() {
    routing {
        get("/friends") {
            val userId = call.currentUserId()
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val list = newSuspendedTransaction {
                Friends.innerJoin(Users) { Friends.friendId eq Users.id }
                    .selectAll()
                    .where { Friends.userId eq userId }
                    .map {
                        FriendRemote(
                            id = it[Users.id],
                            username = it[Users.username]
                        )
                    }
            }
            call.respond(list)
        }

        post("/friends") {
            val userId = call.currentUserId()
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val body = try {
                call.receive<AddFriendRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid body: expected { friendId: Int }")
                return@post
            }
            val friendId = body.friendId
            if (friendId == userId) {
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
            newSuspendedTransaction {
                Friends.insert {
                    it[Friends.userId] = userId
                    it[Friends.friendId] = friendId
                }
                // Двусторонняя дружба: второй пользователь тоже «в друзьях» у первого
                try {
                    Friends.insert {
                        it[Friends.userId] = friendId
                        it[Friends.friendId] = userId
                    }
                } catch (_: Exception) { /* уже есть */ }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        delete("/friends/{friendId}") {
            val userId = call.currentUserId()
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@delete
            }
            val friendId = call.parameters["friendId"]?.toIntOrNull()
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, "Invalid friendId")
                    return@delete
                }
            newSuspendedTransaction {
                Friends.deleteWhere {
                    (Friends.userId eq userId) and (Friends.friendId eq friendId)
                }
                Friends.deleteWhere {
                    (Friends.userId eq friendId) and (Friends.friendId eq userId)
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
