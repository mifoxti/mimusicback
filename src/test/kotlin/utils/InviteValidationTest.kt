package com.example.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InviteValidationTest {

    @Test
    fun normalizeInviteCode_приводит_к_верхнему_регистру_и_убирает_пробелы() {
        assertEquals("ABCD1-23456-78901", normalizeInviteCode("  abcd1-23456-78901  "))
    }

    @Test
    fun normalizeInviteCode_пустой_ввод_дает_пустую_строку() {
        assertEquals("", normalizeInviteCode(null))
        assertEquals("", normalizeInviteCode("   "))
    }

    @Test
    fun betaInviteStaticCodes_содержит_коды_по_умолчанию() {
        val codes = betaInviteStaticCodes()
        assertTrue("MIMUSIC-BETA-CLOSED-2026" in codes)
        assertTrue("MIMUSIC-BETA-DEV" in codes)
    }
}
