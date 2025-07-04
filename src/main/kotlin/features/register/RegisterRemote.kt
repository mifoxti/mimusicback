package com.example.features.register

import kotlinx.serialization.Serializable

@Serializable
data class RegisterReceiveRemote (
    val login: String,
    val email: String? = null,
    val password: String,
)

@Serializable
data class RegisterResponseRemote (
    val token: String,
    val id: Int,
)
