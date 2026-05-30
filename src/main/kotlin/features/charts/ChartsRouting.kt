package com.example.features.charts

import com.example.database.ListenEvents
import com.example.database.Tracks
import com.example.utils.coverBase64ForApiList
import com.example.utils.currentUserId
import com.example.utils.primaryArtist
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

fun Application.configureChartsRouting() {
    routing {
        get("/charts/tracks") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
            val uid = call.currentUserId()?.toLong()

            val counts = newSuspendedTransaction {
                ListenEvents.selectAll()
                    .map { it[ListenEvents.trackId] }
                    .groupingBy { it }
                    .eachCount()
            }

            val merged = linkedMapOf<Long, Int>()

            if (counts.isEmpty()) {
                newSuspendedTransaction {
                    Tracks.selectAll()
                        .orderBy(Tracks.id, SortOrder.DESC)
                        .limit(limit)
                        .forEach { row -> merged[row[Tracks.id]] = 0 }
                }
            } else {
                counts.entries
                    .sortedByDescending { it.value }
                    .take(limit)
                    .forEach { (tid, c) -> merged[tid] = c }
            }

            // Свои загрузки всегда в чарте (с глобальным числом прослушиваний), в отличие от «Для вас».
            if (uid != null) {
                val mine = newSuspendedTransaction {
                    Tracks.selectAll()
                        .where { Tracks.uploaderUserId eq uid }
                        .orderBy(Tracks.id, SortOrder.DESC)
                        .map { it[Tracks.id] }
                }
                for (tid in mine) {
                    if (!merged.containsKey(tid)) {
                        merged[tid] = counts[tid] ?: 0
                    }
                }
            }

            val top = merged.entries
                .sortedWith(
                    compareByDescending<Map.Entry<Long, Int>> { it.value }
                        .thenByDescending { it.key },
                )

            if (top.isEmpty()) {
                call.respond(emptyList<ChartTrackRemote>())
                return@get
            }

            val trackIds = top.map { it.key }
            val rowsById = newSuspendedTransaction {
                Tracks.selectAll().where { Tracks.id inList trackIds }
                    .associateBy { it[Tracks.id] }
            }

            val out = top.mapIndexed { index, (tid, count) ->
                val row = rowsById[tid] ?: return@mapIndexed null
                ChartTrackRemote(
                    rank = index + 1,
                    trackId = tid.toInt(),
                    title = row[Tracks.title],
                    artist = row[Tracks.artists].primaryArtist().ifBlank { null },
                    playCount = count,
                    cover = coverBase64ForApiList(row[Tracks.audioStorageKey], row[Tracks.coverStorageKey]),
                    isNew = count > 0 && count <= 2,
                )
            }.filterNotNull()

            call.respond(out)
        }
    }
}
