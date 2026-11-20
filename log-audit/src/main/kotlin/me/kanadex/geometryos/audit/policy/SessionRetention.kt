package me.kanadex.geometryos.audit.policy

import me.kanadex.geometryos.audit.model.AuditPolicy
import me.kanadex.geometryos.audit.model.SessionFile
import me.kanadex.geometryos.audit.model.SessionId
import me.kanadex.geometryos.audit.model.SessionSnapshot

/** 一次整组淘汰：会话、字节、文件清单（含大小/mtime，执行时用来验"没被边写边删"）、原因。 */
data class SessionEviction(
    val session: SessionId,
    val bytes: Long,
    val files: List<SessionFile>,
    val groupDir: String?,
    val reason: String,
)

data class SessionRetentionPlan(
    val evictions: List<SessionEviction>,
    val keptSessions: Int,
    val totalBytes: Long,
    val retainedBytes: Long,
) {
    val evictedBytes: Long get() = totalBytes - retainedBytes
}

/**
 * 运行期日志的淘汰策略（docs/log-audit/02 §2.2）：**按会话整组**，不按文件、不按天。
 *
 * 实测依据（01 §3）：单次会话会有 44 个 `application-*.log`，
 * "保留最近 200 个文件"会把同一次会话切一半，留下"有现场但缺一半"的假证据。
 *
 * 硬约束：
 * 1. 活跃会话永不出现在计划里；
 * 2. 没有结束记录的会话（进程被杀、台账没写完）也不淘汰 —— 先由 SessionScanner.closeStale 收尾；
 * 3. legacy（无会话标识的旧文件）第一顺位，按天桶从旧到新；
 * 4. 最新的一次真实会话永远保留，即便它自己就超了总量上限。
 */
object SessionRetention {
    fun plan(sessions: List<SessionSnapshot>, policy: AuditPolicy): SessionRetentionPlan {
        val normalized = policy.normalized()
        val totalBytes = sessions.sumOf { it.bytes }
        val evictions = ArrayList<SessionEviction>()

        val legacy = sessions.filter { it.legacy && !it.active }.sortedBy { it.lastWriteAt }
        val real = sessions.filter { !it.legacy }
        val closed = real.filter { it.isClosed }.sortedBy { it.lastWriteAt }
        val newest = closed.lastOrNull()?.id

        var retained = totalBytes

        fun evict(snapshot: SessionSnapshot, reason: String) {
            if (evictions.any { it.session == snapshot.id }) return
            evictions += SessionEviction(
                session = snapshot.id,
                bytes = snapshot.bytes,
                files = snapshot.files,
                groupDir = snapshot.groupDir,
                reason = reason,
            )
            retained -= snapshot.bytes
        }

        // 1) legacy 先行：它没有完整性可言
        for (snapshot in legacy) evict(snapshot, "legacy")

        // 2) 会话数超限：从旧到新整组淘汰
        val byRecency = closed.sortedByDescending { it.lastWriteAt }
        for (snapshot in byRecency.drop(normalized.keepSessions)) {
            if (snapshot.id == newest) continue
            evict(snapshot, "over_count")
        }

        // 3) 总量仍超上限：继续从旧到新，但保留最新一次
        if (retained > normalized.sessionBytesCap) {
            for (snapshot in closed) {
                if (retained <= normalized.sessionBytesCap) break
                if (snapshot.id == newest) continue
                evict(snapshot, "over_bytes")
            }
        }

        val kept = sessions.count { snapshot -> evictions.none { it.session == snapshot.id } }
        return SessionRetentionPlan(
            evictions = evictions,
            keptSessions = kept,
            totalBytes = totalBytes,
            retainedBytes = retained,
        )
    }
}
