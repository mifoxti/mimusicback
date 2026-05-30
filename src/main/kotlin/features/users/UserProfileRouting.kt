package com.example.features.users

import com.example.database.PlaylistTracks
import com.example.database.Playlists
import com.example.database.Thoughts
import com.example.database.Tracks
import com.example.database.UserNowPlaying
import com.example.database.UserPresence
import com.example.database.Users
import com.example.features.friends.NowPlayingRemote
import com.example.features.playlists.PlaylistListItemRemote
import com.example.features.tracks.TrackRemote
import com.example.services.TrackGenreService
import com.example.utils.coverBase64ForApiList
import com.example.utils.primaryArtist
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime

fun Application.configureUserProfileRouting() {
    routing {
        get("/users/{id}/profile") {
            val userId = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "Invalid user id")
                return@get
            }
            val bundle = newSuspendedTransaction {
                val userRow = Users.selectAll().where { Users.id eq userId }.singleOrNull()
                    ?: return@newSuspendedTransaction ProfileBundle(null, emptyList(), emptyList(), false, null, emptyList())
                val nickname = userRow[Users.nickname]
                if (nickname.startsWith("__")) {
                    return@newSuspendedTransaction ProfileBundle(null, emptyList(), emptyList(), false, null, emptyList())
                }

                val playlistRows = Playlists.selectAll()
                    .where { (Playlists.userId eq userId) and (Playlists.isPublic eq true) }
                    .orderBy(Playlists.id, SortOrder.DESC)
                    .limit(50)
                    .toList()
                val pids = playlistRows.map { it[Playlists.id] }
                val trackCounts = if (pids.isEmpty()) {
                    emptyMap()
                } else {
                    PlaylistTracks.selectAll().where { PlaylistTracks.playlistId inList pids }
                        .groupBy { it[PlaylistTracks.playlistId] }
                        .mapValues { it.value.size }
                }
                val publicPlaylists = playlistRows.map {
                    PlaylistListItemRemote(
                        id = it[Playlists.id].toInt(),
                        title = it[Playlists.title],
                        isPublic = it[Playlists.isPublic],
                        trackCount = trackCounts[it[Playlists.id]] ?: 0,
                        coverStorageKey = it[Playlists.coverStorageKey],
                    )
                }

                val thoughtRows = Thoughts.selectAll()
                    .where { Thoughts.authorUserId eq userId }
                    .orderBy(Thoughts.id, SortOrder.DESC)
                    .limit(10)
                    .toList()
                val attachTrackIds = thoughtRows.mapNotNull { it[Thoughts.attachmentTrackId] }.distinct()
                val attachPlaylistIds = thoughtRows.mapNotNull { it[Thoughts.attachmentPlaylistId] }.distinct()
                val trackMetaById = if (attachTrackIds.isEmpty()) {
                    emptyMap()
                } else {
                    Tracks.selectAll().where { Tracks.id inList attachTrackIds }
                        .associate {
                            it[Tracks.id] to Pair(
                                it[Tracks.title],
                                it[Tracks.artists].primaryArtist().ifBlank { null },
                            )
                        }
                }
                val playlistTitleById = if (attachPlaylistIds.isEmpty()) {
                    emptyMap()
                } else {
                    Playlists.selectAll().where { Playlists.id inList attachPlaylistIds }
                        .associate { it[Playlists.id] to it[Playlists.title] }
                }
                val recentThoughts = thoughtRows.map { tr ->
                    val tid = tr[Thoughts.attachmentTrackId]
                    val pid = tr[Thoughts.attachmentPlaylistId]
                    UserProfileThoughtRemote(
                        id = tr[Thoughts.id],
                        bodyText = tr[Thoughts.bodyText],
                        createdAt = tr[Thoughts.createdAt]?.toString(),
                        attachmentType = tr[Thoughts.attachmentType],
                        attachmentTrackId = tid?.toInt(),
                        attachmentPlaylistId = pid?.toInt(),
                        attachmentTrackTitle = tid?.let { trackMetaById[it]?.first },
                        attachmentTrackArtist = tid?.let { trackMetaById[it]?.second },
                        attachmentPlaylistTitle = pid?.let { playlistTitleById[it] },
                    )
                }

                val trackRows = Tracks.selectAll()
                    .where { Tracks.uploaderUserId eq userId }
                    .orderBy(Tracks.id, SortOrder.DESC)
                    .limit(50)
                    .toList()

                val onlineThreshold = OffsetDateTime.now().minusSeconds(20)
                val isOnline = UserPresence.selectAll()
                    .where {
                        (UserPresence.userId eq userId) and
                            (UserPresence.lastSeenAt greaterEq onlineThreshold)
                    }
                    .any()
                val freshNowPlayingThreshold = OffsetDateTime.now().minusSeconds(120)
                val npRow = if (isOnline) {
                    UserNowPlaying.selectAll()
                        .where {
                            (UserNowPlaying.userId eq userId) and
                                (UserNowPlaying.updatedAt greaterEq freshNowPlayingThreshold)
                        }
                        .singleOrNull()
                } else {
                    null
                }
                val nowPlaying = npRow?.get(UserNowPlaying.trackId)?.let { tid ->
                    Tracks.selectAll().where { Tracks.id eq tid }.singleOrNull()?.let { tr ->
                        NowPlayingRemote(
                            trackId = tid.toInt(),
                            title = tr[Tracks.title],
                            artist = tr[Tracks.artists].primaryArtist().ifBlank { null },
                        )
                    }
                }

                ProfileBundle(
                    user = userRow,
                    publicPlaylists = publicPlaylists,
                    trackRows = trackRows,
                    online = isOnline,
                    nowPlaying = nowPlaying,
                    recentThoughts = recentThoughts,
                )
            }
            val userRow = bundle.user ?: run {
                call.respond(HttpStatusCode.NotFound, "User not found")
                return@get
            }
            val genreMap = TrackGenreService.loadGenreSlugsForTracks(bundle.trackRows.map { it[Tracks.id] })
            val uploadedTracks = bundle.trackRows.map {
                TrackRemote(
                    id = it[Tracks.id].toInt(),
                    title = it[Tracks.title],
                    artist = it[Tracks.artists].primaryArtist().ifBlank { null },
                    duration = it[Tracks.durationMs]?.div(1000),
                    cover = coverBase64ForApiList(it[Tracks.audioStorageKey], it[Tracks.coverStorageKey]),
                    genres = genreMap[it[Tracks.id]].orEmpty(),
                )
            }
            call.respond(
                UserPublicProfileRemote(
                    id = userId.toInt(),
                    nickname = userRow[Users.nickname],
                    bio = userRow[Users.bio],
                    avatarStorageKey = userRow[Users.avatarStorageKey],
                    online = bundle.online,
                    nowPlaying = bundle.nowPlaying,
                    publicPlaylists = bundle.publicPlaylists,
                    uploadedTracks = uploadedTracks,
                    recentThoughts = bundle.recentThoughts,
                ),
            )
        }
    }
}

private data class ProfileBundle(
    val user: ResultRow?,
    val publicPlaylists: List<PlaylistListItemRemote>,
    val trackRows: List<ResultRow>,
    val online: Boolean,
    val nowPlaying: NowPlayingRemote?,
    val recentThoughts: List<UserProfileThoughtRemote>,
)
