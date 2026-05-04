package com.example.features.preferences

import com.example.database.Genres
import com.example.database.UserGenrePreferences
import com.example.utils.currentUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime

@Serializable
data class GenrePreferenceRemote(
    val slug: String,
    val weight: Double,
)

@Serializable
data class UserGenrePreferencesPutReceive(
    val preferences: List<GenrePreferenceRemote>,
)

fun Application.configureUserGenrePreferencesRouting() {
    routing {
        get("/me/genre-preferences") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val rows = newSuspendedTransaction {
                val prefs = UserGenrePreferences.selectAll().where { UserGenrePreferences.userId eq uid }
                val genreIds = prefs.map { it[UserGenrePreferences.genreId] }.distinct()
                if (genreIds.isEmpty()) {
                    return@newSuspendedTransaction emptyList()
                }
                val slugById = Genres.selectAll().where { Genres.id inList genreIds }
                    .associate { it[Genres.id] to it[Genres.slug] }
                prefs.mapNotNull { r ->
                    val slug = slugById[r[UserGenrePreferences.genreId]] ?: return@mapNotNull null
                    GenrePreferenceRemote(slug = slug, weight = r[UserGenrePreferences.weight])
                }
            }
            call.respond(rows)
        }

        put("/me/genre-preferences") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@put
            }
            val body = try {
                call.receive<UserGenrePreferencesPutReceive>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                return@put
            }
            newSuspendedTransaction {
                UserGenrePreferences.deleteWhere { UserGenrePreferences.userId eq uid }
                for (p in body.preferences) {
                    val slug = p.slug.trim().lowercase().replace('-', '_')
                    if (slug.isEmpty()) continue
                    val gid = Genres.selectAll().where { Genres.slug eq slug }.singleOrNull()?.get(Genres.id)
                        ?: continue
                    val w = p.weight.coerceIn(0.0, 100.0)
                    if (w <= 0.0) continue
                    UserGenrePreferences.insert {
                        it[UserGenrePreferences.userId] = uid
                        it[UserGenrePreferences.genreId] = gid
                        it[UserGenrePreferences.weight] = w
                        it[UserGenrePreferences.updatedAt] = OffsetDateTime.now()
                    }
                }
            }
            call.respond(HttpStatusCode.OK)
        }
    }
}
