package com.example.services

import com.example.database.TrackGenres
import com.example.database.Tracks
import com.example.database.UserGenrePreferences
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

data class ScoredTrackId(val trackId: Long, val score: Double)

object RecommendationScoreService {

    /**
     * Скоринг: сумма user_pref[g] * track_weight[g] по пересечению жанров.
     * Без предпочтений — как каталог: новые сверху (score = 0).
     */
    suspend fun scoredTrackIds(userId: Long, limit: Int): List<ScoredTrackId> = newSuspendedTransaction {
        val prefs = UserGenrePreferences.selectAll()
            .where { UserGenrePreferences.userId eq userId }
            .associate { it[UserGenrePreferences.genreId] to it[UserGenrePreferences.weight] }
        val allTracks = Tracks.selectAll()
            .orderBy(Tracks.id, SortOrder.DESC)
            .filter { it[Tracks.uploaderUserId] != userId }
            .map { it[Tracks.id] }
        if (prefs.isEmpty()) {
            return@newSuspendedTransaction allTracks.take(limit).map { ScoredTrackId(it, 0.0) }
        }
        val byTrack = TrackGenres.selectAll().groupBy { it[TrackGenres.trackId] }
        val scored = allTracks.map { tid ->
            var s = 0.0
            val rows = byTrack[tid].orEmpty()
            for (r in rows) {
                val gid = r[TrackGenres.genreId]
                val tw = r[TrackGenres.weight]
                val uw = prefs[gid] ?: 0.0
                s += uw * tw
            }
            if (rows.isEmpty()) {
                s = 1e-6
            }
            ScoredTrackId(tid, s)
        }
        scored.sortedWith(compareByDescending<ScoredTrackId> { it.score }.thenByDescending { it.trackId })
            .take(limit)
    }
}
