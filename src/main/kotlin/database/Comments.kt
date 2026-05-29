package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object Comments : Table("comments") {
    val id = long("id").autoIncrement()
    val thoughtId = long("thought_id").references(Thoughts.id)
    val authorUserId = long("author_user_id").references(Users.id)
    val bodyText = text("body_text").nullable()
    val createdAt = timestampWithTimeZone("created_at").nullable()
    val updatedAt = timestampWithTimeZone("updated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
