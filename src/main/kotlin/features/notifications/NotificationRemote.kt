package com.example.features.notifications

import kotlinx.serialization.Serializable

@Serializable
data class NotificationRemote(
    val id: Long,
    val type: String,
    val actorUserId: Int?,
    val actorNickname: String?,
    val read: Boolean,
    val createdAt: String?,
    val entityRef: String?,
    val entityId: Long?,
    val payloadJson: String?,
)
