package me.kanadex.geometryos.audit

import me.kanadex.geometryos.audit.model.AuditEvent
import me.kanadex.geometryos.audit.model.AuditEventKind
import me.kanadex.geometryos.audit.port.AuditClock
import me.kanadex.geometryos.audit.port.AuditEventSink
import me.kanadex.geometryos.audit.port.AuditFileSystem
import me.kanadex.geometryos.audit.policy.SessionRetentionPlan

data class SessionSweepReport(
    val evictedSessions: Int,
    val freedBytes: Long,
    val skippedSessions: Int,
    val retainedBytes: Long,
    val keptSessions: Int,
)

/**
 * 执行会话淘汰计划（docs/log-audit/02 §2.3）。
 *
 * 删除前一定重测：计划是快照，落盘时要确认文件没变大、没变新；
 * 只要有一个文件在快照之后又被写过，就整组跳过并留一条 `session_skip` 事件 ——
 * 绝不"边写边删"，也绝不删出一个半截会话。
 */
class SessionJanitor(
    private val fs: AuditFileSystem,
    private val clock: AuditClock,
    private val sink: AuditEventSink,
) {
    fun sweep(plan: SessionRetentionPlan): SessionSweepReport {
        var freed = 0L
        var evicted = 0
        var skipped = 0
        for (eviction in plan.evictions) {
            if (changedSinceSnapshot(eviction.files)) {
                skipped++
                sink.emit(
                    AuditEvent(
                        at = clock.now(),
                        kind = AuditEventKind.SESSION_SKIP,
                        fields = mapOf(
                            "session" to eviction.session.value,
                            "reason" to "written_during_sweep",
                        ),
                    ),
                )
                continue
            }
            var bytes = 0L
            for (file in eviction.files) {
                bytes += fs.size(file.path)
                fs.delete(file.path)
            }
            // 只有已结束的会话才会进计划。目录里若还留着快照之后冒出来的文件，就只删计划内的，
            // 不整目录带走 —— 宁可留一个空壳，也不删掉没见过的证据。
            eviction.groupDir?.let { dir ->
                if (fs.walkFiles(dir).isEmpty()) runCatching { fs.deleteRecursively(dir) }
            }
            freed += bytes
            evicted++
            sink.emit(
                AuditEvent(
                    at = clock.now(),
                    kind = AuditEventKind.SESSION_EVICT,
                    fields = mapOf(
                        "session" to eviction.session.value,
                        "bytes" to bytes,
                        "files" to eviction.files.size.toLong(),
                        "reason" to eviction.reason,
                    ),
                ),
            )
        }
        return SessionSweepReport(
            evictedSessions = evicted,
            freedBytes = freed,
            skippedSessions = skipped,
            retainedBytes = plan.retainedBytes,
            keptSessions = plan.keptSessions,
        )
    }

    /** 大小或 mtime 变了就认为它还在被写：整组跳过，不"删一半"。 */
    private fun changedSinceSnapshot(files: List<me.kanadex.geometryos.audit.model.SessionFile>): Boolean =
        files.any { file ->
            val size = fs.size(file.path)
            if (size <= 0L) {
                false
            } else {
                size != file.bytes || fs.modifiedAt(file.path) != file.modifiedAt
            }
        }
}
