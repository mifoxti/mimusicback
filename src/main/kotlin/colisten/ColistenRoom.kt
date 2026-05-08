package com.example.colisten

import kotlinx.serialization.Serializable
import kotlinx.coroutines.withTimeoutOrNull
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
    val shuffleEnabled: Boolean = false,
    val repeatMode: String = "off",
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
        shuffleEnabled: Boolean,
        repeatMode: String,
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
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode.trim().lowercase().ifBlank { "off" },
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
            list.removeAll { it.userId == userId }
            list.add(RoomParticipant(userId, send))
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
            if (list.isEmpty() && userId == st?.ownerId) {
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
        val failedUserIds = mutableSetOf<Int>()
        for (p in list) {
            val sent = try {
                withTimeoutOrNull(350) {
                    p.send(message)
                    true
                } == true
            } catch (_: Exception) {
                false
            }
            if (!sent) {
                println("[colisten] broadcast failed room=$roomId user=${p.userId}; pruning participant")
                failedUserIds.add(p.userId)
            }
        }
        if (failedUserIds.isNotEmpty()) {
            lock.withLock {
                rooms[roomId]?.removeAll { it.userId in failedUserIds }
            }
        }
    }

    fun getParticipants(roomId: String): List<RoomParticipant> =
        rooms[roomId]?.toList() ?: emptyList()
}

fun applyHostStateMessage(roomId: String, msg: ColistenClientMessage, senderUserId: Int? = null): RoomState? =
    ColistenRoomManager.updateState(roomId) { cur ->
        val senderIsOwner = senderUserId == null || senderUserId == cur.ownerId
        val canPause = senderIsOwner || !cur.controlPauseHostOnly
        val canSeek = senderIsOwner || !cur.controlSeekHostOnly
        val canShuffle = senderIsOwner || !cur.controlShuffleHostOnly
        val canRepeat = senderIsOwner || !cur.controlRepeatHostOnly
        val canSkip = senderIsOwner || !cur.controlSkipHostOnly
        val canEditPlaylist = senderIsOwner || !cur.controlPlaylistHostOnly
        val requestedQueueIds = msg.queueTrackIds
            ?.filter { id -> id > 0 }
            ?.distinct()
        val requestedQueueKeys = msg.queueTrackKeys
            ?.map { key -> key.trim() }
            ?.filter { key -> key.isNotEmpty() }
            ?.distinct()
        val normalized = if (canEditPlaylist) requestedQueueIds ?: cur.queueTrackIds else cur.queueTrackIds
        val normalizedKeys = if (canEditPlaylist) requestedQueueKeys ?: cur.queueTrackKeys else cur.queueTrackKeys
        val nextTrackId = if (senderIsOwner) {
            msg.trackId ?: normalized.firstOrNull() ?: cur.trackId
        } else if (canSkip) {
            msg.trackId ?: cur.trackId
        } else {
            cur.trackId
        }
        val nextTrackKey = if (senderIsOwner) {
            msg.trackKey ?: normalizedKeys.firstOrNull() ?: cur.trackKey
        } else if (canSkip) {
            msg.trackKey ?: cur.trackKey
        } else {
            cur.trackKey
        }
        val nextRepeatMode = msg.repeatMode
            ?.trim()
            ?.lowercase()
            ?.takeIf { mode -> mode == "off" || mode == "all" || mode == "one" }
            ?.takeIf { canRepeat }
            ?: cur.repeatMode
        val trackChanged = nextTrackId != cur.trackId || nextTrackKey != cur.trackKey
        cur.copy(
            trackId = nextTrackId,
            trackKey = nextTrackKey,
            queueTrackIds = normalized,
            queueTrackKeys = normalizedKeys,
            positionSeconds = if (canSeek || canPause || trackChanged) msg.position ?: cur.positionSeconds else cur.positionSeconds,
            playing = if (canPause || trackChanged) msg.playing ?: cur.playing else cur.playing,
            shuffleEnabled = if (canShuffle) msg.shuffleEnabled ?: cur.shuffleEnabled else cur.shuffleEnabled,
            repeatMode = nextRepeatMode,
        )
    }
