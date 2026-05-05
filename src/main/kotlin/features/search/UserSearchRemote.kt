package com.example.features.search

import kotlinx.serialization.Serializable

@Serializable
data class UserSearchResultRemote(
    val id: Int,
    val nickname: String,
)

@Serializable
data class NicknameAvailableRemote(
    val available: Boolean,
)
