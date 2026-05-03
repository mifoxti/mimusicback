package com.example.utils

import com.example.config.fileStorageRoot
import com.example.config.musicStorageDirectory
import com.mpatric.mp3agic.Mp3File
import java.io.File
import java.util.Base64

fun List<String>?.primaryArtist(): String = this?.firstOrNull().orEmpty()

fun audioFileForTrack(audioStorageKey: String?): File? {
    if (audioStorageKey.isNullOrBlank()) return null
    val candidate = File(audioStorageKey)
    return if (candidate.isAbsolute) candidate else File(musicStorageDirectory(), audioStorageKey)
}

fun readTrackCoverBytes(audioStorageKey: String?, coverStorageKey: String?): ByteArray? {
    val fileRoot = fileStorageRoot()
    if (!coverStorageKey.isNullOrBlank()) {
        val coverFile = File(fileRoot, coverStorageKey)
        if (coverFile.isFile) return coverFile.readBytes()
    }
    val audio = audioFileForTrack(audioStorageKey) ?: return null
    if (!audio.isFile) return null
    return try {
        Mp3File(audio).id3v2Tag?.albumImage
    } catch (_: Exception) {
        null
    }
}

fun coverBase64(audioStorageKey: String?, coverStorageKey: String?): String? =
    readTrackCoverBytes(audioStorageKey, coverStorageKey)?.let { Base64.getEncoder().encodeToString(it) }
