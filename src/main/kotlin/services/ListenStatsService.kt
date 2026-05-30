package com.example.services

import com.example.database.ListenEvents
import com.example.features.studio.PlaysByDayRemote
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate

object ListenStatsService {

    suspend fun playCountsByTrackId(trackIds: Collection<Long>): Map<Long, Int> =
        newSuspendedTransaction {
            if (trackIds.isEmpty()) return@newSuspendedTransaction emptyMap()
            ListenEvents.selectAll()
                .where { ListenEvents.trackId inList trackIds.distinct() }
                .groupBy { it[ListenEvents.trackId] }
                .mapValues { it.value.size }
        }

    suspend fun totalPlaysForTracks(trackIds: Collection<Long>): Int =
        newSuspendedTransaction {
            if (trackIds.isEmpty()) return@newSuspendedTransaction 0
            ListenEvents.selectAll()
                .where { ListenEvents.trackId inList trackIds.distinct() }
                .count()
                .toInt()
        }

    suspend fun uniqueListenersForTracks(trackIds: Collection<Long>): Int =
        newSuspendedTransaction {
            if (trackIds.isEmpty()) return@newSuspendedTransaction 0
            ListenEvents.selectAll()
                .where { ListenEvents.trackId inList trackIds.distinct() }
                .map { it[ListenEvents.userId] }
                .distinct()
                .size
        }

    suspend fun playsByDayForTracks(trackIds: Collection<Long>, days: Int = 14): List<PlaysByDayRemote> =
        newSuspendedTransaction {
            if (trackIds.isEmpty()) return@newSuspendedTransaction emptyList()
            val events = ListenEvents.selectAll()
                .where { ListenEvents.trackId inList trackIds.distinct() }
                .mapNotNull { row ->
                    row[ListenEvents.startedAt]?.toLocalDate()
                }
            val today = LocalDate.now()
            val span = days.coerceIn(1, 90)
            (0 until span).map { offset ->
                val day = today.minusDays((span - 1 - offset).toLong())
                PlaysByDayRemote(
                    date = day.toString(),
                    count = events.count { it == day },
                )
            }
        }

    suspend fun playCountForTrack(trackId: Long): Int =
        playCountsByTrackId(listOf(trackId))[trackId] ?: 0

    suspend fun uniqueListenersForTrack(trackId: Long): Int =
        newSuspendedTransaction {
            ListenEvents.selectAll()
                .where { ListenEvents.trackId eq trackId }
                .map { it[ListenEvents.userId] }
                .distinct()
                .size
        }
}
