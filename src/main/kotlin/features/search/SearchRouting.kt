package com.example.features.search

import com.example.database.TrackLikes
import com.example.database.Tracks
import com.example.database.Users
import com.example.utils.coverBase64
import com.example.utils.primaryArtist
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

fun Application.configureSearchRouting() {
    routing {
        get("/users/nickname-available") {
            val raw = call.request.queryParameters["nick"]?.trim().orEmpty()
            if (raw.length < 2 || raw.length > 255) {
                call.respond(HttpStatusCode.BadRequest, "Nick must be 2..255 characters")
                return@get
            }
            val exceptId = call.request.queryParameters["exceptUserId"]?.toLongOrNull()
            val taken = newSuspendedTransaction {
                val rows = Users.selectAll().where { Users.nickname.lowerCase() eq raw.lowercase() }.toList()
                when {
                    exceptId == null -> rows.isNotEmpty()
                    else -> rows.any { it[Users.id] != exceptId }
                }
            }
            call.respond(NicknameAvailableRemote(available = !taken))
        }

        get("/users/search") {
            var q = call.request.queryParameters["q"]?.trim()?.lowercase().orEmpty()
            q = q.replace("%", "").replace("_", "").take(48)
            if (q.length < 2) {
                call.respond(HttpStatusCode.BadRequest, "Query parameter 'q' must be at least 2 characters")
                return@get
            }
            // В SQL LIKE символ `_` — подстановка одного символа, поэтому `notLike "__%"` отсекал все ники
            // длиной ≥ 2. Системные ники с префиксом "__" отфильтруем в Kotlin.
            val rows = newSuspendedTransaction {
                Users.selectAll()
                    .where {
                        Users.nickname.lowerCase() like "%$q%"
                    }
                    .limit(120)
                    .map {
                        UserSearchResultRemote(
                            id = it[Users.id].toInt(),
                            nickname = it[Users.nickname],
                        )
                    }
                    .filter { !it.nickname.startsWith("__") }
                    .take(40)
            }
            call.respond(rows)
        }

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
