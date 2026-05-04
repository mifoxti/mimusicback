package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Одна строка на пару друзей: `user_low` &lt; `user_high` (канонический порядок). */
object Friendships : Table("friendships") {
    val userLow = long("user_low").references(Users.id)
    val userHigh = long("user_high").references(Users.id)
    val createdAt = timestampWithTimeZone("created_at").nullable()

    override val primaryKey = PrimaryKey(userLow, userHigh)
}
