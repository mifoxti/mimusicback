package com.example.features.studio

import com.example.database.Tracks
import com.example.services.ListenStatsService
import com.example.utils.currentUserId
import com.example.utils.primaryArtist
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

fun Application.configureStudioStatsRouting() {
    routing {
        get("/me/studio/stats") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(7, 30) ?: 14

            val myTrackRows = newSuspendedTransaction {
                Tracks.selectAll()
                    .where { Tracks.uploaderUserId eq uid }
                    .orderBy(Tracks.id, SortOrder.DESC)
                    .toList()
            }
            val myIds = myTrackRows.map { it[Tracks.id] }
            val counts = ListenStatsService.playCountsByTrackId(myIds)

            val topTracks = myTrackRows
                .map { row ->
                    val tid = row[Tracks.id]
                    StudioTopTrackRemote(
                        trackId = tid.toInt(),
                        title = row[Tracks.title],
                        playCount = counts[tid] ?: 0,
                    )
                }
                .sortedByDescending { it.playCount }
                .take(10)

            val stats = MeStudioStatsRemote(
                totalPlays = ListenStatsService.totalPlaysForTracks(myIds),
                totalTracks = myIds.size,
                uniqueListeners = ListenStatsService.uniqueListenersForTracks(myIds),
                playsByDay = ListenStatsService.playsByDayForTracks(myIds, days),
                topTracks = topTracks,
            )
            call.respond(stats)
        }

        get("/tracks/{id}/studio-stats") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val trackId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid track id")
                return@get
            }
            val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(7, 30) ?: 14

            val row = newSuspendedTransaction {
                Tracks.selectAll().where { Tracks.id eq trackId }.singleOrNull()
            } ?: run {
                call.respond(HttpStatusCode.NotFound, "Трек не найден")
                return@get
            }
            if (row[Tracks.uploaderUserId] != uid) {
                call.respond(HttpStatusCode.Forbidden, "Статистика доступна только автору трека")
                return@get
            }

            call.respond(
                TrackStudioStatsRemote(
                    trackId = trackId.toInt(),
                    title = row[Tracks.title],
                    artist = row[Tracks.artists].primaryArtist().ifBlank { null },
                    totalPlays = ListenStatsService.playCountForTrack(trackId),
                    uniqueListeners = ListenStatsService.uniqueListenersForTrack(trackId),
                    playsByDay = ListenStatsService.playsByDayForTracks(listOf(trackId), days),
                ),
            )
        }
    }
}
