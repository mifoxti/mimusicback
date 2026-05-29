package com.example.features.history

import com.example.database.ListenEvents
import com.example.database.Tracks
import com.example.utils.currentUserId
import com.example.utils.primaryArtist
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime

fun Application.configureListeningHistoryRouting() {
    routing {
        post("/me/listen-events") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val body = try {
                call.receive<ListenEventCreateReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON body")
                return@post
            }
            val trackId = body.trackId
            val trackExists = newSuspendedTransaction {
                Tracks.selectAll().where { Tracks.id eq trackId }.any()
            }
            if (!trackExists) {
                call.respond(HttpStatusCode.BadRequest, "Track not found")
                return@post
            }
            val now = OffsetDateTime.now()
            newSuspendedTransaction {
                ListenEvents.insert {
                    it[ListenEvents.userId] = uid
                    it[ListenEvents.trackId] = trackId
                    it[ListenEvents.startedAt] = now
                    it[ListenEvents.sourceType] = 0
                }
            }
            call.respond(HttpStatusCode.Created, mapOf("status" to "ok"))
        }

        get("/me/listening-history") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 100
            val rows = newSuspendedTransaction {
                ListenEvents.selectAll()
                    .where { ListenEvents.userId eq uid }
                    .orderBy(ListenEvents.startedAt to SortOrder.DESC, ListenEvents.id to SortOrder.DESC)
                    .limit(limit * 3)
                    .toList()
            }
            val seenTracks = mutableSetOf<Long>()
            val items = mutableListOf<ListeningHistoryItemRemote>()
            for (row in rows) {
                val tid = row[ListenEvents.trackId]
                if (!seenTracks.add(tid)) continue
                val track = newSuspendedTransaction {
                    Tracks.selectAll().where { Tracks.id eq tid }.singleOrNull()
                } ?: continue
                items.add(
                    ListeningHistoryItemRemote(
                        trackId = tid.toInt(),
                        title = track[Tracks.title],
                        artist = track[Tracks.artists].primaryArtist().ifBlank { null },
                        playedAt = row[ListenEvents.startedAt]?.toString() ?: "",
                    ),
                )
                if (items.size >= limit) break
            }
            call.respond(items)
        }
    }
}
