package com.example.features.login

import com.example.cache.InMemoryCache
import com.example.cache.TokenCache
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

fun Application.configureLoginRouting() {
    routing {
        post("/login") {
            val receive = call.receive(LoginReceiveRemote::class)
            val first = InMemoryCache.userList.firstOrNull { it.login == receive.login }

            if (first == null) {
                call.respond(HttpStatusCode.BadRequest, "Login not found")
            } else {
                if (first.password == receive.password) {
                    if (InMemoryCache.userList.map { it.login }.contains(receive.login)) {
                        val token = UUID.randomUUID().toString()
                        InMemoryCache.token.add(TokenCache(login = receive.login, token = token))
                        call.respond(LoginResponseRemote(token = token))
                        return@post
                    } else {
                        call.respond(HttpStatusCode.BadRequest)
                    }
                } else {
                    call.respond(HttpStatusCode.BadRequest, "Wrong password")
                }
            }
        }
    }
}
