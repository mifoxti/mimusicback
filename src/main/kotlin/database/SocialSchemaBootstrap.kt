package com.example.database

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.postgresql.util.PSQLException

private fun Throwable.psqlCause(): PSQLException? {
    var c: Throwable? = this
    while (c != null) {
        if (c is PSQLException) return c
        c = c.cause
    }
    return null
}

/** Таблицы этапа 6+ (`friendships`, при необходимости `notifications`) на неполной dev-БД. */
fun ensureSocialGraphTables() {
    transaction {
        SchemaUtils.createMissingTablesAndColumns(Notifications, Friendships, UserNowPlaying, UserPresence)
        for (table in arrayOf(Notifications, Friendships, UserNowPlaying, UserPresence)) {
            try {
                SchemaUtils.create(table)
            } catch (e: Exception) {
                if (e.psqlCause()?.sqlState != "42P07") throw e
            }
        }
    }
}
