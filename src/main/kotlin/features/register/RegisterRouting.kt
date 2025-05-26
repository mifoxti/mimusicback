package com.example.features.register

import com.example.cache.InMemoryCache
import com.example.cache.TokenCache
import com.example.utils.isValidEmail
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

fun Application.configureRegisterRouting() {
    routing {
        post("/register") {
            val receive = call.receive(RegisterReceiveRemote::class)
            if (!receive.email.isValidEmail()) {
                call.respond(HttpStatusCode.BadRequest, "Invalid email")
            }

            if (InMemoryCache.userList.map { it.login }.contains(receive.login)) {
                call.respond(HttpStatusCode.Conflict, "User already exists")
            }

            val token = UUID.randomUUID().toString()
            InMemoryCache.userList.add(receive)
            InMemoryCache.token.add(TokenCache(login = receive.login, token = token))
            call.respond(RegisterResponseRemote(token = token))
            return@post
        }
    }
}

