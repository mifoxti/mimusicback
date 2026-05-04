package com.example.database

import org.jetbrains.exposed.sql.Table

object PlaylistTracks : Table("playlist_tracks") {
    val playlistId = long("playlist_id").references(Playlists.id)
    val trackId = long("track_id").references(Tracks.id)
    val position = integer("position")

    override val primaryKey = PrimaryKey(playlistId, position)
}
