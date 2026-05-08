package com.example

import com.example.colisten.ColistenRoomManager
import com.example.colisten.ColistenClientMessage
import com.example.colisten.RoomState
import com.example.colisten.applyHostStateMessage
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
        val roomId = ColistenRoomManager.createRoom(
            ownerId = 1,
            isOpen = true,
            trackId = null,
            trackKey = null,
            queueTrackKeys = emptyList(),
            positionSeconds = 0.0,
            playing = false,
            shuffleEnabled = false,
            repeatMode = "off",
            controlPauseHostOnly = true,
            controlSeekHostOnly = true,
            controlShuffleHostOnly = true,
            controlRepeatHostOnly = true,
            controlSkipHostOnly = true,
            controlPlaylistHostOnly = true,
        )
        assertNotNull(ColistenRoomManager.getState(roomId))
        assertEquals(1, ColistenRoomManager.getState(roomId)?.ownerId)

        val received = Channel<String>(Channel.UNLIMITED)
        val joined = ColistenRoomManager.joinRoom(roomId, userId = 2) { msg ->
            received.trySend(msg)
        }
        assertNotNull(joined)
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

    @Test
    fun hostState_updatesRoomState() {
        val roomId = ColistenRoomManager.createRoom(
            ownerId = 10,
            isOpen = true,
            trackId = 1,
            trackKey = "srv:1",
            queueTrackKeys = listOf("srv:1"),
            positionSeconds = 5.0,
            playing = true,
            shuffleEnabled = false,
            repeatMode = "off",
            controlPauseHostOnly = true,
            controlSeekHostOnly = true,
            controlShuffleHostOnly = true,
            controlRepeatHostOnly = true,
            controlSkipHostOnly = true,
            controlPlaylistHostOnly = true,
        )

        val updated = applyHostStateMessage(
            roomId,
            ColistenClientMessage(
                type = "host_state",
                trackId = 2,
                trackKey = "srv:2",
                queueTrackKeys = listOf("srv:1", "srv:2"),
                position = 42.0,
                playing = false,
                shuffleEnabled = true,
                repeatMode = "all",
            ),
        )

        assertNotNull(updated)
        assertEquals(2, updated.trackId)
        assertEquals("srv:2", updated.trackKey)
        assertEquals(listOf("srv:1", "srv:2"), updated.queueTrackKeys)
        assertEquals(42.0, updated.positionSeconds)
        assertEquals(false, updated.playing)
        assertEquals(true, updated.shuffleEnabled)
        assertEquals("all", updated.repeatMode)
        ColistenRoomManager.leaveRoom(roomId, 10)
    }
}
