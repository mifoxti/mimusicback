package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object Tracks : Table("tracks") {
    val id = long("id").autoIncrement()
    val uploaderUserId = long("uploader_user_id").references(Users.id)
    val title = text("title").default("Untitled")
    val artists = array<String>("artists").nullable()
    val audioStorageKey = text("audio_storage_key").nullable()
    val coverStorageKey = text("cover_storage_key").nullable()
    val hash = text("hash").nullable()
    val durationMs = integer("duration_ms").nullable()
    val createdAt = timestampWithTimeZone("created_at").nullable()
    val updatedAt = timestampWithTimeZone("updated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
