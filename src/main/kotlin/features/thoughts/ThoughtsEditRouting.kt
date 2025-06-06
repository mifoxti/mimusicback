package com.example.features.thoughts

import com.example.database.DatabaseFactory
import com.example.database.Tracks
import com.example.database.Users
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update


fun Application.configureThoughtsRouting() {
    routing {
        post("/users/{id}/thought") {
            val userId = call.parameters["id"]?.toIntOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "User ID is required")
                return@post
            }

            val newThought = call.request.queryParameters["th"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Thought parameter 'th' is required")
                return@post
            }

            try {
                val updated = newSuspendedTransaction {
                    val userExists = Users.selectAll().where { Users.id eq userId }.count() > 0
                    if (!userExists) {
                        return@newSuspendedTransaction false
                    }

                    Users.update({ Users.id eq userId }) {
                        it[Users.thoughts] = newThought
                    } > 0
                }

                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf(
                        "status" to "success",
                        "message" to "Thought updated successfully"
                    ))
                } else {
                    call.respond(HttpStatusCode.NotFound, "User not found")
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error updating thought")
            }
        }
    }
}