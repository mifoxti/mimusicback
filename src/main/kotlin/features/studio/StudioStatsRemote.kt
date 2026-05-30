package com.example.features.studio

import kotlinx.serialization.Serializable

@Serializable
data class PlaysByDayRemote(
    val date: String,
    val count: Int,
)

@Serializable
data class StudioTopTrackRemote(
    val trackId: Int,
    val title: String,
    val playCount: Int,
)

@Serializable
data class MeStudioStatsRemote(
    val totalPlays: Int,
    val totalTracks: Int,
    val uniqueListeners: Int,
    val playsByDay: List<PlaysByDayRemote>,
    val topTracks: List<StudioTopTrackRemote>,
)

@Serializable
data class TrackStudioStatsRemote(
    val trackId: Int,
    val title: String,
    val artist: String?,
    val totalPlays: Int,
    val uniqueListeners: Int,
    val playsByDay: List<PlaysByDayRemote>,
)
