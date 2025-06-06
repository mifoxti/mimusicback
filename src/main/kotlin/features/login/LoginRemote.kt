package com.example.features.login

import kotlinx.serialization.Serializable

@Serializable
data class LoginReceiveRemote(
    val login: String,
    val password: String,
)

@Serializable
data class LoginResponseRemote(
    val token: String,
    val id: Int,
)

