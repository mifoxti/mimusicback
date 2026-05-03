package com.example.features.profile

import com.example.database.Users
import com.example.utils.currentUserId
import com.example.utils.isValidEmail
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

private sealed class ProfileUpdateResult {
    data object Ok : ProfileUpdateResult()
    data object ConflictNickname : ProfileUpdateResult()
    data object ConflictEmail : ProfileUpdateResult()
}

fun Application.configureProfileRouting() {
    routing {
        get("/me") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val me = newSuspendedTransaction {
                Users.selectAll().where { Users.id eq uid }.singleOrNull()
            } ?: run {
                call.respond(HttpStatusCode.NotFound, "User not found")
                return@get
            }
            call.respond(
                MeResponseRemote(
                    id = me[Users.id].toInt(),
                    email = me[Users.email],
                    nickname = me[Users.nickname],
                    bio = me[Users.bio],
                ),
            )
        }

        put("/me") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@put
            }
            val body = try {
                call.receive<MePatchReceiveRemote>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                return@put
            }
            if (body.nickname.isNullOrBlank() && body.email == null) {
                call.respond(HttpStatusCode.BadRequest, "Nothing to update")
                return@put
            }
            if (body.email != null && body.email.isNotBlank() && body.email.isValidEmail() == false) {
                call.respond(HttpStatusCode.BadRequest, "Invalid email")
                return@put
            }

            val outcome = try {
                newSuspendedTransaction {
                    if (!body.nickname.isNullOrBlank()) {
                        val nick = body.nickname.trim()
                        val taken = Users.selectAll().where {
                            (Users.nickname eq nick) and (Users.id neq uid)
                        }.any()
                        if (taken) return@newSuspendedTransaction ProfileUpdateResult.ConflictNickname
                    }
                    if (!body.email.isNullOrBlank()) {
                        val emailNorm = body.email.trim()
                        val takenEmail = Users.selectAll().where {
                            (Users.email eq emailNorm) and (Users.id neq uid)
                        }.any()
                        if (takenEmail) return@newSuspendedTransaction ProfileUpdateResult.ConflictEmail
                    }
                    Users.update({ Users.id eq uid }) {
                        if (!body.nickname.isNullOrBlank()) {
                            it[Users.nickname] = body.nickname.trim()
                        }
                        if (body.email != null) {
                            it[Users.email] = body.email.trim().ifEmpty { null }
                        }
                        it[Users.updatedAt] = OffsetDateTime.now()
                    }
                    ProfileUpdateResult.Ok
                }
            } catch (e: Exception) {
                application.log.error("Profile update failed", e)
                call.respond(HttpStatusCode.InternalServerError, "Update failed")
                return@put
            }

            when (outcome) {
                ProfileUpdateResult.ConflictNickname ->
                    call.respond(HttpStatusCode.Conflict, "Nickname taken")
                ProfileUpdateResult.ConflictEmail ->
                    call.respond(HttpStatusCode.Conflict, "Email taken")
                ProfileUpdateResult.Ok -> {
                    val me = newSuspendedTransaction {
                        Users.selectAll().where { Users.id eq uid }.single()
                    }
                    call.respond(
                        MeResponseRemote(
                            id = me[Users.id].toInt(),
                            email = me[Users.email],
                            nickname = me[Users.nickname],
                            bio = me[Users.bio],
                        ),
                    )
                }
            }
        }
    }
}
