package com.example.colisten

import com.example.database.AuthSessions
import com.example.database.Friendships
import com.example.utils.sha256Hex
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

private suspend fun areFriends(a: Long, b: Long): Boolean {
    val (l, h) = if (a < b) a to b else b to a
    return newSuspendedTransaction {
        Friendships.selectAll().where {
            (Friendships.userLow eq l) and (Friendships.userHigh eq h)
        }.any()
    }
}

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
            val isOwner = state.ownerId == userId
            val allowed = isOwner || areFriends(uid, ownerId)
            if (!allowed) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Only friends can join"))
                return@webSocket
            }
            val joinedState = ColistenRoomManager.joinRoom(roomId, userId) { msg ->
                send(Frame.Text(msg))
            }
            if (joinedState == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Could not join room"))
                return@webSocket
            }
            try {
                broadcast(roomId, joinedState)
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            val msg = parseClientMessage(text) ?: continue
                            val current = ColistenRoomManager.getState(roomId) ?: continue
                            val isOwner = userId == current.ownerId
                            val allowed = when (msg.type) {
                                "host_state" -> isOwner
                                "update_settings" -> isOwner
                                else -> false
                            }
                            if (!allowed) {
                                continue
                            }
                            val updated = when (msg.type) {
                                "update_settings" -> ColistenRoomManager.updateState(roomId) {
                                    it.copy(
                                        isOpen = msg.privateRoom?.not() ?: it.isOpen,
                                        controlPauseHostOnly = msg.controlPauseHostOnly ?: it.controlPauseHostOnly,
                                        controlSeekHostOnly = msg.controlSeekHostOnly ?: it.controlSeekHostOnly,
                                        controlShuffleHostOnly = msg.controlShuffleHostOnly ?: it.controlShuffleHostOnly,
                                        controlRepeatHostOnly = msg.controlRepeatHostOnly ?: it.controlRepeatHostOnly,
                                        controlSkipHostOnly = msg.controlSkipHostOnly ?: it.controlSkipHostOnly,
                                        controlPlaylistHostOnly = msg.controlPlaylistHostOnly ?: it.controlPlaylistHostOnly,
                                    )
                                }
                                "host_state" -> ColistenRoomManager.updateState(roomId) {
                                    val normalized = msg.queueTrackIds
                                        ?.filter { it > 0 }
                                        ?.distinct()
                                        ?: it.queueTrackIds
                                    val normalizedKeys = msg.queueTrackKeys
                                        ?.map { key -> key.trim() }
                                        ?.filter { key -> key.isNotEmpty() }
                                        ?.distinct()
                                        ?: it.queueTrackKeys
                                    val nextTrackId = msg.trackId
                                        ?: normalized.firstOrNull()
                                        ?: it.trackId
                                    val nextTrackKey = msg.trackKey
                                        ?: normalizedKeys.firstOrNull()
                                        ?: it.trackKey
                                    it.copy(
                                        trackId = nextTrackId,
                                        trackKey = nextTrackKey,
                                        queueTrackIds = normalized,
                                        queueTrackKeys = normalizedKeys,
                                        positionSeconds = msg.position ?: it.positionSeconds,
                                        playing = msg.playing ?: it.playing,
                                    )
                                }
                                else -> null
                            }
                            if (updated != null) {
                                broadcast(roomId, updated)
                            }
                        }
                        else -> {}
                    }
                }
            } finally {
                val afterLeave = ColistenRoomManager.leaveRoom(roomId, userId)
                if (afterLeave != null) {
                    broadcast(roomId, afterLeave)
                }
            }
        }
    }
}

private suspend fun broadcast(roomId: String, state: RoomState) {
    ColistenRoomManager.broadcast(roomId, stateToJson(state))
}
