package com.example.features.friends

import kotlinx.serialization.Serializable

@Serializable
data class AddFriendRequest(val friendId: Int)

@Serializable
data class FriendRemote(val id: Int, val username: String)
