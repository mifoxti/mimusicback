package com.example.features.likes

import com.example.database.UserTracks
import com.example.features.like.ToggleLikeRequest
import com.example.features.like.ToggleLikeResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction


fun Application.configureLikeRouting() {
    routing {
        post("/tracks/{trackId}/like") {
            println("Получен запрос")
            val trackId = call.parameters["trackId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid track ID")

            val request = try {
                call.receive<ToggleLikeRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")
            }

            try {
                val result = newSuspendedTransaction {
                    val alreadyLiked = UserTracks.selectAll().where {
                        (UserTracks.userIduser eq request.userId) and
                                (UserTracks.trackIdtrack eq trackId)
                    }.any()

                    if (alreadyLiked) {
                        UserTracks.deleteWhere {
                            (UserTracks.userIduser eq request.userId) and
                                    (UserTracks.trackIdtrack eq trackId)
                        }
                        ToggleLikeResponse(false)
                    } else {
                        UserTracks.insert {
                            it[userIduser] = request.userId
                            it[trackIdtrack] = trackId
                        }
                        ToggleLikeResponse(true)
                    }
                }

                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                call.application.environment.log.error("Toggle like error", e)
                call.respond(HttpStatusCode.InternalServerError, "Toggle like failed")
            }
        }

        get("/tracks/{trackId}/like") {
            val trackId = call.parameters["trackId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid track ID")

            val userId = call.request.queryParameters["userId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing userId")

            val isLiked = newSuspendedTransaction {
                UserTracks.selectAll().where {
                    (UserTracks.userIduser eq userId) and (UserTracks.trackIdtrack eq trackId)
                }.any()
            }

            call.respond(HttpStatusCode.OK, ToggleLikeResponse(status = isLiked))
        }
    }
}
