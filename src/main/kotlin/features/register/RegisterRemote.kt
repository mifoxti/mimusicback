package com.example.features.register

import kotlinx.serialization.Serializable

/** [login] — уникальный **никнейм** (как в UI регистрации). */
@Serializable
data class RegisterReceiveRemote(
    val login: String,
    val email: String? = null,
    val password: String,
    val inviteCode: String? = null,
)

@Serializable
data class RegisterResponseRemote(
    val token: String,
    val id: Int,
    val email: String? = null,
    val nickname: String,
)
