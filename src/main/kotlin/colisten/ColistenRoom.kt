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
    val controlSeq: Long = 0L,
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
    private val guestPriorityUntilMs = ConcurrentHashMap<String, Long>()
    private val lastGuestCommandVersion = ConcurrentHashMap<String, Long>()
    private val lastGuestCommandState = ConcurrentHashMap<String, RoomState>()
    private val recentRemoteCommandAtMs = ConcurrentHashMap<String, Long>()
    private const val GUEST_PRIORITY_WINDOW_MS = 2200L
    private const val REMOTE_COMMAND_DEDUPE_WINDOW_MS = 900L

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
            val transformed = transform(cur)
            if (transformed == cur) {
                return cur
            }
            val now = System.currentTimeMillis()
            val next = transformed.copy(
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
                guestPriorityUntilMs.remove(roomId)
                lastGuestCommandVersion.remove(roomId)
                lastGuestCommandState.remove(roomId)
                recentRemoteCommandAtMs.keys.removeIf { key -> key.startsWith("$roomId|") }
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

    suspend fun sendToUser(roomId: String, userId: Int, message: String): Boolean {
        val participant = lock.withLock {
            rooms[roomId]?.firstOrNull { it.userId == userId }
        } ?: return false
        return try {
            withTimeoutOrNull(350) {
                participant.send(message)
                true
            } == true
        } catch (_: Exception) {
            false
        }
    }

    fun markGuestPriorityWindow(roomId: String) {
        guestPriorityUntilMs[roomId] = System.currentTimeMillis() + GUEST_PRIORITY_WINDOW_MS
    }

    fun isGuestPriorityWindowActive(roomId: String): Boolean =
        (guestPriorityUntilMs[roomId] ?: 0L) > System.currentTimeMillis()

    fun markGuestCommandVersion(roomId: String, stateVersion: Long) {
        if (stateVersion > 0L) {
            lastGuestCommandVersion[roomId] = stateVersion
        }
    }

    fun markGuestCommandState(roomId: String, state: RoomState) {
        lastGuestCommandState[roomId] = state
        markGuestCommandVersion(roomId, state.stateVersion)
    }

    fun clearGuestCommandState(roomId: String) {
        lastGuestCommandVersion.remove(roomId)
        lastGuestCommandState.remove(roomId)
    }

    fun isStaleAgainstGuestCommand(roomId: String, baseStateVersion: Long): Boolean {
        val guestVersion = lastGuestCommandVersion[roomId] ?: return false
        return baseStateVersion < guestVersion
    }

    fun conflictsWithLastGuestCommand(roomId: String, msg: ColistenClientMessage): Boolean {
        val guest = lastGuestCommandState[roomId] ?: return false
        val requestedTrackKey = msg.trackKey ?: guest.trackKey
        val requestedTrackId = msg.trackId ?: guest.trackId
        val requestedPlaying = msg.playing ?: guest.playing
        val requestedShuffle = msg.shuffleEnabled ?: guest.shuffleEnabled
        val requestedRepeat = msg.repeatMode
            ?.trim()
            ?.lowercase()
            ?.takeIf { mode -> mode == "off" || mode == "all" || mode == "one" }
            ?: guest.repeatMode

        return requestedTrackId != guest.trackId ||
            requestedTrackKey != guest.trackKey ||
            requestedPlaying != guest.playing ||
            requestedShuffle != guest.shuffleEnabled ||
            requestedRepeat != guest.repeatMode
    }

    fun shouldAcceptRemoteCommand(roomId: String, senderUserId: Int, command: ColistenClientMessage): Boolean {
        val positionBucket = command.position
            ?.let { pos -> (pos * 2).toLong() } // 500ms buckets absorb WS/REST serialization noise.
            ?: -1L
        val key = listOf(
            roomId,
            senderUserId,
            command.playing,
            positionBucket,
            command.trackId,
            command.trackKey,
            command.shuffleEnabled,
            command.repeatMode,
            command.queueTrackIds?.joinToString(","),
            command.queueTrackKeys?.joinToString(","),
        ).joinToString("|")
        val now = System.currentTimeMillis()
        val previous = recentRemoteCommandAtMs[key]
        if (previous != null && now - previous <= REMOTE_COMMAND_DEDUPE_WINDOW_MS) {
            println("[colisten] remote_command duplicate suppressed room=$roomId sender=$senderUserId")
            return false
        }
        recentRemoteCommandAtMs[key] = now
        recentRemoteCommandAtMs.entries.removeIf { now - it.value > 10_000L }
        return true
    }
}

