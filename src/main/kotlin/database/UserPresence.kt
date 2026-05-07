package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Последняя активность пользователя в приложении (heartbeat по авторизованным запросам). */
object UserPresence : Table("user_presence") {
    val userId = long("user_id").references(Users.id)
    val lastSeenAt = timestampWithTimeZone("last_seen_at")

    override val primaryKey = PrimaryKey(userId)
}
