package com.example.config

import java.io.File

/** Dev convenience: `.env` in the process working dir (Gradle `run` → project root). Real env wins. */
private val dotEnv: Map<String, String> by lazy { loadDotEnvFile() }

private fun loadDotEnvFile(): Map<String, String> {
    val f = File(System.getProperty("user.dir"), ".env")
    if (!f.isFile) return emptyMap()
    return try {
        f.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                var value = line.substring(idx + 1).trim()
                if (value.length >= 2) {
                    val q = value.first()
                    if ((q == '"' || q == '\'') && value.last() == q) {
                        value = value.substring(1, value.length - 1)
                    }
                }
                key to value
            }
            .toMap()
    } catch (_: Exception) {
        emptyMap()
    }
}

fun getenv(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: dotEnv[name]?.takeIf { it.isNotBlank() }

private fun isWindowsOs(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("win")

/**
 * Путь к `ffmpeg` / `ffprobe`: сначала `FFMPEG_PATH` / `FFPROBE_PATH`, иначе каталог `FFMPEG_BIN_DIR`,
 * иначе имя из PATH (`ffmpeg` / `ffprobe`).
 */
fun ffmpegExecutable(): String = resolveExternalTool(
    explicitPathEnv = "FFMPEG_PATH",
    binDirEnv = "FFMPEG_BIN_DIR",
    defaultName = "ffmpeg",
)

fun ffprobeExecutable(): String = resolveExternalTool(
    explicitPathEnv = "FFPROBE_PATH",
    binDirEnv = "FFMPEG_BIN_DIR",
    defaultName = "ffprobe",
)

/** Каталог с ffmpeg.exe/ffprobe.exe после `winget install Gyan.FFmpeg` (JVM часто не видит обновлённый PATH). */
private fun windowsWingetFfmpegBinDir(): String? {
    if (!isWindowsOs()) return null
    val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: return null
    val links = File(local, "Microsoft/WinGet/Links")
    if (!links.isDirectory) return null
    val ext = ".exe"
    val ffmpeg = File(links, "ffmpeg$ext")
    val ffprobe = File(links, "ffprobe$ext")
    return if (ffmpeg.isFile && ffprobe.isFile) links.absolutePath else null
}

private fun resolveExternalTool(
    explicitPathEnv: String,
    binDirEnv: String,
    defaultName: String,
): String {
    getenv(explicitPathEnv)?.let { return File(it).absolutePath }
    val ext = if (isWindowsOs()) ".exe" else ""
    val binDirs = listOfNotNull(
        getenv(binDirEnv),
        windowsWingetFfmpegBinDir(),
    )
    for (dir in binDirs) {
        val candidate = File(dir, defaultName + ext)
        if (candidate.isFile) return candidate.absolutePath
    }
    return defaultName
}

/** Корень аудио: env `MUSIC_STORAGE_DIR` или относительный `music_storage`. */
fun musicStorageDirectory(): File =
    File(getenv("MUSIC_STORAGE_DIR") ?: "music_storage").also {
        if (!it.exists()) it.mkdirs()
    }

/** Корень обложек/аватаров: env `FILE_STORAGE_ROOT` или `file_storage`; создаются подкаталоги из спецификации. */
fun fileStorageRoot(): File {
    val root = File(getenv("FILE_STORAGE_ROOT") ?: "file_storage")
    if (!root.exists()) root.mkdirs()
    listOf(
        "avatars",
        "covers/tracks",
        "covers/playlists",
        "covers/albums",
    ).forEach { rel ->
        File(root, rel).also { sub -> if (!sub.exists()) sub.mkdirs() }
    }
    return root
}
