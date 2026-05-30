package com.example.auth

import com.example.utils.sha256Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthCryptoTest {

    @Test
    fun проверка_хеширования_пароля_стабильна() {
        val h1 = sha256Hex("MiMusic")
        val h2 = sha256Hex("MiMusic")
        assertEquals(h1, h2)
        assertEquals(64, h1.length)
    }

    @Test
    fun разные_пароли_дают_разный_хеш() {
        assertNotEquals(sha256Hex("a"), sha256Hex("b"))
    }

    @Test
    fun проверка_хеширования_токена_сессии() {
        val token = "sample-bearer-token"
        val hash = sha256Hex(token)
        assertTrue(hash.matches(Regex("^[0-9a-f]{64}$")))
    }
}
