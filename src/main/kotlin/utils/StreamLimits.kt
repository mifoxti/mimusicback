package com.example.utils

import java.io.InputStream

/**
 * Читает не больше [maxBytes]. Если после [maxBytes] в потоке ещё есть данные — бросает
 * (защита от обхода лимита и zip/png «бомб»).
 */
fun InputStream.readAtMostBytesStrict(maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
    val buf = ByteArray(minOf(8192, maxBytes))
    var total = 0
    while (total < maxBytes) {
        val want = minOf(buf.size, maxBytes - total)
        val n = read(buf, 0, want)
        if (n <= 0) break
        out.write(buf, 0, n)
        total += n
    }
    val data = out.toByteArray()
    if (data.size == maxBytes) {
        val extra = ByteArray(1)
        val more = read(extra)
        if (more > 0) {
            throw IllegalArgumentException("Файл больше допустимого размера ($maxBytes байт)")
        }
    }
    return data
}
