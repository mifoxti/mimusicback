package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object UserGenrePreferences : Table("user_genre_preferences") {
    val userId = long("user_id")
    val genreId = long("genre_id")
    val weight = double("weight").default(1.0)
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(userId, genreId)
}
