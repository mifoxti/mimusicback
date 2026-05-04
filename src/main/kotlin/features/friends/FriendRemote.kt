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
data class FriendRemote(val id: Int, val username: String)
