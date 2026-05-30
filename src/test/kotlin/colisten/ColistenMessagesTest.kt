package com.example.colisten

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ColistenMessagesTest {

    @Test
    fun parseClientMessage_разбирает_host_state() {
        val raw = """{"type":"host_state","position":12.5,"playing":true}"""
        val msg = parseClientMessage(raw)
        assertNotNull(msg)
        assertEquals("host_state", msg.type)
        assertEquals(12.5, msg.position)
        assertEquals(true, msg.playing)
    }

    @Test
    fun parseClientMessage_невалидный_json_возвращает_null() {
        assertNull(parseClientMessage("{not json"))
    }

    @Test
    fun stateToJson_содержит_поля_комнаты() {
        val json = stateToJson(
            RoomState(
                roomId = "room-1",
                ownerId = 7,
                trackId = 3,
                trackKey = "srv:3",
                positionSeconds = 5.0,
                playing = false,
                participantIds = listOf(7, 8),
                stateVersion = 2L,
            ),
        )
        assertEquals(true, json.contains("room-1"))
        assertEquals(true, json.contains("\"ownerId\":7"))
        assertEquals(true, json.contains("\"trackId\":3"))
        assertEquals(true, json.contains("\"playing\":false"))
    }
}
