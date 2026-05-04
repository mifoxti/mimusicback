package com.example.features.thoughts

import com.example.database.Thoughts
import com.example.database.Users
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.SortOrder
import java.time.OffsetDateTime

fun Application.configureThoughtsRouting() {
    routing {
        post("/users/{id}/thought") {
            val userId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "User ID is required")
                return@post
            }

            val newThought = call.request.queryParameters["th"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Thought parameter 'th' is required")
                return@post
            }

            try {
                val userExists = newSuspendedTransaction {
                    Users.selectAll().where { Users.id eq userId }.count() > 0
                }

                if (!userExists) {
                    call.respond(HttpStatusCode.NotFound, "User not found")
                    return@post
                }

                newSuspendedTransaction {
                    Thoughts.insert {
                        it[Thoughts.authorUserId] = userId
                        it[Thoughts.bodyText] = newThought
                        it[Thoughts.attachmentType] = null
                        it[Thoughts.attachmentTrackId] = null
                        it[Thoughts.attachmentPlaylistId] = null
                        it[Thoughts.createdAt] = OffsetDateTime.now()
                        it[Thoughts.updatedAt] = OffsetDateTime.now()
                    }
                }

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "status" to "success",
                        "message" to "Thought updated successfully",
                    ),
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error updating thought")
            }
        }

        get("/users/{id}/thought") {
            val userId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "User ID is required")
                return@get
            }

            try {
                val thought = newSuspendedTransaction {
                    Thoughts.selectAll().where { Thoughts.authorUserId eq userId }
                        .orderBy(Thoughts.id, SortOrder.DESC)
                        .limit(1)
                        .map { it[Thoughts.bodyText] }
                        .firstOrNull()
                }

                if (thought != null) {
                    call.respond(HttpStatusCode.OK, mapOf("thought" to thought))
                } else {
                    call.respond(HttpStatusCode.NotFound, "User not found or no thought")
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error fetching thought")
            }
        }
    }
}
