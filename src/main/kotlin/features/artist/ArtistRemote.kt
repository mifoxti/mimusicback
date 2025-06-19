package com.example.features.artist

import kotlinx.serialization.Serializable

@Serializable
data class ArtistSong(
    val id: Int,
    val title: String,
    val artist: String,
    val coverArt: String?,
    val isLiked: Boolean
)

@Serializable
data class ArtistResponse(
    val thoughts: String,
    val songs: List<ArtistSong>
)