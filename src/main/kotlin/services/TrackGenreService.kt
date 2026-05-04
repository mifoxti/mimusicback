package com.example.services

import com.example.database.AlbumGenres
import com.example.database.Genres
import com.example.database.TrackGenres
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

object TrackGenreService {

    suspend fun genreIdBySlug(slug: String): Long? = newSuspendedTransaction {
        Genres.selectAll().where { Genres.slug eq slug }.singleOrNull()?.get(Genres.id)
    }

    suspend fun replaceTrackGenres(
        trackId: Long,
        slugs: List<String>,
        source: String = "uploader",
        normalizeWeights: Boolean = false,
    ) = newSuspendedTransaction {
        TrackGenres.deleteWhere { TrackGenres.trackId eq trackId }
        val pairs = slugs.mapNotNull { s ->
            val slug = s.trim().lowercase().replace('-', '_')
            if (slug.isEmpty()) return@mapNotNull null
            val gid = Genres.selectAll().where { Genres.slug eq slug }.singleOrNull()?.get(Genres.id)
                ?: return@mapNotNull null
            gid to slug
        }.distinctBy { it.first }
        if (pairs.isEmpty()) return@newSuspendedTransaction
        val n = pairs.size
        val w = if (normalizeWeights && n > 0) 1.0 / n else 1.0
        for ((gid, _) in pairs) {
            TrackGenres.insert {
                it[TrackGenres.trackId] = trackId
                it[TrackGenres.genreId] = gid
                it[TrackGenres.weight] = w
                it[TrackGenres.genreSource] = source
            }
        }
    }

    suspend fun replaceAlbumGenres(
        albumId: Long,
        slugs: List<String>,
        source: String = "owner",
        normalizeWeights: Boolean = false,
    ) = newSuspendedTransaction {
        AlbumGenres.deleteWhere { AlbumGenres.albumId eq albumId }
        val pairs = slugs.mapNotNull { s ->
            val slug = s.trim().lowercase().replace('-', '_')
            if (slug.isEmpty()) return@mapNotNull null
            val gid = Genres.selectAll().where { Genres.slug eq slug }.singleOrNull()?.get(Genres.id)
                ?: return@mapNotNull null
            gid
        }.distinct()
        if (pairs.isEmpty()) return@newSuspendedTransaction
        val n = pairs.size
        val w = if (normalizeWeights && n > 0) 1.0 / n else 1.0
        for (gid in pairs) {
            AlbumGenres.insert {
                it[AlbumGenres.albumId] = albumId
                it[AlbumGenres.genreId] = gid
                it[AlbumGenres.weight] = w
                it[AlbumGenres.genreSource] = source
            }
        }
    }

    suspend fun loadGenreSlugsForTracks(trackIds: List<Long>): Map<Long, List<String>> = newSuspendedTransaction {
        if (trackIds.isEmpty()) return@newSuspendedTransaction emptyMap()
        val rows = TrackGenres.selectAll().where { TrackGenres.trackId inList trackIds }
        val genreIds = rows.map { it[TrackGenres.genreId] }.distinct()
        if (genreIds.isEmpty()) return@newSuspendedTransaction emptyMap()
        val slugById = Genres.selectAll().where { Genres.id inList genreIds }
            .associate { it[Genres.id] to it[Genres.slug] }
        rows.groupBy { it[TrackGenres.trackId] }
            .mapValues { (_, r) ->
                r.sortedByDescending { it[TrackGenres.weight] }
                    .mapNotNull { slugById[it[TrackGenres.genreId]] }
            }
    }

    suspend fun loadGenreSlugsForTrack(trackId: Long): List<String> =
        loadGenreSlugsForTracks(listOf(trackId))[trackId].orEmpty()

    suspend fun loadGenreSlugsForAlbum(albumId: Long): List<String> = newSuspendedTransaction {
        val rows = AlbumGenres.selectAll().where { AlbumGenres.albumId eq albumId }
        val genreIds = rows.map { it[AlbumGenres.genreId] }.distinct()
        if (genreIds.isEmpty()) return@newSuspendedTransaction emptyList()
        val slugById = Genres.selectAll().where { Genres.id inList genreIds }
            .associate { it[Genres.id] to it[Genres.slug] }
        rows.sortedByDescending { it[AlbumGenres.weight] }
            .mapNotNull { slugById[it[AlbumGenres.genreId]] }
    }
}
