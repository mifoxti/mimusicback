package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object FriendRequests : Table("friend_requests") {
    val fromUserId = long("from_user_id").references(Users.id)
    val toUserId = long("to_user_id").references(Users.id)
    val status = text("status").default("pending")
    val createdAt = timestampWithTimeZone("created_at").nullable()
    val respondedAt = timestampWithTimeZone("responded_at").nullable()

    override val primaryKey = PrimaryKey(fromUserId, toUserId)
}
