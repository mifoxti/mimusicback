package com.example.features.tracks

import com.example.database.DatabaseFactory
import com.example.database.Tracks
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import java.io.File

fun Application.configureTrackRouting() {
    routing {
        get("/tracks") {
            val tracks = DatabaseFactory.dbQuery {
                Tracks.selectAll().map {
                    TrackResponse(
                        id = it[Tracks.id],
                        title = it[Tracks.title],
                        artist = it[Tracks.artist],
                        duration = it[Tracks.duration],
                        hasCover = it[Tracks.albumArt] != null
                    )
                }
            }
            call.respond(tracks)
        }

        get("/tracks/{id}/cover") {
            val trackId = call.parameters["id"]?.toIntOrNull() ?: throw BadRequestException("Invalid ID")

            val cover = DatabaseFactory.dbQuery {
                Tracks.selectAll().where { Tracks.id eq trackId }
                    .map { it[Tracks.albumArt] }
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

            val filePath = DatabaseFactory.dbQuery {
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