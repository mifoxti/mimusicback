package com.example.features.likes

import com.example.database.TrackLikes
import com.example.features.like.ToggleLikeRequest
import com.example.features.like.ToggleLikeResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

fun Application.configureLikeRouting() {
    routing {
        post("/tracks/{trackId}/like") {
            println("Получен запрос")
            val trackId = call.parameters["trackId"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid track ID")

            val request = try {
                call.receive<ToggleLikeRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")
            }

            try {
                val result = newSuspendedTransaction {
                    val uid = request.userId.toLong()
                    val alreadyLiked = TrackLikes.selectAll().where {
                        (TrackLikes.userId eq uid) and (TrackLikes.trackId eq trackId)
                    }.any()

                    if (alreadyLiked) {
                        TrackLikes.deleteWhere {
                            (TrackLikes.userId eq uid) and (TrackLikes.trackId eq trackId)
                        }
                        ToggleLikeResponse(false)
                    } else {
                        TrackLikes.insert {
                            it[TrackLikes.userId] = uid
                            it[TrackLikes.trackId] = trackId
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
            val trackId = call.parameters["trackId"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid track ID")

            val userId = call.request.queryParameters["userId"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing userId")

            val isLiked = newSuspendedTransaction {
                TrackLikes.selectAll().where {
                    (TrackLikes.userId eq userId) and (TrackLikes.trackId eq trackId)
                }.any()
            }

            call.respond(HttpStatusCode.OK, ToggleLikeResponse(status = isLiked))
        }
    }
}
