package com.example.features.artist

import com.example.database.Thoughts
import com.example.database.TrackLikes
import com.example.database.Tracks
import com.example.database.Users
import com.example.utils.coverBase64ForApiList
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
                    val normalized = artistName.trim()
                    val artistRow = Users.selectAll()
                        .where { Users.nickname.lowerCase() eq normalized.lowercase(Locale.getDefault()) }
                        .singleOrNull()
                    val isRegistered = artistRow != null &&
                        !artistRow[Users.nickname].startsWith("__")
                    val registeredUserId = if (isRegistered) artistRow!![Users.id].toInt() else null

                    val allTracks = Tracks.selectAll().toList()
                    val matching = allTracks
                        .filter { trackRow ->
                            trackRow[Tracks.artists].orEmpty().any {
                                it.equals(normalized, ignoreCase = true)
                            }
                        }
                        .sortedByDescending { it[Tracks.id] }

                    val songs = matching.map { trackRow ->
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
                            coverArt = coverBase64ForApiList(
                                trackRow[Tracks.audioStorageKey],
                                trackRow[Tracks.coverStorageKey],
                            ),
                            isLiked = isLiked,
                        )
                    }

                    val heroCoverArt = if (!isRegistered) {
                        matching.firstOrNull()?.let { trackRow ->
                            coverBase64ForApiList(
                                trackRow[Tracks.audioStorageKey],
                                trackRow[Tracks.coverStorageKey],
                            )
                        }
                    } else {
                        null
                    }

                    val thoughts = if (isRegistered && artistRow != null) {
                        Thoughts.selectAll()
                            .where { Thoughts.authorUserId eq artistRow[Users.id] }
                            .orderBy(Thoughts.id, SortOrder.DESC)
                            .limit(1)
                            .map { it[Thoughts.bodyText] }
                            .firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: artistRow[Users.bio]
                            ?: ""
                    } else {
                        ""
                    }

                    ArtistResponse(
                        thoughts = thoughts,
                        songs = songs,
                        registeredUserId = registeredUserId,
                        isRegistered = isRegistered,
                        heroCoverArt = heroCoverArt,
                    )
                }

                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Ошибка при обработке запроса")
            }
        }
    }
}
