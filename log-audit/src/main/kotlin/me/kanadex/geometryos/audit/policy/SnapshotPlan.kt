package me.kanadex.geometryos.audit.policy

import me.kanadex.geometryos.audit.model.AuditPolicy

/** 归档扫描根：一个目录 + 归档里的路径前缀。 */
data class ScanRoot(val directory: String, val prefix: String)

/** 一个候选文件：来源路径、归档内路径、原始字节、重要性（小的更该留）。 */
data class SnapshotSource(
    val path: String,
    val stored: String,
    val bytes: Long,
    val priority: Int,
)

/** 计划里的一次复制。[truncated] 非 null 表示只留了尾部。 */
data class PlannedCopy(
    val path: String,
    val stored: String,
    val bytes: Long,
    val storedBytes: Long,
    val truncated: String?,
)

/** 计划里"应该归档但装不下/读不到"的一项。 */
data class PlannedMissing(val path: String, val reason: String)

data class SnapshotPlanResult(
    val copies: List<PlannedCopy>,
    val missing: List<PlannedMissing>,
) {
    val storedBytes: Long get() = copies.sumOf { it.storedBytes }

    /** 当且仅当没有任何文件被截断、也没有缺失（docs/log-audit/03 §2）。 */
    val complete: Boolean get() = missing.isEmpty() && copies.all { it.truncated == null }
}

/**
 * 单个文件"归档多大"与"哪些文件该进归档"的纯策略（docs/log-audit/03 §3）。
 *
 * 关键改动（相对旧实现）：
 * - 原始 > [AuditPolicy.fileTailBytes] 时**复制尾部**，不再整体跳过 ——
 *   崩溃现场恰恰是 `framework-server.log` 这种超过 2 MB 的文件，跳过等于丢掉唯一证据；
 * - 单条记录预算超限时不再静默丢文件，而是把它写进 missing，`reason = over_record_budget`。
 */
object SnapshotPlan {
    /** 归档白名单：只看归档内路径等价物。 */
    fun wanted(stored: String): Boolean {
        val name = stored.substringAfterLast('/')
        return when {
            name == "startup-events.log" -> true
            name == "session-state.log" -> true
            name == "operation.log" -> true
            name == "gpu-host.log" -> true
            name == "a9exec.log" -> true
            stored.contains("/framework-") && stored.endsWith(".log") -> true
            else -> false
        }
    }

    /**
     * 重要性：数字越小越先装进预算。
     *
     * 顺序来自 03 §3 的命令：`startup-events.log` → `session-state.log` →
     * 失败角色对应的 `framework-*.log` → 其余。
     */
    fun priorityOf(stored: String): Int {
        val name = stored.substringAfterLast('/')
        return when {
            name == "startup-events.log" -> 0
            name == "session-state.log" -> 1
            name == "operation.log" -> 2
            stored.contains("/framework-") && stored.endsWith(".log") -> 3
            else -> 3
        }
    }

    /**
     * @param sources 从扫描根枚举出来的候选（调用方负责去重前的基本过滤）
     * @param expected 明确"本应存在"的归档内路径（例如 `startup-events.log`）；
     *   缺了就写进 missing，并给 `not_created`，不许"什么都不说"。
     */
    fun plan(
        sources: List<SnapshotSource>,
        policy: AuditPolicy,
        expected: List<String> = emptyList(),
    ): SnapshotPlanResult {
        val ordered = sources
            .distinctBy { it.stored }
            .sortedWith(compareBy({ it.priority }, { it.stored }))
        var budget = policy.recordBytesBudget
        val copies = ArrayList<PlannedCopy>(ordered.size)
        val missing = ArrayList<PlannedMissing>()
        for (source in ordered) {
            val wantedBytes = if (source.bytes <= policy.fileTailBytes) source.bytes else policy.fileTailBytes
            if (wantedBytes > budget) {
                missing += PlannedMissing(source.stored, "over_record_budget")
                continue
            }
            budget -= wantedBytes
            val truncated = if (wantedBytes < source.bytes) tailLabel(policy.fileTailBytes) else null
            copies += PlannedCopy(source.path, source.stored, source.bytes, wantedBytes, truncated)
        }
        val present = copies.mapTo(HashSet()) { it.stored }
        for (path in expected.distinct()) {
            if (path !in present && missing.none { it.path == path }) {
                missing += PlannedMissing(path, "not_created")
            }
        }
        return SnapshotPlanResult(copies, missing)
    }

    /** `tail@2MB` —— 归档清单与 UI 徽章都读这一个字符串。 */
    fun tailLabel(fileTailBytes: Long): String {
        val mega = fileTailBytes / (1024 * 1024)
        return if (fileTailBytes % (1024 * 1024) == 0L && mega > 0) "tail@${mega}MB"
        else "tail@${fileTailBytes}B"
    }
}
