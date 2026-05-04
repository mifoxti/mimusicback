package com.example.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object RecommendationEvents : Table("recommendation_events") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val surface = text("surface")
    val targetType = text("target_type")
    val targetId = long("target_id")
    val interaction = text("interaction")
    val scorePresent = double("score_present").nullable()
    val meta = text("meta").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
