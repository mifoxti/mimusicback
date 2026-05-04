package com.example.features.genres

import com.example.database.Genres
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

@Serializable
data class GenreRemote(
    val id: Int,
    val slug: String,
    val displayName: String,
)

fun Application.configureGenreRouting() {
    routing {
        get("/genres") {
            val list = newSuspendedTransaction {
                Genres.selectAll().orderBy(Genres.sortOrder, SortOrder.ASC).map {
                    GenreRemote(
                        id = it[Genres.id].toInt(),
                        slug = it[Genres.slug],
                        displayName = it[Genres.displayName],
                    )
                }
            }
            call.respond(list)
        }
    }
}
