package com.example.features.tracks

import com.example.database.Tracks
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.io.File
import java.util.*

fun Application.configureTrackRouting() {
    routing {
        get("/tracks") {
            val tracks = newSuspendedTransaction {
                Tracks.selectAll().map {
                    TrackRemote(
                        id = it[Tracks.id],
                        title = it[Tracks.title],
                        artist = it[Tracks.artist],
                        duration = it[Tracks.duration],
                        cover = it[Tracks.coverArt]?.let { bytes ->
                            Base64.getEncoder().encodeToString(bytes)
                        }
                    )
                }
            }
            call.respond(tracks)
        }

        get("/tracks/{id}/cover") {
            val trackId = call.parameters["id"]?.toIntOrNull() ?: throw BadRequestException("Invalid ID")

            val cover = newSuspendedTransaction {
                Tracks.selectAll().where { Tracks.id eq trackId }
                    .map { it[Tracks.coverArt] }
                    .firstOrNull()
            }

            if (cover != null) {
                call.respondBytes(cover, ContentType.Image.Any)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get("/tracks/{id}/stream") {
            val trackId = call.parameters["id"]?.toIntOrNull() ?: throw BadRequestException("Invalid ID")

            val filePath = newSuspendedTransaction {
                Tracks.selectAll().where { Tracks.id eq trackId }
                    .map { it[Tracks.path] }
                    .firstOrNull()
            }

            if (filePath != null) {
                val file = File(filePath)
                if (file.exists()) {
                    call.respondFile(file)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}
