package com.example.database

import org.jetbrains.exposed.sql.Table

object Tracks : Table("track") {
    val id = integer("idtrack").autoIncrement()
    val title = varchar("title", 255)
    val artist = varchar("artist", 255).nullable()
    val path = varchar("path", 500).uniqueIndex()
    val duration = integer("duration").nullable()
    val coverArt = binary("album_art").nullable()
    val fileHash = varchar("file_hash", 64).uniqueIndex()

    override val primaryKey = PrimaryKey(id)
}