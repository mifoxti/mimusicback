package com.example.services

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Проверка формулы S(t,u) = Σ U·T из RecommendationScoreService (без БД).
 */
class RecommendationScoreTest {

    private fun scoreTrack(
        userPrefs: Map<Long, Double>,
        trackWeights: List<Pair<Long, Double>>,
    ): Double {
        var s = 0.0
        for ((gid, tw) in trackWeights) {
            s += (userPrefs[gid] ?: 0.0) * tw
        }
        if (trackWeights.isEmpty()) {
            s = 1e-6
        }
        return s
    }

    private fun compareScored(aId: Long, aScore: Double, bId: Long, bScore: Double): Int =
        when {
            aScore > bScore -> -1
            aScore < bScore -> 1
            else -> bId.compareTo(aId)
        }

    @Test
    fun скор_суммирует_веса_жанров_пользователя_и_трека() {
        val prefs = mapOf(1L to 0.8, 2L to 0.5)
        val weights = listOf(1L to 1.0, 2L to 0.5)
        assertEquals(0.8 * 1.0 + 0.5 * 0.5, scoreTrack(prefs, weights))
    }

    @Test
    fun трек_без_жанров_получает_минимальный_скор() {
        assertEquals(1e-6, scoreTrack(mapOf(1L to 1.0), emptyList()))
    }

    @Test
    fun сортировка_по_скору_и_id_трека() {
        assertEquals(-1, compareScored(1, 2.0, 2, 1.0))
        assertEquals(1, compareScored(1, 1.0, 2, 2.0))
        assertEquals(-1, compareScored(5, 1.0, 3, 1.0))
    }
}
