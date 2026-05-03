package com.example.utils

import com.example.database.UserTokens
import com.example.database.Users
import io.ktor.server.application.*
import io.ktor.server.request.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Достаёт userId из заголовка Authorization: Bearer <token>.
 * Возвращает null, если заголовка нет или токен невалиден.
 */
suspend fun ApplicationCall.currentUserId(): Int? {
    val authHeader = request.header("Authorization") ?: return null
    val token = authHeader.removePrefix("Bearer ").takeIf { it.isNotBlank() } ?: return null
    return newSuspendedTransaction {
        UserTokens.selectAll().where { UserTokens.token eq token }
            .map { it[UserTokens.userId] }
            .firstOrNull()
    }
}
