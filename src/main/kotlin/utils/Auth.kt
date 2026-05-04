package com.example.utils

import com.example.database.AuthSessions
import io.ktor.server.application.*
import io.ktor.server.request.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Достаёт userId из заголовка Authorization: Bearer <token>.
 * Возвращает null, если заголовка нет или токен невалиден.
 */
suspend fun ApplicationCall.currentUserId(): Int? {
    val authHeader = request.header("Authorization") ?: return null
    val token = authHeader.removePrefix("Bearer ").takeIf { it.isNotBlank() } ?: return null
    val tokenHash = sha256Hex(token)
    return newSuspendedTransaction {
        AuthSessions.selectAll().where { AuthSessions.tokenHash eq tokenHash }
            .map { it[AuthSessions.userId] }
            .firstOrNull()
            ?.toInt()
    }
}
