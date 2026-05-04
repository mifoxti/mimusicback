package com.example.features.login

import com.example.database.AuthSessions
import com.example.database.Users
import com.example.utils.sha256Hex
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
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
                val key = receive.login.trim()
                val keyLower = key.lowercase()
                val user = newSuspendedTransaction {
                    Users.selectAll().where {
                        (Users.nickname.lowerCase() eq keyLower) or
                            ((Users.email neq null) and (Users.email.lowerCase() eq keyLower))
                    }.singleOrNull()
                }

                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid login or password")
                    return@post
                }

                if (user[Users.passwordHash] != sha256Hex(receive.password)) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid login or password")
                    return@post
                }

                val token = UUID.randomUUID().toString()
                val userId = user[Users.id]
                newSuspendedTransaction {
                    AuthSessions.deleteWhere { AuthSessions.userId eq user[Users.id] }

                    AuthSessions.insert {
                        it[AuthSessions.userId] = user[Users.id]
                        it[AuthSessions.tokenHash] = sha256Hex(token)
                        it[AuthSessions.ipAddress] = null
                        it[AuthSessions.deviceLabel] = null
                    }
                }

                call.respond(
                    LoginResponseRemote(
                        token = token,
                        id = userId.toInt(),
                        email = user[Users.email],
                        nickname = user[Users.nickname],
                    ),
                )
            } catch (e: Exception) {
                application.log.error("Login failed", e)
                call.respond(HttpStatusCode.InternalServerError, "Login failed")
            }
        }
    }
}
