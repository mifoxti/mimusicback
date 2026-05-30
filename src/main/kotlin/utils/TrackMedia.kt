package com.example.utils

import com.example.config.fileStorageRoot
import com.example.config.musicStorageDirectory
import com.example.config.uploadMaxBytes
import com.example.config.ffmpegExecutable
import com.example.services.AudioTranscodeService
import com.mpatric.mp3agic.Mp3File
import java.io.File
import java.util.concurrent.TimeUnit

fun List<String>?.primaryArtist(): String = this?.firstOrNull().orEmpty()

fun audioFileForTrack(audioStorageKey: String?): File? {
    if (audioStorageKey.isNullOrBlank()) return null
    val candidate = File(audioStorageKey)
    return if (candidate.isAbsolute) candidate else File(musicStorageDirectory(), audioStorageKey)
}

/**
 * Только сохранённый PNG обложки ([coverStorageKey]). Без разбора аудио — клиент грузит [GET /tracks/{id}/cover].
 */
fun readTrackCoverBytes(audioStorageKey: String?, coverStorageKey: String?): ByteArray? {
    if (coverStorageKey.isNullOrBlank()) return null
    val coverFile = File(fileStorageRoot(), coverStorageKey)
    if (!coverFile.isFile) return null
    return coverFile.readBytes()
}

/** Не встраивать в JSON-списки — только URL `/tracks/{id}/cover` (иначе base64 раздувает ответ). */
fun coverBase64ForApiList(@Suppress("UNUSED_PARAMETER") audioStorageKey: String?, @Suppress("UNUSED_PARAMETER") coverStorageKey: String?): String? = null

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
 * Извлекает встроенную обложку из исходного аудио (MP3 ID3 или картинка в M4A/MP4 через ffmpeg).
 * @return PNG-байты или null.
 */
fun extractEmbeddedCoverPngBytes(audioFile: File, maxInputBytes: Int): ByteArray? {
    if (!audioFile.isFile) return null
    val headSize = minOf(16384, audioFile.length().toInt().coerceAtLeast(0))
    if (headSize > 0) {
        val head = ByteArray(headSize)
        audioFile.inputStream().use { ins ->
            var off = 0
            while (off < head.size) {
                val n = ins.read(head, off, head.size - off)
                if (n <= 0) break
                off += n
            }
        }
        if (looksLikeMp3Prefix(head)) {
            val raw = try {
                Mp3File(audioFile).id3v2Tag?.albumImage
            } catch (_: Exception) {
                null
            } ?: return null
            return try {
                SafeImage.rasterToLosslessPngBytes(raw, maxInputBytes)
            } catch (_: Exception) {
                null
            }
        }
    }
    return extractEmbeddedCoverPngViaFfmpeg(audioFile, maxInputBytes)
}

private fun extractEmbeddedCoverPngViaFfmpeg(audioFile: File, maxInputBytes: Int): ByteArray? {
    if (!AudioTranscodeService.ffmpegAvailable()) return null
    val mapVariants = listOf(
        listOf("-an"),
        listOf("-map", "0:v:0"),
    )
    for (mapArgs in mapVariants) {
        val png = runFfmpegCoverFrameExtract(audioFile, mapArgs, maxInputBytes) ?: continue
        if (png.isNotEmpty()) return png
    }
    return null
}

private fun runFfmpegCoverFrameExtract(
    audioFile: File,
    mapArgs: List<String>,
    maxInputBytes: Int,
): ByteArray? {
    val out = File.createTempFile("mimusic_emb", ".jpg", audioFile.parentFile)
    try {
        val cmd = mutableListOf(
            ffmpegExecutable(),
            "-y",
            "-i",
            audioFile.absolutePath,
        )
        cmd.addAll(mapArgs)
        cmd.addAll(listOf("-frames:v", "1", "-q:v", "2", out.absolutePath))
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val p = pb.start()
        p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(8, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return null
        }
        if (p.exitValue() != 0 || !out.isFile || out.length() == 0L) return null
        return SafeImage.rasterToLosslessPngBytes(out.readBytes(), maxInputBytes)
    } catch (_: Exception) {
        return null
    } finally {
        out.delete()
    }
}

/**
 * Извлекает встроенную картинку из ID3v2, конвертирует через [SafeImage], сохраняет как PNG.
 * @return относительный ключ в [file_storage] или null, если встроенной нет или конвертация не удалась.
 */
fun persistEmbeddedCoverFromMp3File(audioFile: File, trackId: Long, maxInputBytes: Int): String? {
    val png = extractEmbeddedCoverPngBytes(audioFile, maxInputBytes) ?: return null
    return try {
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
