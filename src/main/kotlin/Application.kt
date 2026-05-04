package com.example

import com.example.config.fileStorageRoot
import com.example.config.musicStorageDirectory
import com.example.database.DatabaseFactory
import com.example.database.ensureBootstrapTestInviteKey
import com.example.database.ensureBootstrapUploaderUserId
import com.example.database.ensureGenresSeeded
import com.example.database.ensureSocialGraphTables
import com.example.colisten.configureColistenRouting
import com.example.colisten.configureColistenWebSocket
import com.example.features.artist.configureArtistRouting
import com.example.features.friends.configureFriendRouting
import com.example.features.likes.configureLikeRouting
import com.example.features.login.configureLoginRouting
import com.example.features.profile.configureProfileRouting
import com.example.features.loved.configureLovedTracksRouting
import com.example.features.register.configureRegisterRouting
import com.example.features.search.configureSearchRouting
import com.example.features.thoughts.configureThoughtsRouting
import com.example.features.tracks.configureTrackRouting
import com.example.features.media.configureFileServingRouting
import com.example.features.upload.configureUploadRouting
import com.example.features.genres.configureGenreRouting
import com.example.features.preferences.configureUserGenrePreferencesRouting
import com.example.features.recommendations.configureRecommendationRouting
import com.example.features.albums.configureAlbumRouting
import com.example.features.playlists.configurePlaylistRouting
import com.example.services.MusicScanner
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val musicStorageDir = musicStorageDirectory()
    fileStorageRoot()

    DatabaseFactory.init()
    val scannerUploaderId = ensureBootstrapUploaderUserId()
    ensureBootstrapTestInviteKey()
    ensureGenresSeeded()
    ensureSocialGraphTables()
    configureSecurity()
    configureHTTP()
    configureSerialization()
    configureRouting()
    configureLikeRouting()
    configureLoginRouting()
    configureRegisterRouting()
    configureProfileRouting()
    configureUploadRouting()
    configureGenreRouting()
    configureUserGenrePreferencesRouting()
    configureRecommendationRouting()
    configureAlbumRouting()
    configurePlaylistRouting()
    configureTrackRouting()
    configureFileServingRouting()
    configureSearchRouting()
    configureThoughtsRouting()
    configureLovedTracksRouting()
    configureArtistRouting()
    configureFriendRouting()
    configureColistenRouting()
    configureColistenWebSocket()

    val musicScanner = MusicScanner(musicStorageDir, scannerUploaderId)

    // Запускаем сканирование каждые 60 минут
    CoroutineScope(Dispatchers.IO).launch {
        while (true) {
            delay(TimeUnit.SECONDS.toMillis(1))
            musicScanner.scanAndUpdateDatabase()
        }
    }
}