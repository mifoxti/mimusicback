package com.example.utils

/**
 * PNG шаблона аватара из [classpath default_identity_avatar.png] (копия клиентского identity).
 */
object DefaultIdentityAvatar {
    private val lock = Any()
    private var cached: ByteArray? = null

    fun bytes(): ByteArray? {
        synchronized(lock) {
            if (cached != null) return cached
            val stream =
                Thread.currentThread().contextClassLoader.getResourceAsStream("default_identity_avatar.png")
                    ?: return null
            cached = stream.use { it.readAllBytes() }
            return cached
        }
    }
}
