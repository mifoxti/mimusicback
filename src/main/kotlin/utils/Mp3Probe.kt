package com.example.utils

/** Грубая проверка MP3: ID3v2 в начале или MPEG sync frame в первых [scan] байтах. */
fun looksLikeMp3Prefix(bytes: ByteArray, scan: Int = 16384): Boolean {
    if (bytes.size < 3) return false
    if (bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()) return true
    val lim = minOf(bytes.size - 1, scan)
    var i = 0
    while (i < lim) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = bytes[i + 1].toInt() and 0xFF
        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return true
        i++
    }
    return false
}