fun applyHostStateMessage(roomId: String, msg: ColistenClientMessage, senderUserId: Int? = null): RoomState? =
    ColistenRoomManager.updateState(roomId) { cur ->
        val senderIsOwner = senderUserId == null || senderUserId == cur.ownerId
        val explicitAction = msg.explicitAction == true
        if (senderIsOwner &&
            msg.type != "command" &&
            !explicitAction &&
            ColistenRoomManager.isGuestPriorityWindowActive(roomId)
        ) {
            println(
                "[colisten] owner host_state suppressed room=$roomId sender=$senderUserId " +
                    "reason=guest_priority_window",
            )
            return@updateState cur
        }
        if (senderIsOwner &&
            msg.type != "command" &&
            !explicitAction &&
            msg.baseStateVersion != null &&
            msg.baseStateVersion > 0L &&
            ColistenRoomManager.isStaleAgainstGuestCommand(roomId, msg.baseStateVersion)
        ) {
            println(
                "[colisten] owner host_state suppressed room=$roomId sender=$senderUserId " +
                    "reason=stale_guest_base base=${msg.baseStateVersion} current=${cur.stateVersion}",
            )
            return@updateState cur
        }
        if (senderIsOwner &&
            msg.type != "command" &&
            !explicitAction &&
            ColistenRoomManager.conflictsWithLastGuestCommand(roomId, msg)
        ) {
            println(
                "[colisten] owner host_state suppressed room=$roomId sender=$senderUserId " +
                    "reason=guest_conflict",
            )
            return@updateState cur
        }
        val canPause = senderIsOwner
        val canSeek = senderIsOwner
        val canShuffle = senderIsOwner
        val canRepeat = senderIsOwner
        val canSkip = senderIsOwner
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
        val next = cur.copy(
            trackId = nextTrackId,
            trackKey = nextTrackKey,
            queueTrackIds = normalized,
            queueTrackKeys = normalizedKeys,
            positionSeconds = if (canSeek || canPause || trackChanged) msg.position ?: cur.positionSeconds else cur.positionSeconds,
            playing = if (canPause || trackChanged) msg.playing ?: cur.playing else cur.playing,
            shuffleEnabled = if (canShuffle) msg.shuffleEnabled ?: cur.shuffleEnabled else cur.shuffleEnabled,
            repeatMode = nextRepeatMode,
            // Инкрементируем controlSeq для каждой явной команды, чтобы гости
            // не отфильтровывали сообщение по staleVersion даже при неизменном state.
            controlSeq = if (explicitAction) cur.controlSeq + 1 else cur.controlSeq,
        )
        println(
            "[colisten] apply_state room=$roomId sender=$senderUserId owner=${cur.ownerId} type=${msg.type} " +
                "canPause=$canPause canSeek=$canSeek canShuffle=$canShuffle canRepeat=$canRepeat canSkip=$canSkip canQueue=$canEditPlaylist " +
                "req(trackId=${msg.trackId},key=${msg.trackKey},pos=${msg.position},playing=${msg.playing},shuffle=${msg.shuffleEnabled},repeat=${msg.repeatMode},queueKeys=${msg.queueTrackKeys?.size ?: -1}) " +
                "next(trackId=${next.trackId},key=${next.trackKey},pos=${next.positionSeconds},playing=${next.playing},shuffle=${next.shuffleEnabled},repeat=${next.repeatMode},queueKeys=${next.queueTrackKeys.size})",
        )
        if (senderIsOwner && msg.type != "command" && explicitAction) {
            ColistenRoomManager.clearGuestCommandState(roomId)
        }
        next
    }

