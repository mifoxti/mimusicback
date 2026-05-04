package com.example.database

import org.jetbrains.exposed.sql.Table

object AuthSessions : Table("auth_sessions") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val tokenHash = text("token_hash").uniqueIndex()
    val ipAddress = text("ip_address").nullable()
    val deviceLabel = text("device_label").nullable()

    override val primaryKey = PrimaryKey(id)
}
