package com.example.features.thoughts

import kotlinx.serialization.Serializable

@Serializable
data class ThoughtFeedItemRemote(
    val id: Long,
    val authorUserId: Int,
    val authorNickname: String,
    val bodyText: String?,
    val createdAt: String?,
    val attachmentType: Int?,
    val attachmentTrackId: Int?,
    val attachmentPlaylistId: Int?,
    val attachmentTrackTitle: String?,
    val attachmentTrackArtist: String?,
    val attachmentPlaylistTitle: String?,
    val isFriend: Boolean = false,
)

@Serializable
data class ThoughtCreateReceive(
    val bodyText: String,
    val attachmentType: Int? = null,
    val attachmentTrackId: Long? = null,
    val attachmentPlaylistId: Long? = null,
)
