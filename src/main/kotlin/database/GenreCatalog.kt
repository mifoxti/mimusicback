package com.example.database

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.postgresql.util.PSQLException
import java.time.OffsetDateTime

private fun Throwable.psqlCause(): PSQLException? {
    var c: Throwable? = this
    while (c != null) {
        if (c is PSQLException) return c
        c = c.cause
    }
    return null
}

/**
 * Exposed `createMissingTablesAndColumns` на частично накатанной БД не всегда создаёт `track_genres` / `album_genres`.
 * Дублируем явным `CREATE` и игнорируем только «таблица уже есть» (42P07).
 */
private fun ensureGenreLinkTablesCreated() {
    for (table in arrayOf(TrackGenres, AlbumGenres)) {
        try {
            SchemaUtils.create(table)
        } catch (e: Exception) {
            if (e.psqlCause()?.sqlState != "42P07") throw e
        }
    }
}

private data class GenreSeed(val slug: String, val displayName: String, val sortOrder: Int)

/** Совпадает с Flutter `studioGenreIds` и INSERT в `pgsql_starter_code.sql`. */
private val genreSeeds: List<GenreSeed> =
    listOf(
        GenreSeed("pop", "Pop", 10),
        GenreSeed("rock", "Rock", 20),
        GenreSeed("electronic", "Electronic", 30),
        GenreSeed("hip_hop", "Hip-Hop", 40),
        GenreSeed("rb", "R&B", 50),
        GenreSeed("jazz", "Jazz", 60),
        GenreSeed("classical", "Classical", 70),
        GenreSeed("ambient", "Ambient", 80),
        GenreSeed("lo_fi", "Lo-Fi", 90),
        GenreSeed("metal", "Metal", 100),
        GenreSeed("punk", "Punk", 110),
        GenreSeed("indie", "Indie", 120),
        GenreSeed("folk", "Folk", 130),
        GenreSeed("country", "Country", 140),
        GenreSeed("reggae", "Reggae", 150),
        GenreSeed("drum_bass", "Drum & Bass", 160),
        GenreSeed("house", "House", 170),
        GenreSeed("techno", "Techno", 180),
        GenreSeed("trance", "Trance", 190),
        GenreSeed("dubstep", "Dubstep", 200),
        GenreSeed("other", "Other", 900),
    )

fun ensureRecommendationTables() {
    transaction {
        SchemaUtils.createMissingTablesAndColumns(UserGenrePreferences, RecommendationEvents)
    }
}

fun ensureListenEventsTable() {
    transaction {
        SchemaUtils.createMissingTablesAndColumns(ListenEvents)
    }
}

fun ensureGenresSeeded() {
    transaction {
        // DDL — `pgsql_starter_code.sql`; при неполной БД поднимаем минимум для сида и `GET /tracks`.
        SchemaUtils.createMissingTablesAndColumns(Genres, TrackGenres, AlbumGenres)
        ensureGenreLinkTablesCreated()
        for (g in genreSeeds) {
            val exists = Genres.selectAll().where { Genres.slug eq g.slug }.firstOrNull() != null
            if (!exists) {
                Genres.insert {
                    it[slug] = g.slug
                    it[displayName] = g.displayName
                    it[parentGenreId] = null
                    it[sortOrder] = g.sortOrder
                    it[createdAt] = OffsetDateTime.now()
                }
            }
        }
    }
}
