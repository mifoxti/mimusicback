package com.example.features.loved

import com.example.database.TrackLikes
import com.example.database.Tracks
import com.example.utils.coverBase64ForApiList
import com.example.utils.primaryArtist
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

fun Application.configureLovedTracksRouting() {
    routing {
        get("/users/{id}/loved") {
            val userId = call.parameters["id"]?.toLongOrNull()
                ?: throw BadRequestException("Invalid user ID")

            val lovedTracks = newSuspendedTransaction {
                (TrackLikes innerJoin Tracks).selectAll().where {
                    TrackLikes.userId eq userId
                }.map {
                    LovedTrackRemote(
                        id = it[Tracks.id].toInt(),
                        title = it[Tracks.title],
                        artist = it[Tracks.artists].primaryArtist().ifBlank { null },
                        coverArt = coverBase64ForApiList(it[Tracks.audioStorageKey], it[Tracks.coverStorageKey]),
                    )
                }
            }

            call.respond(lovedTracks)
        }
    }
}
