package com.example.colisten

import com.example.database.Tracks
import com.example.database.Users
import com.example.database.Notifications
import com.example.utils.currentUserId
import com.example.utils.primaryArtist
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime

@Serializable
data class ColistenCreateRoomReceive(
    val isOpen: Boolean = false,
    val trackId: Int? = null,
    val trackKey: String? = null,
    val queueTrackKeys: List<String> = emptyList(),
    val positionSeconds: Double = 0.0,
    val playing: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: String = "off",
    val controlPauseHostOnly: Boolean = true,
    val controlSeekHostOnly: Boolean = true,
    val controlShuffleHostOnly: Boolean = true,
    val controlRepeatHostOnly: Boolean = true,
    val controlSkipHostOnly: Boolean = true,
    val controlPlaylistHostOnly: Boolean = true,
)

@Serializable
data class OpenRoomSummaryRemote(
    val roomId: String,
    val ownerId: Int,
    val ownerNickname: String,
    val trackId: Int?,
    val trackTitle: String?,
    val trackArtist: String?,
    val positionSeconds: Double,
    val playing: Boolean,
    val listenersCount: Int,
    val stateVersion: Long,
    val wallClockMs: Long,
)

@Serializable
data class ColistenInviteUsersReceive(
    val userIds: List<Int> = emptyList(),
)

