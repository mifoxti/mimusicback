package com.example.features.charts

import kotlinx.serialization.Serializable

@Serializable
data class ChartTrackRemote(
    val rank: Int,
    val trackId: Int,
    val title: String,
    val artist: String?,
    val playCount: Int,
    val cover: String? = null,
    val isNew: Boolean = false,
)
