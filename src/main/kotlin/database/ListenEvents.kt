package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object ListenEvents : Table("listen_events") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val trackId = long("track_id").references(Tracks.id)
    val startedAt = timestampWithTimeZone("started_at").nullable()
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    val durationPlayedMs = integer("duration_played_ms").nullable()
    val sourceType = integer("source_type").nullable()
    val sourceId = long("source_id").nullable()

    override val primaryKey = PrimaryKey(id)
}
