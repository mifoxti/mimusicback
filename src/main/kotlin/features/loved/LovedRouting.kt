package com.example.features.loved

import com.example.database.Tracks
import com.example.database.UserTracks
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.*

fun Application.configureLovedTracksRouting() {
    routing {
        get("/users/{id}/loved") {
            val userId = call.parameters["id"]?.toIntOrNull()
                ?: throw BadRequestException("Invalid user ID")

            val lovedTracks = newSuspendedTransaction {
                (UserTracks innerJoin Tracks).selectAll().where {
                    UserTracks.userIduser eq userId
                }.map {
                    LovedTrackRemote(
                        id = it[Tracks.id],
                        title = it[Tracks.title],
                        artist = it[Tracks.artist],
                        coverArt = it[Tracks.coverArt]?.let { bytes ->
                            Base64.getEncoder().encodeToString(bytes)
                        }
                    )
                }
            }

            call.respond(lovedTracks)
        }
    }
}
