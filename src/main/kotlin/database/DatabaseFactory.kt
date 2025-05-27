package com.example.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init() {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://localhost:5432/music_app"
            driverClassName = "org.postgresql.Driver"
            username = "postgres"
            password = "1234"
            maximumPoolSize = 10
        }

        Database.connect(HikariDataSource(config))

        transaction {
            // Сначала проверяем существование таблиц
            if (!Users.exists()) {
                SchemaUtils.create(Users)
            }
            if (!UserTokens.exists()) {
                SchemaUtils.create(UserTokens)
            }

            // Затем добавляем недостающие колонки
            SchemaUtils.createMissingTablesAndColumns(Users, UserTokens)
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}