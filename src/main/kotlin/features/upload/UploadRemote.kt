package com.example.features.upload

import kotlinx.serialization.Serializable

@Serializable
data class UploadTrackResponseRemote(
    val trackId: Long,
    val audioStorageKey: String,
    val title: String,
    val durationSec: Int?,
)

@Serializable
data class UploadAvatarResponseRemote(
    val avatarStorageKey: String,
)

@Serializable
data class UploadedCoverResponseRemote(
    val coverStorageKey: String,
)
