package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object Genres : Table("genres") {
    val id = long("id").autoIncrement()
    val slug = text("slug").uniqueIndex()
    val displayName = text("display_name")
    val parentGenreId = long("parent_genre_id").nullable()
    val sortOrder = integer("sort_order").default(0)
    val createdAt = timestampWithTimeZone("created_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
