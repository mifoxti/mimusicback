package com.example.services

import com.example.database.Tracks
import com.mpatric.mp3agic.Mp3File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.io.File
import java.security.MessageDigest

class MusicScanner(private val musicDir: File) {

    suspend fun scanAndUpdateDatabase() {
        val mp3Files = withContext(Dispatchers.IO) {
            musicDir.walk()
                .filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
                .toList()
        }

        for (file in mp3Files) {
            val fileHash = withContext(Dispatchers.IO) { calculateFileHash(file) }

            val exists = newSuspendedTransaction {
                Tracks.selectAll().where { Tracks.fileHash eq fileHash }.firstOrNull()
            }

            if (exists == null) {
                addNewTrack(file, fileHash)
            }
        }
    }

    private suspend fun addNewTrack(file: File, fileHash: String) {
        val mp3 = try {
            withContext(Dispatchers.IO) { Mp3File(file) }
        } catch (e: Exception) {
            println("Error parsing MP3 file ${file.name}: ${e.message}")
            return
        }

        val title = mp3.id3v2Tag?.title ?: file.nameWithoutExtension
        val artist = mp3.id3v2Tag?.artist
        val duration = mp3.lengthInSeconds.toInt()
        val coverArt = mp3.id3v2Tag?.albumImage

        newSuspendedTransaction {
            Tracks.insert {
                it[path] = file.absolutePath
                it[Tracks.title] = title
                it[Tracks.artist] = artist
                it[Tracks.duration] = duration
                it[Tracks.coverArt] = coverArt
                it[Tracks.fileHash] = fileHash
            }
        }

        println("Successfully added ${file.name}")
    }

    private fun calculateFileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
