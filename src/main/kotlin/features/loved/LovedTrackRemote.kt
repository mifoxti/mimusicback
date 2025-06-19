package com.example.features.loved

import kotlinx.serialization.Serializable

@Serializable
data class LovedTrackRemote(
    val id: Int,
    val title: String,
    val artist: String?,
    val coverArt: String?
)
