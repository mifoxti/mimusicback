package com.example.services

import com.example.config.ffmpegExecutable
import com.example.config.ffprobeExecutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

data class AudioProbeResult(
    val durationSec: Double?,
    val title: String?,
    val artist: String?,
)

/**
 * Конвертация в AAC в контейнере `.m4a` через внешние `ffmpeg` / `ffprobe`.
 * Пути: [ffmpegExecutable] / [ffprobeExecutable] (см. `FFMPEG_PATH`, `FFMPEG_BIN_DIR` в `.env`).
 */
object AudioTranscodeService {
    private val json = Json { ignoreUnknownKeys = true }

    private fun executableWorks(path: String): Boolean = try {
        val p = ProcessBuilder(path, "-version").start()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    fun ffmpegAvailable(): Boolean =
        executableWorks(ffmpegExecutable()) && executableWorks(ffprobeExecutable())

    fun probe(file: File): AudioProbeResult {
        val out = runCommand(
            listOf(
                ffprobeExecutable(),
                "-v",
                "quiet",
                "-print_format",
                "json",
                "-show_format",
                file.absolutePath,
            ),
        ) ?: return AudioProbeResult(null, null, null)
        return try {
            val root = json.parseToJsonElement(out).jsonObject
            val format = root["format"]?.jsonObject ?: return AudioProbeResult(null, null, null)
            val dur = format["duration"]?.jsonPrimitive?.doubleOrNull
            val tags = format["tags"]?.jsonObject
            val title = tags?.get("title")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val artist = tags?.get("artist")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: tags?.get("album_artist")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            AudioProbeResult(dur, title, artist)
        } catch (_: Exception) {
            AudioProbeResult(null, null, null)
        }
    }

    /**
     * @return сообщение об ошибке или null при успехе
     */
    fun transcodeToM4aAac(input: File, output: File): String? {
        output.parentFile?.mkdirs()
        val pb = ProcessBuilder(
            listOf(
                ffmpegExecutable(),
                "-y",
                "-i",
                input.absolutePath,
                "-vn",
                "-c:a",
                "aac",
                "-b:a",
                "192k",
                "-movflags",
                "+faststart",
                output.absolutePath,
            ),
        )
        pb.redirectErrorStream(true)
        return try {
            val p = pb.start()
            p.inputStream.bufferedReader().use { it.readText() }
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly()
                "ffmpeg timeout"
            } else if (p.exitValue() != 0) {
                "ffmpeg exit ${p.exitValue()}"
            } else if (!output.isFile || output.length() == 0L) {
                "empty output"
            } else {
                null
            }
        } catch (e: Exception) {
            e.message ?: "ffmpeg failed"
        }
    }

    private fun runCommand(cmd: List<String>): String? = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(2, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            null
        } else if (p.exitValue() != 0) {
            null
        } else {
            text
        }
    } catch (_: Exception) {
        null
    }
}
