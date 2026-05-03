package com.example.features.tracks

import kotlinx.serialization.Serializable

@Serializable
data class TrackRemote(
    val id: Int,
    val title: String,
    val artist: String?,
    val duration: Int?,
    val cover: String?
)