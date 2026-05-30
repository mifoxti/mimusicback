package com.example.utils

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamLimitsTest {

    @Test
    fun readAtMostBytesStrict_читает_данные_в_пределах_лимита() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val read = ByteArrayInputStream(data).readAtMostBytesStrict(5)
        assertContentEquals(data, read)
    }

    @Test
    fun readAtMostBytesStrict_бросает_если_файл_больше_лимита() {
        val data = ByteArray(10) { it.toByte() }
        assertFailsWith<IllegalArgumentException> {
            ByteArrayInputStream(data).readAtMostBytesStrict(5)
        }
    }

    @Test
    fun readAtMostBytesStrict_короткий_поток_не_ошибка() {
        val read = ByteArrayInputStream(byteArrayOf(9)).readAtMostBytesStrict(8)
        assertEquals(1, read.size)
        assertEquals(9, read[0])
    }
}
