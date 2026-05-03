package com.example.colisten

import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

@Serializable
data class RoomState(
    val roomId: String,
    val ownerId: Int,
    val trackId: Int? = null,
    val positionSeconds: Double = 0.0,
    val playing: Boolean = false,
    val participantIds: List<Int> = emptyList()
)

/** Участник комнаты: userId и функция отправки сообщения в его WebSocket. */
data class RoomParticipant(val userId: Int, val send: suspend (String) -> Unit)

/**
 * In-memory хранилище комнат совместного прослушивания.
 * При рестарте сервера комнаты пропадают (для MVP достаточно).
 */
object ColistenRoomManager {
    private val mutex = Mutex()
    private val rooms = mutableMapOf<String, MutableList<RoomParticipant>>()
    private val roomState = mutableMapOf<String, RoomState>()

    fun createRoom(ownerId: Int): String {
        val id = UUID.randomUUID().toString()
        mutex.withLock {
            roomState[id] = RoomState(roomId = id, ownerId = ownerId)
            rooms[id] = mutableListOf()
        }
        return id
    }

    fun getState(roomId: String): RoomState? = roomState[roomId]

    fun setState(roomId: String, state: RoomState) {
        mutex.withLock {
            roomState[roomId] = state
        }
    }

    fun joinRoom(roomId: String, userId: Int, send: suspend (String) -> Unit): Boolean {
        mutex.withLock {
            val state = roomState[roomId] ?: return false
            val list = rooms.getOrPut(roomId) { mutableListOf() }
            list.add(RoomParticipant(userId, send))
            roomState[roomId] = state.copy(
                participantIds = list.map { it.userId }
            )
            return true
        }
    }

    fun leaveRoom(roomId: String, userId: Int) {
        mutex.withLock {
            rooms[roomId]?.removeAll { it.userId == userId }
            val list = rooms[roomId] ?: return
            roomState[roomId] = roomState[roomId]?.copy(
                participantIds = list.map { it.userId }
            ) ?: return
            if (list.isEmpty()) {
                rooms.remove(roomId)
                roomState.remove(roomId)
            }
        }
    }

    suspend fun broadcast(roomId: String, message: String) {
        val list = mutex.withLock { rooms[roomId]?.toList() ?: emptyList() }
        for (p in list) {
            try {
                p.send(message)
            } catch (_: Exception) { /* участник отключился */ }
        }
    }

    fun getParticipants(roomId: String): List<RoomParticipant> =
        rooms[roomId]?.toList() ?: emptyList()
}
