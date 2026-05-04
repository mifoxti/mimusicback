package com.example.colisten

import com.example.database.AuthSessions
import com.example.database.FriendRequests
import com.example.utils.sha256Hex
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

fun Application.configureColistenWebSocket() {
    install(WebSockets)
    routing {
        webSocket("/ws/room/{roomId}") {
            val roomId = call.parameters["roomId"] ?: run {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing roomId"))
                return@webSocket
            }
            val token = call.request.queryParameters["token"]
            if (token.isNullOrBlank()) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing token"))
                return@webSocket
            }
            val userId = newSuspendedTransaction {
                AuthSessions.selectAll().where { AuthSessions.tokenHash eq sha256Hex(token) }
                    .map { it[AuthSessions.userId] }
                    .firstOrNull()
                    ?.toInt()
            }
            if (userId == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid token"))
                return@webSocket
            }
            val state = ColistenRoomManager.getState(roomId)
            if (state == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Room not found"))
                return@webSocket
            }
            val uid = userId.toLong()
            val ownerId = state.ownerId.toLong()
            val isFriendOrOwner = state.ownerId == userId || newSuspendedTransaction {
                FriendRequests.selectAll().where {
                    (FriendRequests.status eq "accepted") and
                        (((FriendRequests.fromUserId eq ownerId) and (FriendRequests.toUserId eq uid)) or
                            ((FriendRequests.fromUserId eq uid) and (FriendRequests.toUserId eq ownerId)))
                }.any()
            }
            if (!isFriendOrOwner) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Only friends can join"))
                return@webSocket
            }
            val joined = ColistenRoomManager.joinRoom(roomId, userId) { msg ->
                send(Frame.Text(msg))
            }
            if (!joined) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Could not join room"))
                return@webSocket
            }
            try {
                state.let { s -> broadcast(roomId, s) }
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            val msg = parseClientMessage(text) ?: continue
                            val current = ColistenRoomManager.getState(roomId) ?: continue
                            val updated = when (msg.type) {
                                "play" -> current.copy(playing = true)
                                "pause" -> current.copy(playing = false)
                                "seek" -> current.copy(positionSeconds = msg.position ?: current.positionSeconds)
                                "change_track" -> current.copy(
                                    trackId = msg.trackId,
                                    positionSeconds = 0.0,
                                    playing = true
                                )
                                else -> current
                            }
                            ColistenRoomManager.setState(roomId, updated)
                            broadcast(roomId, updated)
                        }
                        else -> {}
                    }
                }
            } finally {
                ColistenRoomManager.leaveRoom(roomId, userId)
            }
        }
    }
}

private suspend fun broadcast(roomId: String, state: RoomState) {
    ColistenRoomManager.broadcast(roomId, stateToJson(state))
}
