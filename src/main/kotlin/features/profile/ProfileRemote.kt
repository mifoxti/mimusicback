package com.example.features.profile

import kotlinx.serialization.Serializable

@Serializable
data class MeResponseRemote(
    val id: Int,
    val email: String? = null,
    val nickname: String,
    val bio: String? = null,
)

@Serializable
data class MePatchReceiveRemote(
    val nickname: String? = null,
    val email: String? = null,
)
