package com.example

import com.example.features.friends.configureFriendRouting
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Тесты API друзей без реальной БД:
 * - GET /friends без токена → 401
 * - GET /friends с невалидным токеном → 401
 *
 * Полные сценарии (добавление/удаление друга) требуют БД с пользователями и токенами —
 * можно поднять H2 in-memory в testApplication и создать пользователя + токен перед тестами.
 */
class FriendsRoutingTest {

    private fun Application.testModule() {
        routing {
            configureFriendRouting()
        }
    }

    @Test
    fun getFriendsWithoutToken_returns401() = testApplication {
        application { testModule() }
        val r = client.get("/friends")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals("Missing or invalid token", r.bodyAsText())
    }

    /** С невалидным токеном тоже 401. Требует инициализации БД (например H2 в тестах). */
    @Test
    fun getFriendsWithInvalidToken_returns401() = testApplication {
        application { testModule() }
        val r = client.get("/friends") {
            header("Authorization", "Bearer invalid-token-123")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }
}
