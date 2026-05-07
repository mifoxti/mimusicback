package com.example.features.upload

import kotlinx.serialization.Serializable

@Serializable
data class UploadTrackResponseRemote(
    val trackId: Long,
    val audioStorageKey: String,
    val title: String,
    /** Основной исполнитель (список из одного элемента в БД). */
    val artist: String? = null,
    val durationSec: Int?,
    val coverStorageKey: String? = null,
    /** Обложка сохранена из multipart `cover`. */
    val customCoverApplied: Boolean = false,
    /** Обложка извлечена из MP3 и сохранена на диск (если своя не была передана). */
    val embeddedCoverApplied: Boolean = false,
)

@Serializable
data class UploadAvatarResponseRemote(
    val avatarStorageKey: String,
)

@Serializable
data class UploadedCoverResponseRemote(
    val coverStorageKey: String,
)
