package com.example.features.like

import kotlinx.serialization.Serializable

@Serializable
data class ToggleLikeRequest(
    val userId: Int
)

@Serializable
data class ToggleLikeResponse(
    val status: Boolean
)
