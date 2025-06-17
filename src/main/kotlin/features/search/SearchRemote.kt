package com.example.features.search

import kotlinx.serialization.Serializable

@Serializable
data class SearchRemote(
    val id: Int,
    val title: String,
    val artist: String?,
    val duration: Int?,
    val coverArt: String?,
)