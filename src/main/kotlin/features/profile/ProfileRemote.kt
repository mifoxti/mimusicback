package com.example.features.profile

import kotlinx.serialization.Serializable

@Serializable
data class MeResponseRemote(
    val id: Int,
    val email: String? = null,
    val nickname: String,
    val bio: String? = null,
    /** Относительный путь в `FILE_STORAGE_ROOT`, например `avatars/42.png`. */
    val avatarStorageKey: String? = null,
)

@Serializable
data class MePatchReceiveRemote(
    val nickname: String? = null,
    val email: String? = null,
    val bio: String? = null,
)

@Serializable
data class MePasswordChangeReceiveRemote(
    val currentPassword: String,
    val newPassword: String,
)

@Serializable
data class MeInviteKeyReceiveRemote(
    /** Если null или пусто — сервер сгенерирует новый ключ в формате `AAAAA-AAAAA-AAAAA`. */
    val keyCode: String? = null,
)

@Serializable
data class MeInviteKeyResponseRemote(
    val keyCode: String,
)

@Serializable
data class MeNowPlayingPutReceive(
    /** Если null — очистить «сейчас слушает». */
    val trackId: Int? = null,
)

@Serializable
data class MeStatsResponseRemote(
    val tracksCount: Int,
    val playlistsCount: Int,
    val friendsCount: Int,
)
