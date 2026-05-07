package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Последний трек, который пользователь пометил как «сейчас слушает» (для друзей и профиля). */
object UserNowPlaying : Table("user_now_playing") {
    val userId = long("user_id").references(Users.id)
    val trackId = long("track_id").references(Tracks.id).nullable()
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(userId)
}
