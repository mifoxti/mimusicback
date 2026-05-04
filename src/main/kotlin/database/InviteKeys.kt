package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object InviteKeys : Table("invite_keys") {
    val id = long("id").autoIncrement()
    val keyCode = text("key_code")
    val creatorUserId = long("creator_user_id").references(Users.id)
    val createdAt = timestampWithTimeZone("created_at").nullable()
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}
