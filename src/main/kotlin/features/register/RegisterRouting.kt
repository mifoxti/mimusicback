package com.example.features.register

import com.example.config.fileStorageRoot
import com.example.database.AuthSessions
import com.example.database.Users
import com.example.utils.DefaultIdentityAvatar
import com.example.utils.isInviteCodeAccepted
import com.example.utils.isValidEmail
import com.example.utils.sha256Hex
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.io.File
import java.time.OffsetDateTime
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

            when {
                receive.login.isBlank() -> {
                    call.respond(HttpStatusCode.BadRequest, "Nickname cannot be empty")
                    return@post
                }
                receive.login.length > 255 -> {
                    call.respond(HttpStatusCode.BadRequest, "Nickname too long")
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

            if (!isInviteCodeAccepted(receive.inviteCode)) {
                call.respond(HttpStatusCode.BadRequest, "Invalid or missing invite code")
                return@post
            }

            try {
                val nicknameTaken = newSuspendedTransaction {
                    Users.selectAll().where { Users.nickname eq receive.login.trim() }.any()
                }
                if (nicknameTaken) {
                    call.respond(HttpStatusCode.Conflict, "User already exists")
                    return@post
                }

                val email = receive.email?.trim()?.ifEmpty { null }
                if (email != null) {
                    val emailTaken = newSuspendedTransaction {
                        Users.selectAll().where { Users.email eq email }.any()
                    }
                    if (emailTaken) {
                        call.respond(HttpStatusCode.Conflict, "Email already registered")
                        return@post
                    }
                }

                val userId = newSuspendedTransaction {
                    Users.insert {
                        it[Users.nickname] = receive.login.trim()
                        it[Users.passwordHash] = sha256Hex(receive.password)
                        it[Users.email] = email
                        it[Users.avatarStorageKey] = null
                        it[Users.bio] = null
                        it[Users.createdAt] = OffsetDateTime.now()
                        it[Users.updatedAt] = OffsetDateTime.now()
                        it[Users.isAdmin] = false
                    } get Users.id
                }

                DefaultIdentityAvatar.bytes()?.let { png ->
                    if (png.isNotEmpty()) {
                        val relativeKey = "avatars/$userId.png"
                        val dest = File(fileStorageRoot(), relativeKey)
                        dest.parentFile?.mkdirs()
                        dest.writeBytes(png)
                        newSuspendedTransaction {
                            Users.update({ Users.id eq userId }) {
                                it[Users.avatarStorageKey] = relativeKey
                                it[Users.updatedAt] = OffsetDateTime.now()
                            }
                        }
                    }
                }

                val token = UUID.randomUUID().toString()

                newSuspendedTransaction {
                    AuthSessions.insert {
                        it[AuthSessions.userId] = userId
                        it[AuthSessions.tokenHash] = sha256Hex(token)
                        it[AuthSessions.ipAddress] = null
                        it[AuthSessions.deviceLabel] = null
                    }
                }

                call.respond(
                    RegisterResponseRemote(
                        token = token,
                        id = userId.toInt(),
                        email = email,
                        nickname = receive.login.trim(),
                    ),
                )
            } catch (e: Exception) {
                application.log.error("Registration failed", e)
                call.respond(HttpStatusCode.InternalServerError, "Registration failed")
            }
        }
    }
}
