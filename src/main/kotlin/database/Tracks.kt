package com.example.database

import org.jetbrains.exposed.sql.Table

import org.jetbrains.exposed.sql.Blob
import org.jetbrains.exposed.sql.`java-time`.datetime

object Tracks : Table("track") {
    val id = integer("idTrack").autoIncrement()
    val title = varchar("title", 255)
    val artist = varchar("artist", 255).nullable()
    val path = varchar("path", 500).uniqueIndex()
    val duration = integer("duration").nullable()
    val albumArt = blob("album_art").nullable() // Используем Exposed Blob
    val fileHash = varchar("file_hash", 64).uniqueIndex()

    override val primaryKey = PrimaryKey(id)
}