package com.example.database

import org.jetbrains.exposed.sql.Table

object UserTracks : Table("usertracks") {
    val userIduser = integer("user_iduser").references(Users.id)
    val trackIdtrack = integer("track_idtrack").references(Tracks.id)

    override val primaryKey = PrimaryKey(userIduser, trackIdtrack)
}