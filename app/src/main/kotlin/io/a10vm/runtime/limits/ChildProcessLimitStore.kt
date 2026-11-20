// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.runtime.limits

import android.content.Context
import io.a10vm.records.LimitStateRow
import io.a10vm.records.RecordStore

/**
 * 检测结论的读写入口 —— **复用 app 既有的 SQLite 数据层，不自建第二个库**。
 *
 * 存储落在 [RecordStore] 的 registry/records.db 里（同一个 SQLiteOpenHelper、同一把
 * ReentrantLock、同一条 onUpgrade 迁移路径），表是单行 KV child_limit_state。
 * 这里只做 [StoredLimit] ↔ [LimitStateRow] 的编解码，SQL 一行都不写。
 *
 * 为什么不再单独开一个 child_limit_state.db：那是「照抄数据层」而不是「复用数据层」，
 * 会多出一个 helper、一条没人知道的迁移路径、一份不会被记录中心带走的库文件。
 */
object ChildProcessLimitStore {

    /** 读上次结论；没有记录或读失败都返回 null（页面据此显示「尚未检测」）。 */
    fun load(context: Context): StoredLimit? {
        val row = runCatching { RecordStore.get(context).loadLimitState() }.getOrNull() ?: return null
        return StoredLimit(
            limit = decode(row.kind, row.detail),
            origin = runCatching { LimitOrigin.valueOf(row.origin) }.getOrDefault(LimitOrigin.RESTORED),
            at = row.at,
            muted = row.muted,
        )
    }

    fun save(context: Context, state: StoredLimit) {
        // 同步镜像一份到 SharedPreferences：SQLite 只能在后台读，而首屏必须**立刻**
        // 显示上次结论（否则每次进主页都先闪一下"未检测"）。prefs 只有一条字符串，
        // 读它是内存操作；权威数据仍然是 SQLite。
        cache(context, state)
        runCatching {
            RecordStore.get(context).saveLimitState(
                LimitStateRow(
                    kind = encode(state.limit),
                    detail = state.limit.detailText(),
                    origin = state.origin.name,
                    at = state.at,
                    muted = state.muted,
                ),
            )
        }
    }

    /** 同步读首屏用的镜像（没有就返回 null，页面照旧显示"尚未检测"）。 */
    fun peek(context: Context): StoredLimit? {
        val raw = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
        }.getOrNull() ?: return null
        val parts = raw.split(SEP)
        if (parts.size < 5) return null
        return runCatching {
            StoredLimit(
                limit = decode(parts[0], parts[4]),
                origin = LimitOrigin.valueOf(parts[1]),
                at = parts[2].toLongOrNull() ?: 0L,
                muted = parts[3] == "1",
            )
        }.getOrNull()
    }

    private fun cache(context: Context, state: StoredLimit) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(
                    KEY,
                    listOf(
                        encode(state.limit),
                        state.origin.name,
                        state.at.toString(),
                        if (state.muted) "1" else "0",
                        state.limit.detailText(),
                    ).joinToString(SEP),
                )
                .apply()
        }
    }

    private const val PREFS = "child_limit_state_cache"
    private const val KEY = "value"
    private const val SEP = "\u0001"

    private fun ChildProcessLimit.detailText(): String = when (this) {
        ChildProcessLimit.NotNeeded -> ""
        is ChildProcessLimit.Relaxed -> detail
        is ChildProcessLimit.Limited -> detail
        is ChildProcessLimit.Unknown -> detail
    }

    private fun encode(limit: ChildProcessLimit): String = when (limit) {
        ChildProcessLimit.NotNeeded -> "NOT_NEEDED"
        is ChildProcessLimit.Relaxed -> "RELAXED"
        is ChildProcessLimit.Limited -> "LIMITED"
        is ChildProcessLimit.Unknown -> "UNKNOWN"
    }

    private fun decode(kind: String, detail: String): ChildProcessLimit = when (kind) {
        "NOT_NEEDED" -> ChildProcessLimit.NotNeeded
        "RELAXED" -> ChildProcessLimit.Relaxed(detail)
        "LIMITED" -> ChildProcessLimit.Limited(detail)
        else -> ChildProcessLimit.Unknown(detail)
    }
}
