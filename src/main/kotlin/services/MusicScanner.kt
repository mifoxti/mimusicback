package com.example.services

import com.example.database.DatabaseFactory
import com.example.database.Tracks
import com.mpatric.mp3agic.Mp3File
import org.jetbrains.exposed.sql.selectAll
import java.io.File
import org.jetbrains.exposed.sql.insert
import java.security.MessageDigest

class MusicScanner(private val musicDir: File) {

    suspend fun scanAndUpdateDatabase() = DatabaseFactory.dbQuery {
        musicDir.walk()
            .filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
            .forEach { mp3File ->
                val fileHash = calculateFileHash(mp3File)

                // Проверяем, есть ли уже такой трек в БД
                val existingTrack = Tracks.selectAll().where  { Tracks.fileHash eq fileHash }.firstOrNull()

                if (existingTrack == null) {
                    addNewTrack(mp3File, fileHash)
                }
            }
    }

    private fun addNewTrack(file: File, fileHash: String) {
        val mp3 = try { Mp3File(file) } catch (e: Exception) { return }

        val title = mp3.id3v2Tag?.title ?: file.nameWithoutExtension
        val artist = mp3.id3v2Tag?.artist
        val duration = mp3.lengthInSeconds.toInt()

        var albumArt: ByteArray? = null
        mp3.id3v2Tag?.albumImage?.let { imageData ->
            if (!imageData.mimeType.isNullOrEmpty()) {
                albumArt = imageData.imageData
            }
        }

        Tracks.insert {
            it[path] = file.absolutePath
            it[title] = title
            it[artist] = artist
            it[duration] = duration
            it[albumArt] = albumArt
            it[fileHash] = fileHash
        }
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