fun applyGuestCommandMessage(roomId: String, msg: ColistenClientMessage, senderUserId: Int): RoomState? {
    val current = ColistenRoomManager.getState(roomId) ?: return null
    if (senderUserId == current.ownerId) return current
    val updated = applyHostStateMessage(roomId, msg, senderUserId)
    if (updated != null) {
        ColistenRoomManager.markGuestPriorityWindow(roomId)
        ColistenRoomManager.markGuestCommandState(roomId, updated)
    }
    return updated
}

fun buildRemoteGuestCommand(roomId: String, msg: ColistenClientMessage, senderUserId: Int): ColistenClientMessage? {
    val cur = ColistenRoomManager.getState(roomId) ?: return null
    if (senderUserId == cur.ownerId) return null
    if (senderUserId !in cur.participantIds) return null

    val canPause = false
    val canSeek = false
    val canShuffle = false
    val canRepeat = false
    val canSkip = false
    val canEditPlaylist = !cur.controlPlaylistHostOnly
    val requestedQueueIds = msg.queueTrackIds
        ?.filter { id -> id > 0 }
        ?.distinct()
    val requestedQueueKeys = msg.queueTrackKeys
        ?.map { key -> key.trim() }
        ?.filter { key -> key.isNotEmpty() }
        ?.distinct()
    val requestedTrackId = msg.trackId?.takeIf { id -> id > 0 }
    val requestedTrackKey = msg.trackKey
        ?.trim()
        ?.takeIf { key -> key.isNotEmpty() }
    val normalizedRepeat = msg.repeatMode
        ?.trim()
        ?.lowercase()
        ?.takeIf { mode -> mode == "off" || mode == "all" || mode == "one" }
    val hasTrackChange = canSkip &&
        ((requestedTrackId != null && requestedTrackId != cur.trackId) ||
            (requestedTrackKey != null && requestedTrackKey != cur.trackKey))
    val command = ColistenClientMessage(
        type = "remote_command",
        position = if (canSeek || canPause || hasTrackChange) msg.position else null,
        playing = if (canPause || hasTrackChange) msg.playing else null,
        trackId = if (hasTrackChange) requestedTrackId else null,
        trackKey = if (hasTrackChange) requestedTrackKey else null,
        queueTrackIds = if (canEditPlaylist) requestedQueueIds else null,
        queueTrackKeys = if (canEditPlaylist) requestedQueueKeys else null,
        shuffleEnabled = if (canShuffle) msg.shuffleEnabled else null,
        repeatMode = if (canRepeat) normalizedRepeat else null,
        senderUserId = senderUserId,
    )
    val hasAnyEffect = command.position != null ||
        command.playing != null ||
        command.trackId != null ||
        command.trackKey != null ||
        command.queueTrackIds != null ||
        command.queueTrackKeys != null ||
        command.shuffleEnabled != null ||
        command.repeatMode != null
    if (!hasAnyEffect) return null
    if (!ColistenRoomManager.shouldAcceptRemoteCommand(roomId, senderUserId, command)) {
        return null
    }
    println(
        "[colisten] remote_command built room=$roomId sender=$senderUserId owner=${cur.ownerId} " +
            "canPause=$canPause canSeek=$canSeek canShuffle=$canShuffle canRepeat=$canRepeat canSkip=$canSkip canQueue=$canEditPlaylist " +
            "cmd(trackId=${command.trackId},key=${command.trackKey},pos=${command.position},playing=${command.playing},shuffle=${command.shuffleEnabled},repeat=${command.repeatMode},queueKeys=${command.queueTrackKeys?.size ?: -1})",
    )
    return command
}
