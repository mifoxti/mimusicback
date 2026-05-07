package com.example.utils

import com.example.database.AuthSessions
import com.example.database.UserPresence
import io.ktor.server.application.*
import io.ktor.server.request.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

/**
 * Достаёт userId из заголовка Authorization: Bearer <token>.
 * Возвращает null, если заголовка нет или токен невалиден.
 */
suspend fun ApplicationCall.currentUserId(): Int? {
    val authHeader = request.header("Authorization") ?: return null
    val token = authHeader.removePrefix("Bearer ").takeIf { it.isNotBlank() } ?: return null
    val tokenHash = sha256Hex(token)
    return newSuspendedTransaction {
        val uid = AuthSessions.selectAll().where { AuthSessions.tokenHash eq tokenHash }
            .map { it[AuthSessions.userId] }
            .firstOrNull()
        if (uid != null) {
            val now = OffsetDateTime.now()
            val updated = UserPresence.update({ UserPresence.userId eq uid }) {
                it[lastSeenAt] = now
            }
            if (updated == 0) {
                UserPresence.insert {
                    it[userId] = uid
                    it[lastSeenAt] = now
                }
            }
        }
        uid?.toInt()
    }
}
