package com.example.database

import org.jetbrains.exposed.sql.Table

object TrackLikes : Table("track_likes") {
    val userId = long("user_id").references(Users.id)
    val trackId = long("track_id").references(Tracks.id)

    override val primaryKey = PrimaryKey(userId, trackId)
}
