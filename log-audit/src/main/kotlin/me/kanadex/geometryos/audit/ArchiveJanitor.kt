package me.kanadex.geometryos.audit

import me.kanadex.geometryos.audit.model.ArchiveEntry
import me.kanadex.geometryos.audit.model.ArchiveOutcome
import me.kanadex.geometryos.audit.model.AuditEvent
import me.kanadex.geometryos.audit.model.AuditEventKind
import me.kanadex.geometryos.audit.port.AuditClock
import me.kanadex.geometryos.audit.port.AuditEventSink
import me.kanadex.geometryos.audit.port.AuditFileSystem
import me.kanadex.geometryos.audit.policy.ArchiveRetentionPlan

data class ArchiveSweepReport(
    val deletedRecords: List<Long>,
    val freedBytes: Long,
    val retainedBytes: Long,
    val protectedCount: Int,
    val pendingUser: Boolean,
)

/**
 * 执行归档淘汰计划（docs/log-audit/02 §3.2）。
 *
 * - 软上限以内：不删；
 * - 超过软上限：自动淘汰"成功且最旧"的整条记录，写 `archive_evict` 事件；
 * - 触硬上限：**不静默删**，只写 `cap_reached` 并置 `pendingUser`，
 *   等用户确认（[userConfirmed]）后再删。
 *
 * 策略保证 [ArchiveOutcome.PROTECTED] 永远不入选，所以这里不需要再判断一次状态。
 */
class ArchiveJanitor(
    private val fs: AuditFileSystem,
    private val clock: AuditClock,
    private val sink: AuditEventSink,
) {
    fun sweep(entries: List<ArchiveEntry>, plan: ArchiveRetentionPlan, userConfirmed: Boolean): ArchiveSweepReport {
        if (plan.pendingUser && !userConfirmed) {
            sink.emit(
                AuditEvent(
                    at = clock.now(),
                    kind = AuditEventKind.CAP_REACHED,
                    fields = mapOf(
                        "total" to plan.totalBytes,
                        "protected" to plan.protectedCount.toLong(),
                        "pendingUser" to true,
                    ),
                ),
            )
            return ArchiveSweepReport(
                deletedRecords = emptyList(),
                freedBytes = 0L,
                retainedBytes = plan.totalBytes,
                protectedCount = plan.protectedCount,
                pendingUser = true,
            )
        }
        val deleted = ArrayList<Long>(plan.evictions.size)
        var freed = 0L
        var retained = plan.totalBytes
        for (entry in plan.evictions) {
            if (entry.protected) continue
            val bytes = if (entry.dir.isEmpty()) entry.bytes else sizeOf(entry)
            if (entry.dir.isNotEmpty()) {
                val ok = runCatching { fs.deleteRecursively(entry.dir) }.getOrDefault(false)
                if (!ok && fs.exists(entry.dir)) continue
            }
            deleted += entry.recordId
            freed += bytes
            retained -= entry.bytes
            sink.emit(
                AuditEvent(
                    at = clock.now(),
                    kind = AuditEventKind.ARCHIVE_EVICT,
                    fields = mapOf(
                        "record" to entry.recordId,
                        "vm" to entry.vmId,
                        "status" to entry.outcome.name,
                        "bytes" to bytes,
                        "reason" to (plan.reason ?: "over_soft_cap"),
                    ),
                ),
            )
        }
        return ArchiveSweepReport(
            deletedRecords = deleted,
            freedBytes = freed,
            retainedBytes = retained,
            protectedCount = plan.protectedCount,
            pendingUser = false,
        )
    }

    private fun sizeOf(entry: ArchiveEntry): Long = runCatching {
        fs.walkFiles(entry.dir).sumOf { it.bytes }
    }.getOrDefault(entry.bytes)
}
