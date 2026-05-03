package com.example

import com.example.colisten.ColistenRoomManager
import com.example.colisten.RoomState
import com.example.colisten.stateToJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ColistenRoomManagerTest {

    @Test
    fun createRoom_join_broadcast_leave() = runTest {
        val roomId = ColistenRoomManager.createRoom(ownerId = 1)
        assertNotNull(ColistenRoomManager.getState(roomId))
        assertEquals(1, ColistenRoomManager.getState(roomId)?.ownerId)

        val received = Channel<String>(Channel.UNLIMITED)
        val joined = ColistenRoomManager.joinRoom(roomId, userId = 2) { msg ->
            received.trySend(msg)
        }
        assertEquals(true, joined)
        assertEquals(listOf(1, 2), ColistenRoomManager.getState(roomId)?.participantIds)

        ColistenRoomManager.setState(roomId, RoomState(
            roomId = roomId,
            ownerId = 1,
            trackId = 5,
            positionSeconds = 10.0,
            playing = true,
            participantIds = listOf(1, 2)
        ))
        ColistenRoomManager.broadcast(roomId, stateToJson(ColistenRoomManager.getState(roomId)!!))
        val msg = received.receive()
        assertEquals(true, msg.contains("trackId"))
        assertEquals(true, msg.contains("5"))

        ColistenRoomManager.leaveRoom(roomId, 2)
        assertEquals(listOf(1), ColistenRoomManager.getState(roomId)?.participantIds)
        ColistenRoomManager.leaveRoom(roomId, 1)
        assertNull(ColistenRoomManager.getState(roomId))
    }
}
