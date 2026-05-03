package com.example.utils

import com.example.config.getenv
import com.example.database.InviteKeys
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

fun requireInviteKeyFromEnv(): Boolean =
    getenv("REQUIRE_INVITE_KEY")?.equals("true", ignoreCase = true) == true

/** Статические коды беты из env `BETA_INVITE_CODES` (через запятую); если env пуст — как на клиенте по умолчанию. */
fun betaInviteStaticCodes(): Set<String> {
    val raw = getenv("BETA_INVITE_CODES")
    if (raw.isNullOrBlank()) {
        return setOf("MIMUSIC-BETA-CLOSED-2026", "MIMUSIC-BETA-DEV")
    }
    return raw.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
}

fun normalizeInviteCode(raw: String?): String =
    raw?.trim()?.uppercase()?.replace(Regex("\\s+"), "") ?: ""

private suspend fun inviteExistsInDbActive(normalized: String): Boolean =
    newSuspendedTransaction {
        InviteKeys.selectAll().where {
            (InviteKeys.keyCode eq normalized) and InviteKeys.revokedAt.isNull()
        }.any()
    }

/**
 * Регистрация: если `REQUIRE_INVITE_KEY` — ключ обязателен и должен быть в списке или в `invite_keys` без отзыва.
 * Если флаг выключен — пустой ключ допустим; непустой ключ при этом всё равно проверяется.
 */
suspend fun isInviteCodeAccepted(code: String?): Boolean {
    val norm = normalizeInviteCode(code)
    if (!requireInviteKeyFromEnv()) {
        if (norm.isEmpty()) return true
        return norm in betaInviteStaticCodes() || inviteExistsInDbActive(norm)
    }
    if (norm.isEmpty()) return false
    return norm in betaInviteStaticCodes() || inviteExistsInDbActive(norm)
}
