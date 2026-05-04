package com.example.database

import org.jetbrains.exposed.sql.Table

object AlbumTracks : Table("album_tracks") {
    val albumId = long("album_id").references(Albums.id)
    val trackId = long("track_id").references(Tracks.id)
    val position = integer("position")

    override val primaryKey = PrimaryKey(albumId, position)
}
