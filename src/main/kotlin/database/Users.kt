package com.example.database

import org.jetbrains.exposed.sql.Table

object Users : Table("users") {
    val id = integer("iduser").autoIncrement()
    val username = varchar("username", 45)
    val password = varchar("password", 100)
    val thoughts = varchar("thoughts", 45).nullable()
    val email = varchar("email", 255).nullable()

    override val primaryKey = PrimaryKey(id, name = "PK_User_ID")
}
