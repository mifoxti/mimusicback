package com.example.features.search

import com.example.database.Tracks
import com.example.database.UserTracks
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.*

fun Application.configureSearchRouting() {
    routing {
        get("/search") {
            var searchQuery = call.request.queryParameters["q"] ?: ""
            searchQuery = searchQuery.lowercase()

            val userId = call.request.queryParameters["userId"]?.toIntOrNull()

            if (searchQuery.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "Query parameter 'q' is required")
                return@get
            }

            val tracks = newSuspendedTransaction {
                val likedTrackIds = if (userId != null) {
                    UserTracks.selectAll().where { UserTracks.userIduser eq userId }.map { it[UserTracks.trackIdtrack] }.toSet()
                } else emptySet()

                Tracks.selectAll().where {
                    (Tracks.title.lowerCase() like "%$searchQuery%") or
                            (Tracks.artist.lowerCase() like "%$searchQuery%")
                }.map { row ->
                    val trackId = row[Tracks.id]
                    SearchRemote(
                        id = trackId,
                        title = row[Tracks.title],
                        artist = row[Tracks.artist],
                        duration = row[Tracks.duration],
                        coverArt = row[Tracks.coverArt]?.let { Base64.getEncoder().encodeToString(it) },
                        isLiked = userId != null && likedTrackIds.contains(trackId)
                    )
                }
            }

            call.respond(tracks)
        }
    }
}
