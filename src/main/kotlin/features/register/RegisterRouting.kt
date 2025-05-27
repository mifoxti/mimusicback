package com.example.features.register

import com.example.database.UserTokens
import com.example.database.Users
import com.example.utils.isValidEmail
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.*

fun Application.configureRegisterRouting() {
    routing {
        post("/register") {
            val receive = try {
                call.receive<RegisterReceiveRemote>()
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
                receive.login.length > 45 -> {
                    call.respond(HttpStatusCode.BadRequest, "Login too long")
                    return@post
                }
                receive.password.isBlank() -> {
                    call.respond(HttpStatusCode.BadRequest, "Password cannot be empty")
                    return@post
                }
                receive.password.length > 100 -> {
                    call.respond(HttpStatusCode.BadRequest, "Password too long")
                    return@post
                }
                receive.email?.isValidEmail() == false -> {
                    call.respond(HttpStatusCode.BadRequest, "Invalid email format")
                    return@post
                }
            }

            try {
                val userExists = newSuspendedTransaction {
                    Users.selectAll().where { Users.username eq receive.login }.count() > 0
                }

                if (userExists) {
                    call.respond(HttpStatusCode.Conflict, "User already exists")
                    return@post
                }

                // В реальном приложении пароль должен хешироваться!
                val userId = newSuspendedTransaction {
                    Users.insert {
                        it[username] = receive.login
                        it[password] = receive.password // Внимание: здесь должен быть хеш пароля!
                        it[email] = receive.email
                        it[thoughts] = null
                    } get Users.id
                }

                val token = UUID.randomUUID().toString()

                newSuspendedTransaction {
                    UserTokens.insert {
                        it[UserTokens.userId] = userId
                        it[UserTokens.token] = token
                    }
                }

                call.respond(RegisterResponseRemote(token = token))
            } catch (e: Exception) {
                application.log.error("Registration failed", e)
                call.respond(HttpStatusCode.InternalServerError, "Registration failed")
            }
        }
    }
}