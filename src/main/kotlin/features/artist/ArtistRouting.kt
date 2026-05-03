package com.example.features.artist

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import com.example.database.Users
import com.example.database.Tracks
import com.example.database.UserTracks
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.*


fun Application.configureArtistRouting() {
    routing {
        get("/artist") {
            val artistName = call.request.queryParameters["name"]
            val requestingUserId = call.request.queryParameters["userId"]?.toIntOrNull()

            if (artistName.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Parameter 'name' is required")
                return@get
            }

            try {
                val result = newSuspendedTransaction {
                    val artistRow = Users.selectAll()
                        .where { Users.username eq artistName.lowercase(Locale.getDefault()) }
                        .singleOrNull()

                    val thoughts = artistRow?.get(Users.thoughts)
                        ?: "Этот пользователь пока не поведал миру о своих мыслях"

                    val songs = Tracks.selectAll()
                        .where { Tracks.artist eq artistName }
                        .map { trackRow ->
                            val trackId = trackRow[Tracks.id]

                            val isLiked = if (requestingUserId != null) {
                                UserTracks.selectAll()
                                    .where {
                                        (UserTracks.userIduser eq requestingUserId) and
                                                (UserTracks.trackIdtrack eq trackId)
                                    }
                                    .count() > 0
                            } else false

                            ArtistSong(
                                id = trackId,
                                title = trackRow[Tracks.title] ?: "",
                                artist = trackRow[Tracks.artist] ?: "",
                                coverArt = trackRow[Tracks.coverArt]?.let {
                                    Base64.getEncoder().encodeToString(it)
                                },
                                isLiked = isLiked
                            )
                        }

                    ArtistResponse(thoughts = thoughts, songs = songs)
                }

                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Ошибка при обработке запроса")
            }
        }
    }
}
