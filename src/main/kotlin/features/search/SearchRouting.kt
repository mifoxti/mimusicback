package com.example.features.search

import com.example.database.TrackLikes
import com.example.database.Tracks
import com.example.utils.coverBase64
import com.example.utils.primaryArtist
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

fun Application.configureSearchRouting() {
    routing {
        get("/search") {
            var searchQuery = call.request.queryParameters["q"] ?: ""
            searchQuery = searchQuery.lowercase()

            val userId = call.request.queryParameters["userId"]?.toLongOrNull()

            if (searchQuery.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "Query parameter 'q' is required")
                return@get
            }

            val tracks = newSuspendedTransaction {
                val likedTrackIds = if (userId != null) {
                    TrackLikes.selectAll().where { TrackLikes.userId eq userId }.map { it[TrackLikes.trackId] }.toSet()
                } else emptySet()

                val byTitle = Tracks.selectAll().where {
                    Tracks.title.lowerCase() like "%$searchQuery%"
                }.toList()
                val byTitleIds = byTitle.map { it[Tracks.id] }.toSet()
                val byArtistOnly = Tracks.selectAll().toList().filter { row ->
                    row[Tracks.id] !in byTitleIds &&
                        row[Tracks.artists].orEmpty().any { it.lowercase().contains(searchQuery) }
                }
                (byTitle + byArtistOnly).map { row ->
                    val trackId = row[Tracks.id]
                    SearchRemote(
                        id = trackId.toInt(),
                        title = row[Tracks.title],
                        artist = row[Tracks.artists].primaryArtist().ifBlank { null },
                        duration = row[Tracks.durationMs]?.div(1000),
                        coverArt = coverBase64(row[Tracks.audioStorageKey], row[Tracks.coverStorageKey]),
                        isLiked = userId != null && likedTrackIds.contains(trackId),
                    )
                }
            }

            call.respond(tracks)
        }
    }
}
