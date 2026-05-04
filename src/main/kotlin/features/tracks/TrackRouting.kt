package com.example.features.tracks

import com.example.database.Tracks
import com.example.utils.coverBase64
import com.example.utils.primaryArtist
import com.example.utils.readTrackCoverBytes
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import com.example.utils.audioFileForTrack

fun Application.configureTrackRouting() {
    routing {
        get("/tracks") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 100
            val tracks = newSuspendedTransaction {
                Tracks.selectAll()
                    .orderBy(Tracks.id, SortOrder.DESC)
                    .limit(limit)
                    .map {
                        TrackRemote(
                            id = it[Tracks.id].toInt(),
                            title = it[Tracks.title],
                            artist = it[Tracks.artists].primaryArtist().ifBlank { null },
                            duration = it[Tracks.durationMs]?.div(1000),
                            cover = coverBase64(it[Tracks.audioStorageKey], it[Tracks.coverStorageKey]),
                        )
                    }
            }
            call.respond(tracks)
        }

        get("/tracks/{id}/cover") {
            val trackId = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("Invalid ID")

            val row = newSuspendedTransaction {
                Tracks.selectAll().where { Tracks.id eq trackId }.singleOrNull()
            } ?: run {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            val bytes = readTrackCoverBytes(
                row[Tracks.audioStorageKey],
                row[Tracks.coverStorageKey],
            )
            if (bytes != null) {
                call.respondBytes(bytes, ContentType.Image.Any)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get("/tracks/{id}/stream") {
            val trackId = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("Invalid ID")

            val audioKey = newSuspendedTransaction {
                Tracks.selectAll().where { Tracks.id eq trackId }
                    .map { it[Tracks.audioStorageKey] }
                    .firstOrNull()
            }

            if (audioKey != null) {
                val file = audioFileForTrack(audioKey)
                if (file != null && file.exists()) {
                    call.respond(LocalFileContent(file, ContentType.Audio.MPEG))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}
