package com.example.features.login

import com.example.database.UserTokens
import com.example.database.Users
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.*

fun Application.configureLoginRouting() {
    routing {
        post("/login") {
            val receive = try {
                call.receive<LoginReceiveRemote>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid request data")
                return@post
            }

            // Валидация данных
            when {
                receive.login.isBlank() -> {
                    call.respond(HttpStatusCode.BadRequest, "Login cannot be empty")
                    return@post
                }
                receive.password.isBlank() -> {
                    call.respond(HttpStatusCode.BadRequest, "Password cannot be empty")
                    return@post
                }
            }

            try {
                val user = newSuspendedTransaction {
                    Users.selectAll().where  { Users.username eq receive.login }
                        .singleOrNull()
                }

                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid login or password")
                    return@post
                }

                // Внимание: здесь должно быть сравнение хешей паролей!
                if (user[Users.password] != receive.password) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid login or password")
                    return@post
                }

                // Генерация нового токена
                val token = UUID.randomUUID().toString()
                val userId = user[Users.id]
                newSuspendedTransaction {
                    // Удаляем старые токены (опционально)
                    UserTokens.deleteWhere { UserTokens.userId eq user[Users.id] }

                    UserTokens.insert {
                        it[UserTokens.userId] = user[Users.id]
                        it[UserTokens.token] = token
                    }
                }

                call.respond(LoginResponseRemote(token = token, id = userId))
            } catch (e: Exception) {
                application.log.error("Login failed", e)
                call.respond(HttpStatusCode.InternalServerError, "Login failed")
            }
        }
    }
}