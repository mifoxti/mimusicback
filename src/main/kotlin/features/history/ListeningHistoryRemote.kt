package com.example.features.history

import kotlinx.serialization.Serializable

@Serializable
data class ListenEventCreateReceive(
    val trackId: Long,
)

@Serializable
data class ListeningHistoryItemRemote(
    val trackId: Int,
    val title: String,
    val artist: String?,
    val playedAt: String,
)
