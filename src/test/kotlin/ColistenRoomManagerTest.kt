package com.example

import com.example.colisten.ColistenRoomManager
import com.example.colisten.ColistenClientMessage
import com.example.colisten.RoomState
import com.example.colisten.applyHostStateMessage
import com.example.colisten.buildRemoteGuestCommand
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

    @Test
    fun guestCommand_updatesOnlyQueueWhenAllowed() {
        val roomId = ColistenRoomManager.createRoom(
            ownerId = 20,
            isOpen = true,
            trackId = 1,
            trackKey = "srv:1",
            queueTrackKeys = listOf("srv:1", "srv:2"),
            positionSeconds = 7.0,
            playing = true,
            shuffleEnabled = false,
            repeatMode = "off",
            controlPauseHostOnly = false,
            controlSeekHostOnly = false,
            controlShuffleHostOnly = true,
            controlRepeatHostOnly = false,
            controlSkipHostOnly = false,
            controlPlaylistHostOnly = false,
        )

        val updated = applyHostStateMessage(
            roomId,
            ColistenClientMessage(
                type = "command",
                trackId = 2,
                trackKey = "srv:2",
                queueTrackKeys = listOf("srv:2"),
                position = 15.0,
                playing = false,
                shuffleEnabled = true,
                repeatMode = "all",
            ),
            senderUserId = 21,
        )

        assertNotNull(updated)
        assertEquals(1, updated.trackId)
        assertEquals("srv:1", updated.trackKey)
        assertEquals(listOf("srv:2"), updated.queueTrackKeys)
        assertEquals(7.0, updated.positionSeconds)
        assertEquals(true, updated.playing)
        assertEquals(false, updated.shuffleEnabled)
        assertEquals("off", updated.repeatMode)
        ColistenRoomManager.leaveRoom(roomId, 20)
    }

    @Test
    fun guestRemoteCommand_containsOnlyQueueWhenAllowed() {
        val roomId = ColistenRoomManager.createRoom(
            ownerId = 30,
            isOpen = true,
            trackId = 1,
            trackKey = "srv:1",
            queueTrackKeys = listOf("srv:1", "srv:2"),
            positionSeconds = 12.0,
            playing = true,
            shuffleEnabled = false,
            repeatMode = "off",
            controlPauseHostOnly = false,
            controlSeekHostOnly = false,
            controlShuffleHostOnly = false,
            controlRepeatHostOnly = false,
            controlSkipHostOnly = false,
            controlPlaylistHostOnly = false,
        )
        ColistenRoomManager.joinRoom(roomId, userId = 31) {}

        val command = buildRemoteGuestCommand(
            roomId,
            ColistenClientMessage(
                type = "command",
                trackId = 2,
                trackKey = "srv:2",
                queueTrackKeys = listOf("srv:2"),
                position = 33.0,
                playing = false,
                shuffleEnabled = true,
                repeatMode = "all",
            ),
            senderUserId = 31,
        )

        assertNotNull(command)
        assertNull(command.trackId)
        assertNull(command.trackKey)
        assertEquals(listOf("srv:2"), command.queueTrackKeys)
        assertNull(command.position)
        assertNull(command.playing)
        assertNull(command.shuffleEnabled)
        assertNull(command.repeatMode)

        ColistenRoomManager.leaveRoom(roomId, 31)
        ColistenRoomManager.leaveRoom(roomId, 30)
    }

    @Test
    fun ownerHostState_withOlderBaseVersionCanFollowOwnerState() {
        val roomId = ColistenRoomManager.createRoom(
            ownerId = 50,
            isOpen = true,
            trackId = 1,
            trackKey = "srv:1",
            queueTrackKeys = listOf("srv:1"),
            positionSeconds = 10.0,
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
        val baseVersion = ColistenRoomManager.getState(roomId)!!.stateVersion

        val firstOwnerUpdate = applyHostStateMessage(
            roomId,
            ColistenClientMessage(
                type = "host_state",
                position = 12.0,
                playing = true,
                baseStateVersion = baseVersion,
            ),
            senderUserId = 50,
        )
        assertNotNull(firstOwnerUpdate)

        val secondOwnerUpdate = applyHostStateMessage(
            roomId,
            ColistenClientMessage(
                type = "host_state",
                position = 13.0,
                playing = false,
                baseStateVersion = baseVersion,
            ),
            senderUserId = 50,
        )
        assertNotNull(secondOwnerUpdate)
        assertEquals(false, secondOwnerUpdate.playing)
        assertEquals(13.0, secondOwnerUpdate.positionSeconds)

        ColistenRoomManager.leaveRoom(roomId, 50)
    }

}
