package com.example.database

import org.jetbrains.exposed.sql.Table

object PlaylistLikes : Table("playlist_likes") {
    val playlistId = long("playlist_id").references(Playlists.id)
    val userId = long("user_id").references(Users.id)

    override val primaryKey = PrimaryKey(playlistId, userId)
}
