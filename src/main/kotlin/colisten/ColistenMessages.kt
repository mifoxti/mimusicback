package com.example.colisten

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Входящее сообщение от клиента (type + опциональные поля). */
@Serializable
data class ColistenClientMessage(
    val type: String,
    val position: Double? = null,
    val playing: Boolean? = null,
    val trackId: Int? = null,
    val trackKey: String? = null,
    val queueTrackIds: List<Int>? = null,
    val queueTrackKeys: List<String>? = null,
    val privateRoom: Boolean? = null,
    val controlPauseHostOnly: Boolean? = null,
    val controlSeekHostOnly: Boolean? = null,
    val controlShuffleHostOnly: Boolean? = null,
    val controlRepeatHostOnly: Boolean? = null,
    val controlSkipHostOnly: Boolean? = null,
    val controlPlaylistHostOnly: Boolean? = null,
)

/** Сообщение состояния комнаты от сервера. */
@Serializable
data class ColistenStateMessage(
    val type: String = "state",
    val roomId: String,
    val ownerId: Int,
    val isOpen: Boolean = false,
    val trackId: Int?,
    val trackKey: String? = null,
    val queueTrackIds: List<Int> = emptyList(),
    val queueTrackKeys: List<String> = emptyList(),
    val positionSeconds: Double,
    val playing: Boolean,
    val controlPauseHostOnly: Boolean = true,
    val controlSeekHostOnly: Boolean = true,
    val controlShuffleHostOnly: Boolean = true,
    val controlRepeatHostOnly: Boolean = true,
    val controlSkipHostOnly: Boolean = true,
    val controlPlaylistHostOnly: Boolean = true,
    val participantIds: List<Int>,
    val stateVersion: Long = 0L,
    val wallClockMs: Long = 0L,
)

private val json = Json { ignoreUnknownKeys = true }

fun parseClientMessage(raw: String): ColistenClientMessage? = try {
    json.decodeFromString<ColistenClientMessage>(raw)
} catch (_: Exception) {
    null
}

fun stateToJson(state: RoomState): String =
    json.encodeToString(
        ColistenStateMessage(
            roomId = state.roomId,
            ownerId = state.ownerId,
            isOpen = state.isOpen,
            trackId = state.trackId,
            trackKey = state.trackKey,
            queueTrackIds = state.queueTrackIds,
            queueTrackKeys = state.queueTrackKeys,
            positionSeconds = state.positionSeconds,
            playing = state.playing,
            controlPauseHostOnly = state.controlPauseHostOnly,
            controlSeekHostOnly = state.controlSeekHostOnly,
            controlShuffleHostOnly = state.controlShuffleHostOnly,
            controlRepeatHostOnly = state.controlRepeatHostOnly,
            controlSkipHostOnly = state.controlSkipHostOnly,
            controlPlaylistHostOnly = state.controlPlaylistHostOnly,
            participantIds = state.participantIds,
            stateVersion = state.stateVersion,
            wallClockMs = state.wallClockMs,
        ),
    )
