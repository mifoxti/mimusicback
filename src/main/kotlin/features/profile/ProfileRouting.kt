package com.example.features.profile

import com.example.config.fileStorageRoot
import com.example.database.InviteKeys
import com.example.database.Users
import com.example.utils.DefaultIdentityAvatar
import com.example.utils.currentUserId
import com.example.utils.isValidEmail
import com.example.utils.normalizeInviteCode
import com.example.utils.sha256Hex
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.io.File
import java.time.OffsetDateTime
import kotlin.random.Random

private sealed class ProfileUpdateResult {
    data object Ok : ProfileUpdateResult()
    data object ConflictNickname : ProfileUpdateResult()
    data object ConflictEmail : ProfileUpdateResult()
}

private val inviteKeyBodyRegex = Regex("^[A-Z0-9]{5}-[A-Z0-9]{5}-[A-Z0-9]{5}$")

private fun generateInviteKeyBody(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    fun segment(): String = List(5) { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    return "${segment()}-${segment()}-${segment()}"
}

private fun isValidInviteKeyBody(normalized: String): Boolean =
    inviteKeyBodyRegex.matches(normalized)

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
                    avatarStorageKey = me[Users.avatarStorageKey],
                ),
            )
        }

        get("/me/avatar") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val key = newSuspendedTransaction {
                Users.selectAll().where { Users.id eq uid }.singleOrNull()?.let { it[Users.avatarStorageKey] }
            }
            val root = fileStorageRoot()
            val file = if (!key.isNullOrBlank()) File(root, key).takeIf { it.isFile } else null
            if (file != null) {
                call.respond(LocalFileContent(file, ContentType.Image.PNG))
                return@get
            }
            val fallback = DefaultIdentityAvatar.bytes()
            if (fallback != null && fallback.isNotEmpty()) {
                call.respondBytes(fallback, ContentType.Image.PNG)
                return@get
            }
            call.respond(HttpStatusCode.NotFound)
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
            val hasNickname = !body.nickname.isNullOrBlank()
            val hasEmail = body.email != null
            val hasBio = body.bio != null
            if (!hasNickname && !hasEmail && !hasBio) {
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
                        if (body.bio != null) {
                            it[Users.bio] = body.bio.trim().ifEmpty { null }
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
                            avatarStorageKey = me[Users.avatarStorageKey],
                        ),
                    )
                }
            }
        }

        put("/me/password") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@put
            }
            val body = try {
                call.receive<MePasswordChangeReceiveRemote>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                return@put
            }
            if (body.currentPassword.isBlank() || body.newPassword.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, "currentPassword and newPassword are required")
                return@put
            }
            if (body.newPassword.length < 6) {
                call.respond(HttpStatusCode.BadRequest, "New password must be at least 6 characters")
                return@put
            }
            if (body.newPassword.length > 100) {
                call.respond(HttpStatusCode.BadRequest, "New password too long")
                return@put
            }
            try {
                val ok = newSuspendedTransaction {
                    val row = Users.selectAll().where { Users.id eq uid }.singleOrNull()
                        ?: return@newSuspendedTransaction false
                    if (row[Users.passwordHash] != sha256Hex(body.currentPassword)) {
                        return@newSuspendedTransaction false
                    }
                    Users.update({ Users.id eq uid }) {
                        it[Users.passwordHash] = sha256Hex(body.newPassword)
                        it[Users.updatedAt] = OffsetDateTime.now()
                    }
                    true
                }
                if (!ok) {
                    call.respond(HttpStatusCode.Unauthorized, "Wrong current password")
                    return@put
                }
                call.respond(HttpStatusCode.NoContent)
            } catch (e: Exception) {
                application.log.error("Password change failed", e)
                call.respond(HttpStatusCode.InternalServerError, "Password change failed")
            }
        }

        get("/me/invite-key") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@get
            }
            val row = newSuspendedTransaction {
                InviteKeys.selectAll().where {
                    (InviteKeys.creatorUserId eq uid) and InviteKeys.revokedAt.isNull()
                }.singleOrNull()
            } ?: run {
                call.respond(HttpStatusCode.NotFound, "No active invite key")
                return@get
            }
            call.respond(MeInviteKeyResponseRemote(keyCode = row[InviteKeys.keyCode]))
        }

        post("/me/invite-key") {
            val uid = call.currentUserId()?.toLong() ?: run {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid token")
                return@post
            }
            val body = try {
                call.receive<MeInviteKeyReceiveRemote>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                return@post
            }
            var chosen = normalizeInviteCode(body.keyCode)
            if (chosen.isEmpty()) {
                var allocated: String? = null
                repeat(12) {
                    if (allocated != null) return@repeat
                    val g = generateInviteKeyBody()
                    val taken = newSuspendedTransaction {
                        InviteKeys.selectAll().where { InviteKeys.keyCode eq g }.any()
                    }
                    if (!taken) allocated = g
                }
                val v = allocated ?: run {
                    call.respond(HttpStatusCode.InternalServerError, "Could not allocate invite key")
                    return@post
                }
                chosen = v
            } else if (!isValidInviteKeyBody(chosen)) {
                call.respond(HttpStatusCode.BadRequest, "Invalid invite key format")
                return@post
            }

            val conflictOther = newSuspendedTransaction {
                InviteKeys.selectAll().where {
                    (InviteKeys.keyCode eq chosen) and (InviteKeys.creatorUserId neq uid)
                }.any()
            }
            if (conflictOther) {
                call.respond(HttpStatusCode.Conflict, "This key is already taken")
                return@post
            }

            val saved = try {
                newSuspendedTransaction {
                    val existing = InviteKeys.selectAll().where { InviteKeys.creatorUserId eq uid }.singleOrNull()
                    if (existing != null) {
                        InviteKeys.update({ InviteKeys.creatorUserId eq uid }) {
                            it[InviteKeys.keyCode] = chosen
                            it[InviteKeys.revokedAt] = null
                            it[InviteKeys.notes] = "User rotated invite key"
                        }
                    } else {
                        InviteKeys.insert {
                            it[InviteKeys.keyCode] = chosen
                            it[InviteKeys.creatorUserId] = uid
                            it[InviteKeys.createdAt] = OffsetDateTime.now()
                            it[InviteKeys.revokedAt] = null
                            it[InviteKeys.notes] = "User invite key"
                        }
                    }
                    chosen
                }
            } catch (e: Exception) {
                application.log.error("invite key save failed", e)
                call.respond(HttpStatusCode.Conflict, "Could not save invite key (duplicate?)")
                return@post
            }
            call.respond(HttpStatusCode.Created, MeInviteKeyResponseRemote(keyCode = saved))
        }
    }
}
