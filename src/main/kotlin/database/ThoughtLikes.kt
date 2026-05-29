package com.example.database

import org.jetbrains.exposed.sql.Table

object ThoughtLikes : Table("thought_likes") {
    val thoughtId = long("thought_id").references(Thoughts.id)
    val userId = long("user_id").references(Users.id)

    override val primaryKey = PrimaryKey(thoughtId, userId)
}
