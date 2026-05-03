package com.example.features.artist

import com.example.database.Thoughts
import com.example.database.TrackLikes
import com.example.database.Tracks
import com.example.database.Users
import com.example.utils.coverBase64
import com.example.utils.primaryArtist
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.*

fun Application.configureArtistRouting() {
    routing {
        get("/artist") {
            val artistName = call.request.queryParameters["name"]
            val requestingUserId = call.request.queryParameters["userId"]?.toLongOrNull()

            if (artistName.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Parameter 'name' is required")
                return@get
            }

            try {
                val result = newSuspendedTransaction {
                    val artistRow = Users.selectAll()
                        .where { Users.nickname.lowerCase() eq artistName.lowercase(Locale.getDefault()) }
                        .singleOrNull()

                    val thoughtsText = if (artistRow != null) {
                        Thoughts.selectAll()
                            .where { Thoughts.authorUserId eq artistRow[Users.id] }
                            .orderBy(Thoughts.id, SortOrder.DESC)
                            .limit(1)
                            .map { it[Thoughts.bodyText] }
                            .firstOrNull()
                            ?: artistRow[Users.bio]
                    } else null

                    val thoughts = thoughtsText
                        ?: "Этот пользователь пока не поведал миру о своих мыслях"

                    val allTracks = Tracks.selectAll().toList()
                    val songs = allTracks.filter { trackRow ->
                        trackRow[Tracks.artists].orEmpty().any { it.equals(artistName, ignoreCase = true) }
                    }.map { trackRow ->
                        val trackId = trackRow[Tracks.id]

                        val isLiked = if (requestingUserId != null) {
                            TrackLikes.selectAll()
                                .where {
                                    (TrackLikes.userId eq requestingUserId) and (TrackLikes.trackId eq trackId)
                                }
                                .count() > 0
                        } else {
                            false
                        }

                        ArtistSong(
                            id = trackId.toInt(),
                            title = trackRow[Tracks.title],
                            artist = trackRow[Tracks.artists].primaryArtist(),
                            coverArt = coverBase64(
                                trackRow[Tracks.audioStorageKey],
                                trackRow[Tracks.coverStorageKey],
                            ),
                            isLiked = isLiked,
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
