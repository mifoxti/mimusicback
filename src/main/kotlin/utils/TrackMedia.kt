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

/**
 * Сохраняет уже нормализованные PNG-байты обложки трека в [file_storage].
 */
fun persistTrackCoverPng(trackId: Long, pngBytes: ByteArray): String {
    val storageRoot = fileStorageRoot()
    val relativeKey = "covers/tracks/$trackId.png"
    val dest = File(storageRoot, relativeKey)
    dest.parentFile?.mkdirs()
    dest.writeBytes(pngBytes)
    return relativeKey
}

/**
 * Извлекает встроенную картинку из ID3v2, конвертирует через [SafeImage], сохраняет как PNG.
 * @return относительный ключ в [file_storage] или null, если встроенной нет или конвертация не удалась.
 */
fun persistEmbeddedCoverFromMp3File(audioFile: File, trackId: Long, maxInputBytes: Int): String? {
    if (!audioFile.isFile) return null
    val raw = try {
        Mp3File(audioFile).id3v2Tag?.albumImage
    } catch (_: Exception) {
        null
    } ?: return null
    return try {
        val png = SafeImage.rasterToLosslessPngBytes(raw, maxInputBytes)
        persistTrackCoverPng(trackId, png)
    } catch (_: Exception) {
        null
    }
}

/**
 * Удаляет с диска аудиофайл трека и отдельный файл обложки (если были в хранилищах).
 * Ошибки глушатся — вызывать после успешного удаления строки в БД.
 */
fun deleteTrackMediaFiles(audioStorageKey: String?, coverStorageKey: String?) {
    try {
        audioFileForTrack(audioStorageKey)?.takeIf { it.isFile }?.delete()
    } catch (_: Exception) {
    }
    try {
        if (!coverStorageKey.isNullOrBlank()) {
            val cf = File(fileStorageRoot(), coverStorageKey)
            if (cf.isFile) cf.delete()
        }
    } catch (_: Exception) {
    }
}
