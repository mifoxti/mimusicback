package com.example.colisten

import com.example.utils.currentUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureColistenRouting() {
    routing {
        /** Создать комнату совместного прослушивания. Требует Authorization: Bearer <token>. */
        post("/colisten/room") {
            val userId = call.currentUserId()
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val roomId = ColistenRoomManager.createRoom(userId)
            call.respond(mapOf("roomId" to roomId))
        }

        /** Получить состояние комнаты (для отображения до подключения по WebSocket). */
        get("/colisten/room/{roomId}") {
            val roomId = call.parameters["roomId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Missing roomId")
                return@get
            }
            val state = ColistenRoomManager.getState(roomId)
            if (state == null) {
                call.respond(HttpStatusCode.NotFound, "Room not found")
                return@get
            }
            call.respond(state)
        }
    }
}