fun Application.configureColistenRouting() {
    routing {
        post("/colisten/room") {
            val userId = call.currentUserId()?.toInt() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val body = try {
                call.receive<ColistenCreateRoomReceive>()
            } catch (_: Exception) {
                ColistenCreateRoomReceive()
            }
            val roomId = ColistenRoomManager.createRoom(
                ownerId = userId,
                isOpen = body.isOpen,
                trackId = body.trackId,
                trackKey = body.trackKey,
                queueTrackKeys = body.queueTrackKeys.distinct(),
                positionSeconds = body.positionSeconds,
                playing = body.playing,
                shuffleEnabled = body.shuffleEnabled,
                repeatMode = body.repeatMode,
                controlPauseHostOnly = true,
                controlSeekHostOnly = true,
                controlShuffleHostOnly = true,
                controlRepeatHostOnly = true,
                controlSkipHostOnly = true,
                controlPlaylistHostOnly = body.controlPlaylistHostOnly,
            )
            println(
                "[colisten] REST create_room room=$roomId owner=$userId trackId=${body.trackId} trackKey=${body.trackKey} pos=${body.positionSeconds} playing=${body.playing} shuffle=${body.shuffleEnabled} repeat=${body.repeatMode} queueKeys=${body.queueTrackKeys}",
            )
            call.respond(mapOf("roomId" to roomId))
        }

        get("/colisten/room/{roomId}") {
            val roomId = call.parameters["roomId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Missing roomId")
                return@get
            }
            val state = ColistenRoomManager.getState(roomId)
            if (state == null) {
                call.respond(HttpStatusCode.NotFound, "Room not found")
                return@get
            }
            println(
                "[colisten] REST get_room room=$roomId owner=${state.ownerId} v=${state.stateVersion} trackId=${state.trackId} trackKey=${state.trackKey} pos=${state.positionSeconds} playing=${state.playing} shuffle=${state.shuffleEnabled} repeat=${state.repeatMode} participants=${state.participantIds}",
            )
            call.respond(state)
        }

        post("/colisten/room/{roomId}/host-state") {
            val userId = call.currentUserId()?.toInt() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val roomId = call.parameters["roomId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Missing roomId")
                return@post
            }
            val state = ColistenRoomManager.getState(roomId)
            if (state == null) {
                call.respond(HttpStatusCode.NotFound, "Room not found")
                return@post
            }
            if (state.ownerId != userId && userId !in state.participantIds) {
                call.respond(HttpStatusCode.Forbidden, "Only room participants can update room state")
                return@post
            }
            val body = try {
                call.receive<ColistenClientMessage>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid body")
                return@post
            }
            if (body.type == "command" && userId != state.ownerId) {
                val command = buildRemoteGuestCommand(roomId, body, userId)
                if (command == null) {
                    call.respond(state)
                    return@post
                }
                val sent = ColistenRoomManager.sendToUser(
                    roomId,
                    state.ownerId,
                    clientMessageToJson(command),
                )
                println(
                    "[colisten] REST remote_command room=$roomId sender=$userId owner=${state.ownerId} sent=$sent",
                )
                call.respond(state)
                return@post
            }
            val updated = when (body.type) {
                "command" -> applyHostStateMessage(roomId, body, userId)
                else -> applyHostStateMessage(roomId, body, userId)
            }
            if (updated == null) {
                call.respond(HttpStatusCode.NotFound, "Room not found")
                return@post
            }
            println(
                "[colisten] REST ${body.type} room=$roomId user=$userId v=${updated.stateVersion} trackId=${updated.trackId} trackKey=${updated.trackKey} pos=${updated.positionSeconds} playing=${updated.playing} shuffle=${updated.shuffleEnabled} repeat=${updated.repeatMode} queueKeys=${updated.queueTrackKeys}",
            )
            ColistenRoomManager.broadcast(roomId, stateToJson(updated))
            call.respond(updated)
        }

        get("/colisten/rooms/open") {
            val states = ColistenRoomManager.listOpenRoomStates()
            if (states.isEmpty()) {
                call.respond(emptyList<OpenRoomSummaryRemote>())
                return@get
            }
            val ownerIds = states.map { it.ownerId.toLong() }.distinct()
            val trackIds = states.mapNotNull { it.trackId?.toLong() }.distinct()
            val nickById = newSuspendedTransaction {
                if (ownerIds.isEmpty()) emptyMap()
                else {
                    Users.selectAll().where { Users.id inList ownerIds }
                        .associate { it[Users.id].toInt() to it[Users.nickname] }
                }
            }
            val trackMeta = newSuspendedTransaction {
                if (trackIds.isEmpty()) emptyMap()
                else {
                    Tracks.selectAll().where { Tracks.id inList trackIds }
                        .associate {
                            it[Tracks.id].toInt() to Pair(
                                it[Tracks.title],
                                it[Tracks.artists].primaryArtist().ifBlank { null },
                            )
                        }
                }
            }
            val out = states.map { s ->
                val tm = s.trackId?.let { trackMeta[it] }
                OpenRoomSummaryRemote(
                    roomId = s.roomId,
                    ownerId = s.ownerId,
                    ownerNickname = nickById[s.ownerId].orEmpty(),
                    trackId = s.trackId,
                    trackTitle = tm?.first,
                    trackArtist = tm?.second,
                    positionSeconds = s.positionSeconds,
                    playing = s.playing,
                    listenersCount = s.participantIds.size,
                    stateVersion = s.stateVersion,
                    wallClockMs = s.wallClockMs,
                )
            }
            call.respond(out)
        }

        post("/colisten/room/{roomId}/invite") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val roomId = call.parameters["roomId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Missing roomId")
                return@post
            }
            val body = try {
                call.receive<ColistenInviteUsersReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid body")
                return@post
            }
            val state = ColistenRoomManager.getState(roomId) ?: run {
                call.respond(HttpStatusCode.NotFound, "Room not found")
                return@post
            }
            if (state.ownerId.toLong() != uid) {
                call.respond(HttpStatusCode.Forbidden, "Only host can invite")
                return@post
            }
            val targets = body.userIds.map { it.toLong() }
                .filter { it != uid }
                .distinct()
            if (targets.isEmpty()) {
                call.respond(mapOf("invited" to 0))
                return@post
            }
            val invited = newSuspendedTransaction {
                val existingTargets = Users.selectAll()
                    .where { Users.id inList targets }
                    .map { it[Users.id] }
                    .distinct()
                var count = 0
                existingTargets.forEach { fid ->
                    Notifications.insert {
                        it[recipientUserId] = fid
                        it[actorUserId] = uid
                        it[type] = 20
                        it[entityRef] = "colisten_room_invite:$roomId"
                        it[entityId] = null
                        it[payloadJson] = null
                        it[readAt] = null
                        it[createdAt] = OffsetDateTime.now()
                    }
                    count++
                }
                count
            }
            call.respond(mapOf("invited" to invited))
        }
    }
}
