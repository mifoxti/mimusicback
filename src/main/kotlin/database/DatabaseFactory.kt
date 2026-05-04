package com.example.database

import com.example.config.getenv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database

object DatabaseFactory {
    fun init() {
        val jdbcUrl = resolvedJdbcUrl()
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = getenv("DB_USER") ?: getenv("PGUSER") ?: "postgres"
            password = getenv("DB_PASSWORD") ?: getenv("PGPASSWORD") ?: "admin"
            maximumPoolSize = getenv("DB_POOL_SIZE")?.toIntOrNull()?.coerceAtLeast(1) ?: 10
        }

        Database.connect(HikariDataSource(config))
    }

    /**
     * Полный JDBC URL: `DB_JDBC_URL`, иначе `DATABASE_URL` если начинается с `jdbc:`,
     * иначе сборка из DB_HOST / DB_PORT / DB_NAME (или PG*) со значениями по умолчанию как раньше в коде.
     */
    private fun resolvedJdbcUrl(): String {
        getenv("DB_JDBC_URL")?.let { return it }
        getenv("DATABASE_URL")?.takeIf { it.startsWith("jdbc:") }?.let { return it }

        val host = getenv("DB_HOST") ?: "localhost"
        val port = getenv("DB_PORT") ?: "5432"
        val name = getenv("DB_NAME") ?: getenv("PGDATABASE") ?: "music_app"
        return "jdbc:postgresql://$host:$port/$name"
    }
}