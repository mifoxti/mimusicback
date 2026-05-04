package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object Thoughts : Table("thoughts") {
    val id = long("id").autoIncrement()
    val authorUserId = long("author_user_id").references(Users.id)
    val bodyText = text("body_text").nullable()
    val attachmentType = integer("attachment_type").nullable()
    val attachmentTrackId = long("attachment_track_id").nullable()
    val attachmentPlaylistId = long("attachment_playlist_id").nullable()
    val createdAt = timestampWithTimeZone("created_at").nullable()
    val updatedAt = timestampWithTimeZone("updated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
