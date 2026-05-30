package com.example.features.users

import com.example.features.friends.NowPlayingRemote
import com.example.features.playlists.PlaylistListItemRemote
import com.example.features.tracks.TrackRemote
import kotlinx.serialization.Serializable

@Serializable
data class UserProfileThoughtRemote(
    val id: Long,
    val bodyText: String?,
    val createdAt: String?,
    val attachmentType: Int?,
    val attachmentTrackId: Int?,
    val attachmentPlaylistId: Int?,
    val attachmentTrackTitle: String?,
    val attachmentTrackArtist: String? = null,
    val attachmentPlaylistTitle: String?,
)

@Serializable
data class UserPublicProfileRemote(
    val id: Int,
    val nickname: String,
    val bio: String?,
    val avatarStorageKey: String?,
    val online: Boolean = false,
    val nowPlaying: NowPlayingRemote?,
    val publicPlaylists: List<PlaylistListItemRemote>,
    val uploadedTracks: List<TrackRemote>,
    val recentThoughts: List<UserProfileThoughtRemote> = emptyList(),
)
