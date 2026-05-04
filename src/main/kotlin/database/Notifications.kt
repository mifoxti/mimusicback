package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object Notifications : Table("notifications") {
    val id = long("id").autoIncrement()
    val recipientUserId = long("recipient_user_id").nullable()
    val actorUserId = long("actor_user_id").nullable()
    val type = integer("type")
    val entityRef = text("entity_ref").nullable()
    val entityId = long("entity_id").nullable()
    val payloadJson = text("payload_json").nullable()
    val readAt = timestampWithTimeZone("read_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
