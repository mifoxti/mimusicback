package com.example.colisten

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** Входящее сообщение от клиента (type + опциональные поля). */
@Serializable
data class ColistenClientMessage(
    val type: String,
    val position: Double? = null,
    val trackId: Int? = null
)

/** Сообщение состояния комнаты от сервера. */
@Serializable
data class ColistenStateMessage(
    val type: String = "state",
    val roomId: String,
    val trackId: Int?,
    val positionSeconds: Double,
    val playing: Boolean,
    val participantIds: List<Int>
)

private val json = Json { ignoreUnknownKeys = true }

fun parseClientMessage(raw: String): ColistenClientMessage? = try {
    json.decodeFromString<ColistenClientMessage>(raw)
} catch (_: Exception) { null }

fun stateToJson(state: RoomState): String =
    json.encodeToString(
        ColistenStateMessage(
            roomId = state.roomId,
            trackId = state.trackId,
            positionSeconds = state.positionSeconds,
            playing = state.playing,
            participantIds = state.participantIds
        )
    )
