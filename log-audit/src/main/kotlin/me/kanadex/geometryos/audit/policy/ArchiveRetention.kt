package me.kanadex.geometryos.audit.policy

import me.kanadex.geometryos.audit.model.ArchiveEntry
import me.kanadex.geometryos.audit.model.ArchiveOutcome
import me.kanadex.geometryos.audit.model.AuditPolicy

data class ArchiveRetentionPlan(
    val evictions: List<ArchiveEntry>,
    val totalBytes: Long,
    val retainedBytes: Long,
    val protectedCount: Int,
    /** 达到硬上限：停止自动淘汰，转"待用户确认"。 */
    val pendingUser: Boolean,
    val reason: String?,
) {
    val freedBytes: Long get() = totalBytes - retainedBytes
}

/**
 * 归档淘汰策略（docs/log-audit/02 §3）：有界 + 分层 + 显式。
 *
 * - 软上限以内：什么都不删；
 * - 超过软上限：自动淘汰"成功且最旧"的整条记录（取消记录排第二顺位）；
 * - 失败/崩溃（[ArchiveOutcome.PROTECTED]）永不入选 —— 这是它们的唯一价值；
 * - 淘汰到仍然高于硬上限：不再自动删，返回 `pendingUser = true` 交给 UI 问人。
 */
object ArchiveRetention {
    fun plan(entries: List<ArchiveEntry>, policy: AuditPolicy): ArchiveRetentionPlan {
        val normalized = policy.normalized()
        val totalBytes = entries.sumOf { it.bytes }
        val protectedCount = entries.count { it.protected }

        val evictable = entries.filter { !it.protected }.sortedWith(
            compareBy({ if (it.outcome == ArchiveOutcome.SUCCESS) 0 else 1 }, { it.finishedAt ?: 0L }),
        )

        val evictions = ArrayList<ArchiveEntry>()
        var retained = totalBytes
        if (totalBytes > normalized.archiveSoftCap) {
            for (entry in evictable) {
                if (retained <= normalized.archiveSoftCap) break
                evictions += entry
                retained -= entry.bytes
            }
        }

        val pendingUser = retained > normalized.archiveHardCap
        val reason = when {
            pendingUser -> "over_hard_cap"
            evictions.isNotEmpty() -> "over_soft_cap"
            else -> null
        }
        return ArchiveRetentionPlan(
            evictions = evictions,
            totalBytes = totalBytes,
            retainedBytes = retained,
            protectedCount = protectedCount,
            pendingUser = pendingUser,
            reason = reason,
        )
    }
}
