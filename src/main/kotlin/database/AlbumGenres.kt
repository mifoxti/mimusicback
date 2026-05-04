package com.example.database

import org.jetbrains.exposed.sql.Table

object AlbumGenres : Table("album_genres") {
    val albumId = long("album_id")
    val genreId = long("genre_id")
    val weight = double("weight").default(1.0)
    /** Имя в Kotlin не `source` — конфликт с [org.jetbrains.exposed.sql.ColumnSet.source]. */
    val genreSource = text("source").default("owner")

    override val primaryKey = PrimaryKey(albumId, genreId)
}
