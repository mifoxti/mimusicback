package com.example.database

import org.jetbrains.exposed.sql.Table

/**
 * Связь «дружба» между пользователями.
 * Храним одну сторону: user_id считает friend_id другом.
 * Для двусторонней дружбы при добавлении можно создавать две строки
 * (user_id=A, friend_id=B и user_id=B, friend_id=A) или проверять обе стороны при выборке.
 */
object Friends : Table("friends") {
    val userId = integer("user_id").references(Users.id)
    val friendId = integer("friend_id").references(Users.id)

    override val primaryKey = PrimaryKey(userId, friendId)
}
