package com.example.features.recommendations

import com.example.database.RecommendationEvents
import com.example.database.Tracks
import com.example.utils.coverBase64
import com.example.utils.currentUserId
import com.example.utils.primaryArtist
import com.example.services.RecommendationScoreService
import com.example.services.TrackGenreService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime

@Serializable
data class RecommendedTrackRemote(
    val id: Int,
    val title: String,
    val artist: String?,
    val duration: Int?,
    val cover: String?,
    val genres: List<String> = emptyList(),
    val score: Double,
)

@Serializable
data class RecommendationEventReceive(
    val surface: String,
    val targetType: String,
    val targetId: Long,
    val interaction: String,
    val scorePresent: Double? = null,
)

@Serializable
data class RecommendationEventsBatchReceive(
    val events: List<RecommendationEventReceive>,
)

fun Application.configureRecommendationRouting() {
    routing {
        get("/recommendations/tracks") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 30
            val scored = RecommendationScoreService.scoredTrackIds(uid, limit)
            if (scored.isEmpty()) {
                call.respond(emptyList<RecommendedTrackRemote>())
                return@get
            }
            val genreMap = TrackGenreService.loadGenreSlugsForTracks(scored.map { it.trackId })
            val byId = newSuspendedTransaction {
                val ids = scored.map { it.trackId }
                Tracks.selectAll().where { Tracks.id inList ids }
                    .associateBy { it[Tracks.id] }
            }
            val out = scored.mapNotNull { st ->
                val row = byId[st.trackId] ?: return@mapNotNull null
                RecommendedTrackRemote(
                    id = row[Tracks.id].toInt(),
                    title = row[Tracks.title],
                    artist = row[Tracks.artists].primaryArtist().ifBlank { null },
                    duration = row[Tracks.durationMs]?.div(1000),
                    cover = coverBase64(row[Tracks.audioStorageKey], row[Tracks.coverStorageKey]),
                    genres = genreMap[st.trackId].orEmpty(),
                    score = st.score,
                )
            }
            call.respond(out)
        }

        post("/recommendations/events") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val body = try {
                call.receive<RecommendationEventsBatchReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                return@post
            }
            newSuspendedTransaction {
                for (e in body.events) {
                    RecommendationEvents.insert {
                        it[RecommendationEvents.userId] = uid
                        it[RecommendationEvents.surface] = e.surface.take(64)
                        it[RecommendationEvents.targetType] = e.targetType.take(32)
                        it[RecommendationEvents.targetId] = e.targetId
                        it[RecommendationEvents.interaction] = e.interaction.take(32)
                        it[RecommendationEvents.scorePresent] = e.scorePresent
                        it[RecommendationEvents.meta] = null
                        it[RecommendationEvents.createdAt] = OffsetDateTime.now()
                    }
                }
            }
            call.respond(HttpStatusCode.Created)
        }
    }
}
