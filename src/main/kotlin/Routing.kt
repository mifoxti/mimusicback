package com.example

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respond(Test(name = "Hello World!"))
        }
    }
}

@Serializable
data class Test(
    val name: String,
)