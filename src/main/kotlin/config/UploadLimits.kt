package com.example.config

/** Максимальный размер одного загружаемого файла (байты). Переопределение: env `UPLOAD_MAX_BYTES`. */
fun uploadMaxBytes(): Int =
    getenv("UPLOAD_MAX_BYTES")?.toIntOrNull()?.coerceIn(1024, 200 * 1024 * 1024)
        ?: (20 * 1024 * 1024) // 20 MiB по умолчанию

/** Максимальная сторона растра в пикселях после чтения заголовка (защита от «бомбы» размеров). */
const val UPLOAD_IMAGE_MAX_EDGE_PX: Int = 4096

/** Максимум пикселей (ширина × высота) перед полным декодированием. */
const val UPLOAD_IMAGE_MAX_PIXELS: Long = 4096L * 4096L
