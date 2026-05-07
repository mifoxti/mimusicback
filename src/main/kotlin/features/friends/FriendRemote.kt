package com.example.features.friends

import kotlinx.serialization.Serializable

/** Тело `POST /friends` (совместимость): добавить в друзья = отправить заявку. */
@Serializable
data class AddFriendRequest(val friendId: Int)

@Serializable
data class FriendRequestCreateDto(val toUserId: Int)

@Serializable
data class FriendIncomingDto(
    val fromUserId: Int,
    val nickname: String,
    val createdAt: String?,
)

@Serializable
data class NowPlayingRemote(
    val trackId: Int,
    val title: String,
    val artist: String? = null,
)

@Serializable
data class FriendRemote(
    val id: Int,
    val username: String,
    val online: Boolean = false,
    val nowPlaying: NowPlayingRemote? = null,
    /** Активная комната Colisten, если друг сейчас в ней (in-memory на сервере). */
    val activeColistenRoomId: String? = null,
)
