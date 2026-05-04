package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object Playlists : Table("playlists") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val title = text("title").nullable()
    val isPublic = bool("is_public").nullable()
    val coverStorageKey = text("cover_storage_key").nullable()
    val createdAt = timestampWithTimeZone("created_at").nullable()
    val updatedAt = timestampWithTimeZone("updated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
