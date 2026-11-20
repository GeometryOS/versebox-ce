// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.audit.android

import android.content.Context
import me.kanadex.geometryos.audit.model.AuditPolicy
import io.a10vm.ui.settings.AppPreferences

/**
 * 阈值读写的唯一入口（docs/log-audit/04 §4）。
 *
 * 键名只在这里出现一次；页面、Service、Collector 都不许自己拼 key，
 * 也不许在别处出现 `2 * 1024 * 1024` 这种字面量。
 */
class AuditPolicyStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)

    fun read(): AuditPolicy {
        val defaults = AuditPolicy.Default
        return AuditPolicy(
            keepSessions = prefs.getInt(KEY_KEEP_SESSIONS, defaults.keepSessions),
            sessionBytesCap = prefs.getLong(KEY_SESSION_BYTES, defaults.sessionBytesCap),
            archiveSoftCap = prefs.getLong(KEY_ARCHIVE_SOFT, defaults.archiveSoftCap),
            archiveHardCap = prefs.getLong(KEY_ARCHIVE_HARD, defaults.archiveHardCap),
            recordBytesBudget = prefs.getLong(KEY_RECORD_BUDGET, defaults.recordBytesBudget),
            fileTailBytes = prefs.getLong(KEY_FILE_TAIL, defaults.fileTailBytes),
            auditLogBytesCap = prefs.getLong(KEY_AUDIT_LOG, defaults.auditLogBytesCap),
            windowLogTailBytes = prefs.getLong(KEY_WINDOW_TAIL, defaults.windowLogTailBytes),
            trashGraceMillis = prefs.getLong(KEY_TRASH_GRACE, defaults.trashGraceMillis),
        ).normalized()
    }

    fun write(policy: AuditPolicy) {
        val normalized = policy.normalized()
        prefs.edit()
            .putInt(KEY_KEEP_SESSIONS, normalized.keepSessions)
            .putLong(KEY_SESSION_BYTES, normalized.sessionBytesCap)
            .putLong(KEY_ARCHIVE_SOFT, normalized.archiveSoftCap)
            .putLong(KEY_ARCHIVE_HARD, normalized.archiveHardCap)
            .putLong(KEY_RECORD_BUDGET, normalized.recordBytesBudget)
            .putLong(KEY_FILE_TAIL, normalized.fileTailBytes)
            .putLong(KEY_AUDIT_LOG, normalized.auditLogBytesCap)
            .putLong(KEY_WINDOW_TAIL, normalized.windowLogTailBytes)
            .putLong(KEY_TRASH_GRACE, normalized.trashGraceMillis)
            .apply()
    }

    private companion object {
        const val KEY_KEEP_SESSIONS = "audit.keepSessions"
        const val KEY_SESSION_BYTES = "audit.sessionBytesCap"
        const val KEY_ARCHIVE_SOFT = "audit.archiveSoftCap"
        const val KEY_ARCHIVE_HARD = "audit.archiveHardCap"
        const val KEY_RECORD_BUDGET = "audit.recordBytesBudget"
        const val KEY_FILE_TAIL = "audit.fileTailBytes"
        const val KEY_AUDIT_LOG = "audit.auditLogBytesCap"
        const val KEY_WINDOW_TAIL = "audit.windowLogTailBytes"
        const val KEY_TRASH_GRACE = "audit.trashGraceMillis"
    }
}
