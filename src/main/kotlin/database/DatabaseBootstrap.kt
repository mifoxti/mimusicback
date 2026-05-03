package com.example.database

import com.example.utils.sha256Hex
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

private const val BOOTSTRAP_UPLOADER_NICKNAME = "__scanner_uploader__"

/** Отдельный «владелец» строки в invite_keys: у таблицы UNIQUE(creator_user_id), один пользователь — один ключ. */
private const val INVITE_KEY_HOLDER_NICKNAME = "__invite_key_holder__"

/** Ключ для ручных тестов регистрации (формат как у клиента: 5-5-5). Сидится при старте, если ещё нет в БД. */
const val BOOTSTRAP_TEST_INVITE_CODE = "TESTK-EYDEV-BUILD"

/** Служебный пользователь для строк `tracks.uploader_user_id`, пока треки добавляет только сканер папки. */
fun ensureBootstrapUploaderUserId(): Long = transaction {
    Users.selectAll().where { Users.nickname eq BOOTSTRAP_UPLOADER_NICKNAME }
        .singleOrNull()
        ?.get(Users.id)
        ?: Users.insert {
            it[nickname] = BOOTSTRAP_UPLOADER_NICKNAME
            it[email] = null
            it[passwordHash] = sha256Hex("bootstrap:" + UUID.randomUUID())
            it[avatarStorageKey] = null
            it[bio] = null
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
            it[isAdmin] = false
        } get Users.id
}

/** Тестовый invite в `invite_keys` (активный, не revoked). Не трогает ключи реальных пользователей. */
fun ensureBootstrapTestInviteKey() {
    transaction {
        if (InviteKeys.selectAll().where { InviteKeys.keyCode eq BOOTSTRAP_TEST_INVITE_CODE }.any()) {
            return@transaction
        }

        val holderId =
            Users.selectAll().where { Users.nickname eq INVITE_KEY_HOLDER_NICKNAME }
                .singleOrNull()
                ?.get(Users.id)
                ?: Users.insert {
                    it[nickname] = INVITE_KEY_HOLDER_NICKNAME
                    it[email] = null
                    it[passwordHash] = sha256Hex("bootstrap-invite-holder:" + UUID.randomUUID())
                    it[avatarStorageKey] = null
                    it[bio] = null
                    it[createdAt] = OffsetDateTime.now()
                    it[updatedAt] = OffsetDateTime.now()
                    it[isAdmin] = false
                } get Users.id

        if (InviteKeys.selectAll().where { InviteKeys.creatorUserId eq holderId }.any()) {
            return@transaction
        }

        InviteKeys.insert {
            it[keyCode] = BOOTSTRAP_TEST_INVITE_CODE
            it[creatorUserId] = holderId
            it[createdAt] = OffsetDateTime.now()
            it[revokedAt] = null
            it[notes] = "Bootstrap test invite (dev)"
        }
    }
}
