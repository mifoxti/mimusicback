package com.example.utils

/** WAV: RIFF…WAVE */
fun looksLikeWavPrefix(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    return bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() &&
        bytes[9] == 'A'.code.toByte() &&
        bytes[10] == 'V'.code.toByte() &&
        bytes[11] == 'E'.code.toByte()
}

/** ISO BMFF (mp4/m4a): … ftyp … */
fun looksLikeIsoBmffPrefix(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    val ftyp = String(bytes, 4, 4, Charsets.US_ASCII)
    return ftyp == "ftyp"
}

/** Любой поддерживаемый вход: mp3 / wav / m4a(mp4). */
fun looksLikeSupportedAudioUpload(bytes: ByteArray): Boolean =
    looksLikeMp3Prefix(bytes) || looksLikeWavPrefix(bytes) || looksLikeIsoBmffPrefix(bytes)
