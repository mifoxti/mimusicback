package com.example.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database

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

    }

}