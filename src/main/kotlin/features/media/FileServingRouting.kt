package com.example.features.media

import com.example.config.fileStorageRoot
import com.example.database.Albums
import com.example.database.Playlists
import com.example.database.Users
import com.example.utils.DefaultIdentityAvatar
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.io.File

fun Application.configureFileServingRouting() {
    routing {
        get("/playlists/{id}/cover") {
            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val key = newSuspendedTransaction {
                Playlists.selectAll().where { Playlists.id eq id }.singleOrNull()
                    ?.let { it[Playlists.coverStorageKey] }
            }
            if (key.isNullOrBlank()) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val file = File(fileStorageRoot(), key)
            if (!file.isFile) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respond(LocalFileContent(file, ContentType.Image.PNG))
        }

        get("/albums/{id}/cover") {
            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val key = newSuspendedTransaction {
                Albums.selectAll().where { Albums.id eq id }.singleOrNull()
                    ?.let { it[Albums.coverStorageKey] }
            }
            if (key.isNullOrBlank()) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val file = File(fileStorageRoot(), key)
            if (!file.isFile) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respond(LocalFileContent(file, ContentType.Image.PNG))
        }

        get("/users/{id}/avatar") {
            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val key = newSuspendedTransaction {
                Users.selectAll().where { Users.id eq id }.singleOrNull()?.let { it[Users.avatarStorageKey] }
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
    }
}
