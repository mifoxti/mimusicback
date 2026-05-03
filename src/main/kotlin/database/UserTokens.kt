package com.example.database

import org.jetbrains.exposed.sql.Table

object UserTokens : Table("usertokens") {
    val userId = integer("user_iduser").references(Users.id)
    val token = varchar("token", 255)

    override val primaryKey = PrimaryKey(userId, token)
}