package com.example.features.tracks

import com.example.database.AlbumTracks
import com.example.database.PlaylistTracks
import com.example.database.RecommendationEvents
import com.example.database.Thoughts
import com.example.database.TrackGenres
import com.example.database.TrackLikes
import com.example.database.Tracks
import com.example.services.TrackGenreService
import com.example.utils.audioFileForTrack
import com.example.utils.coverBase64
import com.example.utils.currentUserId
import com.example.utils.deleteTrackMediaFiles
import com.example.utils.primaryArtist
import com.example.utils.readTrackCoverBytes
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

@Serializable
data class TrackGenresPutReceive(
    val genreSlugs: List<String>,
    val normalizeWeights: Boolean = false,
)

private sealed class DeleteTrackOutcome {
    data object Missing : DeleteTrackOutcome()
    data object Forbidden : DeleteTrackOutcome()
    data class Deleted(val audioStorageKey: String?, val coverStorageKey: String?) : DeleteTrackOutcome()
}

fun Application.configureTrackRouting() {
    routing {
        get("/tracks") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 100
            val tracks = newSuspendedTransaction {
                Tracks.selectAll()
                    .orderBy(Tracks.id, SortOrder.DESC)
                    .limit(limit)
                    .map { it }
            }
            val ids = tracks.map { it[Tracks.id] }
            val genreMap = TrackGenreService.loadGenreSlugsForTracks(ids)
            val out = tracks.map {
                TrackRemote(
                    id = it[Tracks.id].toInt(),
                    title = it[Tracks.title],
                    artist = it[Tracks.artists].primaryArtist().ifBlank { null },
                    duration = it[Tracks.durationMs]?.div(1000),
                    cover = coverBase64(it[Tracks.audioStorageKey], it[Tracks.coverStorageKey]),
                    genres = genreMap[it[Tracks.id]].orEmpty(),
                )
            }
            call.respond(out)
        }

        delete("/tracks/{id}") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@delete
            }
            val trackId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid track id")
                return@delete
            }
            val outcome = newSuspendedTransaction {
                val row = Tracks.selectAll().where { Tracks.id eq trackId }.singleOrNull()
                    ?: return@newSuspendedTransaction DeleteTrackOutcome.Missing
                if (row[Tracks.uploaderUserId] != uid) {
                    return@newSuspendedTransaction DeleteTrackOutcome.Forbidden
                }
                val audio = row[Tracks.audioStorageKey]
                val cover = row[Tracks.coverStorageKey]
                TrackGenres.deleteWhere { TrackGenres.trackId eq trackId }
                TrackLikes.deleteWhere { TrackLikes.trackId eq trackId }
                PlaylistTracks.deleteWhere { PlaylistTracks.trackId eq trackId }
                AlbumTracks.deleteWhere { AlbumTracks.trackId eq trackId }
                Thoughts.update({ Thoughts.attachmentTrackId eq trackId }) {
                    it[Thoughts.attachmentTrackId] = null
                }
                RecommendationEvents.deleteWhere {
                    (RecommendationEvents.targetType.lowerCase() eq "track") and
                        (RecommendationEvents.targetId eq trackId)
                }
                Tracks.deleteWhere { Tracks.id eq trackId }
                DeleteTrackOutcome.Deleted(audio, cover)
            }
            when (outcome) {
                DeleteTrackOutcome.Missing -> {
                    call.respond(HttpStatusCode.NotFound, "Трек не найден")
                    return@delete
                }
                DeleteTrackOutcome.Forbidden -> {
                    call.respond(HttpStatusCode.Forbidden, "Можно удалить только свой трек")
                    return@delete
                }
                is DeleteTrackOutcome.Deleted -> {
                    deleteTrackMediaFiles(outcome.audioStorageKey, outcome.coverStorageKey)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        put("/tracks/{id}/genres") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@put
            }
            val trackId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid track id")
                return@put
            }
            val body = try {
                call.receive<TrackGenresPutReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                return@put
            }
            val owner = newSuspendedTransaction {
                Tracks.selectAll().where { Tracks.id eq trackId }.singleOrNull()?.let { it[Tracks.uploaderUserId] }
            }
            when {
                owner == null -> {
                    call.respond(HttpStatusCode.NotFound, "Трек не найден")
                    return@put
                }
                owner != uid -> {
                    call.respond(HttpStatusCode.Forbidden, "Можно менять жанры только своего трека")
                    return@put
                }
            }
            TrackGenreService.replaceTrackGenres(
                trackId = trackId,
                slugs = body.genreSlugs,
                source = "uploader",
                normalizeWeights = body.normalizeWeights,
            )
            call.respond(HttpStatusCode.OK)
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
                    call.respondTrackAudioWithOptionalRange(file)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}
