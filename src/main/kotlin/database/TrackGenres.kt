package com.example.database

import org.jetbrains.exposed.sql.Table

object TrackGenres : Table("track_genres") {
    val trackId = long("track_id")
    val genreId = long("genre_id")
    val weight = double("weight").default(1.0)
    val genreSource = text("source").default("uploader")

    override val primaryKey = PrimaryKey(trackId, genreId)
}
