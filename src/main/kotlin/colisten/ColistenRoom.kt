package com.example.colisten

import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class RoomState(
    val roomId: String,
    val ownerId: Int,
    val isOpen: Boolean = false,
    val trackId: Int? = null,
    val trackKey: String? = null,
    val queueTrackIds: List<Int> = emptyList(),
    val queueTrackKeys: List<String> = emptyList(),
    val positionSeconds: Double = 0.0,
    val playing: Boolean = false,
    val controlPauseHostOnly: Boolean = true,
    val controlSeekHostOnly: Boolean = true,
    val controlShuffleHostOnly: Boolean = true,
    val controlRepeatHostOnly: Boolean = true,
    val controlSkipHostOnly: Boolean = true,
    val controlPlaylistHostOnly: Boolean = true,
    val participantIds: List<Int> = emptyList(),
    val stateVersion: Long = 0L,
    val wallClockMs: Long = 0L,
)

/** Участник комнаты: userId и функция отправки сообщения в его WebSocket. */
data class RoomParticipant(val userId: Int, val send: suspend (String) -> Unit)

/**
 * In-memory хранилище комнат совместного прослушивания.
 */
object ColistenRoomManager {
    private val lock = ReentrantLock()
    private val rooms = mutableMapOf<String, MutableList<RoomParticipant>>()
    private val roomState = mutableMapOf<String, RoomState>()
    private val userActiveRoom = ConcurrentHashMap<Int, String>()

    fun getActiveRoomIdForUser(userId: Int): String? = userActiveRoom[userId]

    fun createRoom(
        ownerId: Int,
        isOpen: Boolean,
        trackId: Int?,
        trackKey: String?,
        queueTrackKeys: List<String>,
        positionSeconds: Double,
        playing: Boolean,
        controlPauseHostOnly: Boolean,
        controlSeekHostOnly: Boolean,
        controlShuffleHostOnly: Boolean,
        controlRepeatHostOnly: Boolean,
        controlSkipHostOnly: Boolean,
        controlPlaylistHostOnly: Boolean,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        lock.withLock {
            roomState[id] = RoomState(
                roomId = id,
                ownerId = ownerId,
                isOpen = isOpen,
                trackId = trackId,
                trackKey = trackKey,
                queueTrackIds = trackId?.let { listOf(it) } ?: emptyList(),
                queueTrackKeys = if (queueTrackKeys.isNotEmpty()) queueTrackKeys else trackKey?.let { listOf(it) } ?: emptyList(),
                positionSeconds = positionSeconds,
                playing = playing,
                controlPauseHostOnly = controlPauseHostOnly,
                controlSeekHostOnly = controlSeekHostOnly,
                controlShuffleHostOnly = controlShuffleHostOnly,
                controlRepeatHostOnly = controlRepeatHostOnly,
                controlSkipHostOnly = controlSkipHostOnly,
                controlPlaylistHostOnly = controlPlaylistHostOnly,
                participantIds = listOf(ownerId),
                stateVersion = 1L,
                wallClockMs = now,
            )
            rooms[id] = mutableListOf()
        }
        userActiveRoom[ownerId] = id
        return id
    }

    fun getState(roomId: String): RoomState? = roomState[roomId]

    fun listOpenRoomStates(): List<RoomState> = lock.withLock {
        roomState.values.filter { it.isOpen }.sortedByDescending { it.wallClockMs }
    }

    /** Атомарно обновить состояние (инкремент версии и метки времени). */
    fun updateState(roomId: String, transform: (RoomState) -> RoomState): RoomState? {
        lock.withLock {
            val cur = roomState[roomId] ?: return null
            val now = System.currentTimeMillis()
            val next = transform(cur).copy(
                stateVersion = cur.stateVersion + 1,
                wallClockMs = now,
            )
            roomState[roomId] = next
            return next
        }
    }

    fun setState(roomId: String, state: RoomState) {
        lock.withLock {
            roomState[roomId] = state
        }
    }

    /**
     * Для служебных апдейтов (join/leave) нельзя "обнулять" тайминг комнаты.
     * Если комната играет, продвигаем позицию на прошедшее время и только потом
     * переносим wallClock к now.
     */
    private fun advancePlaybackAnchorIfNeeded(state: RoomState, now: Long): Pair<Double, Long> {
        if (!state.playing) return state.positionSeconds to state.wallClockMs
        val wallClock = state.wallClockMs
        if (wallClock <= 0L) return state.positionSeconds to now
        val elapsedMs = (now - wallClock).coerceIn(0L, 30000L)
        val nextPosition = state.positionSeconds + (elapsedMs / 1000.0)
        return nextPosition to now
    }

    fun joinRoom(roomId: String, userId: Int, send: suspend (String) -> Unit): RoomState? {
        lock.withLock {
            val state = roomState[roomId] ?: return null
            val list = rooms.getOrPut(roomId) { mutableListOf() }
            if (list.none { it.userId == userId }) {
                list.add(RoomParticipant(userId, send))
            }
            val ids = (listOf(state.ownerId) + list.map { it.userId }).distinct()
            val now = System.currentTimeMillis()
            val (positionSeconds, wallClockMs) = advancePlaybackAnchorIfNeeded(state, now)
            val next = state.copy(
                participantIds = ids,
                positionSeconds = positionSeconds,
                stateVersion = state.stateVersion + 1,
                wallClockMs = wallClockMs,
            )
            roomState[roomId] = next
            userActiveRoom[userId] = roomId
            return next
        }
    }

    fun leaveRoom(roomId: String, userId: Int): RoomState? {
        var out: RoomState? = null
        lock.withLock {
            rooms[roomId]?.removeAll { it.userId == userId }
            val list = rooms[roomId]
            if (list == null) {
                if (userActiveRoom[userId] == roomId) userActiveRoom.remove(userId)
                return null
            }
            val st = roomState[roomId]
            if (st != null) {
                val ids = (listOf(st.ownerId) + list.map { it.userId }).distinct()
                val now = System.currentTimeMillis()
                val (positionSeconds, wallClockMs) = advancePlaybackAnchorIfNeeded(st, now)
                val next = st.copy(
                    participantIds = ids,
                    positionSeconds = positionSeconds,
                    stateVersion = st.stateVersion + 1,
                    wallClockMs = wallClockMs,
                )
                roomState[roomId] = next
                out = next
            }
            if (list.isEmpty()) {
                rooms.remove(roomId)
                roomState.remove(roomId)
                userActiveRoom.filterValues { it == roomId }.keys.forEach { userActiveRoom.remove(it) }
                out = null
            }
        }
        if (userActiveRoom[userId] == roomId) {
            userActiveRoom.remove(userId)
        }
        return out
    }

    suspend fun broadcast(roomId: String, message: String) {
        val list = lock.withLock { rooms[roomId]?.toList() ?: emptyList() }
        for (p in list) {
            try {
                p.send(message)
            } catch (_: Exception) { /* disconnected */ }
        }
    }

    fun getParticipants(roomId: String): List<RoomParticipant> =
        rooms[roomId]?.toList() ?: emptyList()
}
