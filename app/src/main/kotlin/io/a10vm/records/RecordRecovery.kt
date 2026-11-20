// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.records

import android.content.ContentValues
import android.content.Context
import android.util.Log
import me.kanadex.geometryos.audit.model.SessionId
import io.a10vm.runtime.VmRegistry

/**
 * 孤儿记录收尾。
 *
 * 一条记录只有在 `RecordCollector.finish` 被调用后才会离开 RUNNING 状态。
 * 如果应用/服务在操作过程中被系统杀掉，那次 `finish` 永远不会发生，
 * 记录就会一直停在「进行中」并且没有归档 —— 之后导出还会是空包。
 *
 * 判断依据是**运行时上报的 recordId**（`VmRuntime.recordId`）：
 * 只要还有活着的运行时报着这个 id，就说明这次操作还在进行，绝对不能碰；
 * 只有没人认领的记录才会被标成 CANCELLED 并补做归档。
 */
object RecordRecovery {
    private const val TAG = "A10VM_RECORDS"

    /** 太新的记录不碰：给它留出正常收尾的时间，避免和正在写的记录抢。 */
    private const val MIN_AGE_MS = 30_000L

    /**
     * @param liveRecordIds 当前仍有运行时在跟踪的记录 id 集合（来自服务上报）。
     * @return 实际收尾的记录条数。
     */
    suspend fun reconcile(context: Context, liveRecordIds: Set<Long>): Int {
        val store = RecordStore.get(context)
        val registry = VmRegistry.get(context)
        val running = runCatching {
            store.query(RecordFilter(status = RecordStatus.RUNNING))
        }.getOrElse {
            Log.w(TAG, "RECOVER 查询失败：${it.message}")
            return 0
        }
        if (running.isEmpty()) return 0

        val now = System.currentTimeMillis()
        var recovered = 0
        for (record in running) {
            if (record.id in liveRecordIds) continue
            if (now - record.startedAt < MIN_AGE_MS) continue
            val device = runCatching {
                registry.list().firstOrNull { it.id == record.vmId }
            }.getOrNull()
            val detail = "应用在「" + record.kind.label + "」结束前退出，这次操作被中断，已自动收尾"
            if (device != null) {
                runCatching {
                    RecordCollector.finish(
                        context,
                        record.id,
                        device,
                        record.kind,
                        RecordStatus.CANCELLED,
                        detail,
                        // 被中断的记录找不到它属于哪次会话（索引里没有 session 列，见 05 债务 D-1），
                        // 清单里如实写 legacy，而不是编一个 id。
                        SessionId.Legacy,
                    )
                    recovered++
                }.onFailure { Log.w(TAG, "RECOVER 归档失败 id=${record.id}：${it.message}") }
            } else {
                // 虚拟机已经不在了：只收尾索引，没有目录可归档。
                runCatching {
                    store.update(
                        record.id,
                        ContentValues().apply {
                            put("status", RecordStatus.CANCELLED.name)
                            put("failure", null as String?)
                            put("summary", detail)
                            put("finished_at", now)
                            put("duration_ms", (now - record.startedAt).coerceAtLeast(0))
                        },
                    )
                    recovered++
                }.onFailure { Log.w(TAG, "RECOVER 收尾失败 id=${record.id}：${it.message}") }
            }
        }
        if (recovered > 0) Log.i(TAG, "RECOVER ids=${running.size} 收尾=$recovered")
        return recovered
    }
}
