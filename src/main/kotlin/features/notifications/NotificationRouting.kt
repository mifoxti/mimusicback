package com.example.features.notifications

import com.example.database.Notifications
import com.example.database.Users
import com.example.utils.currentUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

private const val TYPE_FRIEND_REQUEST = 10
private const val TYPE_FRIEND_ACCEPTED = 11
private const val TYPE_COLISTEN_INVITE = 20

private fun typeToString(t: Int): String = when (t) {
    TYPE_FRIEND_REQUEST -> "friend_request"
    TYPE_FRIEND_ACCEPTED -> "friend_accepted"
    TYPE_COLISTEN_INVITE -> "colisten_invite"
    else -> "unknown_$t"
}

fun Application.configureNotificationRouting() {
    routing {
        get("/notifications") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val unreadOnly = call.request.queryParameters["unreadOnly"]?.equals("true", ignoreCase = true) == true
            val list = newSuspendedTransaction {
                val rows = Notifications.selectAll()
                    .where {
                        val forMe = Notifications.recipientUserId eq uid
                        if (unreadOnly) forMe and Notifications.readAt.isNull()
                        else forMe
                    }
                    .orderBy(Notifications.id, SortOrder.DESC)
                    .limit(limit)
                    .toList()
                val actorIds = rows.mapNotNull { it[Notifications.actorUserId] }.distinct()
                val nickById = if (actorIds.isEmpty()) {
                    emptyMap()
                } else {
                    Users.selectAll().where { Users.id inList actorIds }
                        .associate { r -> r[Users.id] to r[Users.nickname] }
                }
                rows.map { row ->
                    val aid = row[Notifications.actorUserId]
                    NotificationRemote(
                        id = row[Notifications.id],
                        type = typeToString(row[Notifications.type]),
                        actorUserId = aid?.toInt(),
                        actorNickname = aid?.let { nickById[it] },
                        read = row[Notifications.readAt] != null,
                        createdAt = row[Notifications.createdAt]?.toString(),
                        entityRef = row[Notifications.entityRef],
                        entityId = row[Notifications.entityId],
                        payloadJson = row[Notifications.payloadJson],
                    )
                }
            }
            call.respond(list)
        }

        get("/notifications/unread-count") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val count = newSuspendedTransaction {
                Notifications.selectAll()
                    .where {
                        (Notifications.recipientUserId eq uid) and Notifications.readAt.isNull()
                    }
                    .count()
            }
            call.respond(mapOf("count" to count.toInt()))
        }

        post("/notifications/{id}/read") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val nid = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid id")
                return@post
            }
            val updated = newSuspendedTransaction {
                Notifications.update({
                    (Notifications.id eq nid) and (Notifications.recipientUserId eq uid)
                }) {
                    it[Notifications.readAt] = OffsetDateTime.now()
                }
            }
            if (updated == 0) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/notifications/read-all") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            newSuspendedTransaction {
                Notifications.update({
                    (Notifications.recipientUserId eq uid) and Notifications.readAt.isNull()
                }) {
                    it[Notifications.readAt] = OffsetDateTime.now()
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
