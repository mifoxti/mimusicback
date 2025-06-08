package com.example.services

import com.example.database.DatabaseFactory
import com.example.database.Tracks
import com.mpatric.mp3agic.Mp3File
import org.jetbrains.exposed.sql.selectAll
import java.io.File
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest

class MusicScanner(private val musicDir: File) {

    suspend fun scanAndUpdateDatabase() = DatabaseFactory.dbQuery {
        musicDir.walk()
            .filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
            .forEach { mp3File ->
                val fileHash = calculateFileHash(mp3File)

                // Проверяем, есть ли уже такой трек в БД
                val existingTrack = Tracks.selectAll()
                    .where { Tracks.fileHash eq fileHash }
                    .firstOrNull()

                if (existingTrack == null) {
                    addNewTrack(mp3File, fileHash)
                }
            }
    }

    private suspend fun addNewTrack(file: File, fileHash: String) = DatabaseFactory.dbQuery {
        val mp3 = try { Mp3File(file) } catch (e: Exception) {
            // Логируем ошибку, если нужно
            println("Error parsing MP3 file ${file.name}: ${e.message}")
            return@dbQuery
        }

        val title = mp3.id3v2Tag?.title ?: file.nameWithoutExtension
        val artist = mp3.id3v2Tag?.artist
        val duration = mp3.lengthInSeconds.toInt()
        val coverArt = mp3.id3v2Tag?.albumImage



        Tracks.insert {
            it[Tracks.path] = file.absolutePath
            it[Tracks.title] = title
            it[Tracks.artist] = artist
            it[Tracks.duration] = duration
            it[Tracks.coverArt] = coverArt
            it[Tracks.fileHash] = fileHash
        }
        println("Succesfully added ${file.name}")
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