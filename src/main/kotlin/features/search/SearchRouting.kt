package com.example.features.search

import com.example.database.DatabaseFactory
import com.example.database.Tracks
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import java.io.File


fun Application.configureSearchRouting() {
    routing {
        get("/search") {
            var searchQuery = call.request.queryParameters["q"] ?: ""
            searchQuery = searchQuery.lowercase()
            if (searchQuery.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "Query parameter 'q' is required")
                return@get
            }

            val tracks = DatabaseFactory.dbQuery {
                Tracks.selectAll().where {
                    (Tracks.title.lowerCase() like "%$searchQuery%") or
                            (Tracks.artist.lowerCase() like "%$searchQuery%")
                }.map { row ->
                    SearchRemote(
                        id = row[Tracks.id],
                        title = row[Tracks.title],
                        artist = row[Tracks.artist],
                        duration = row[Tracks.duration],
                    )
                }
            }

            call.respond(tracks)
        }
    }
}