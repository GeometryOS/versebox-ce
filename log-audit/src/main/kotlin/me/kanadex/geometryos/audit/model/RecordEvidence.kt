package me.kanadex.geometryos.audit.model

import java.util.Locale

/**
 * 一条记录的证据完整性结论（docs/log-audit/03 §5）。
 *
 * 属于审计域而不是 UI：`完整 / 部分证据 / 完整性未知` 的判定与文案只在这里出现一次，
 * 两套 UI 分支都只渲染它 —— 页面不许自己 switch `missing.reason`，也不许自己算"缺几个"。
 *
 * 只依赖 [ArchiveManifest]，不依赖 `records`/`ui` 的任何类型，因此能在 JVM 上直接单测。
 */
data class RecordEvidence(
    val present: Boolean = false,
    val manifest: ArchiveManifest? = null,
) {
    /** null = 完整性未知（旧快照没有清单）。 */
    val complete: Boolean? get() = manifest?.complete

    /** 顶部徽章文字：完整 / 部分证据 / 完整性未知。 */
    val label: String
        get() = when {
            !present -> "没有归档"
            complete == null -> "完整性未知"
            complete == true -> "完整"
            else -> "部分证据"
        }

    /** 一行说明：多少文件、缺几个、截断几个。 */
    val summary: String
        get() {
            val snapshot = manifest
            if (!present || snapshot == null) {
                return if (present) "旧快照没有清单，无法判断完整性" else "这条记录没有留下归档目录"
            }
            val files = snapshot.files.size
            val truncated = snapshot.truncatedCount
            val missing = snapshot.missing.size
            return when {
                missing == 0 && truncated == 0 -> "$files 个文件，全部完整"
                else -> "$files 个文件，" + (if (missing > 0) "缺 $missing 个" else "无缺失") +
                    (if (truncated > 0) "，$truncated 个只保留了尾部" else "")
            }
        }

    val missing: List<ManifestMissing> get() = manifest?.missing.orEmpty()

    /** 缺失原因的人话：原因串的唯一来源是 manifest。 */
    fun reasonText(reason: String): String = when {
        reason == "not_created" -> "归档时这个文件还不存在"
        reason == "over_record_budget" -> "超出单条记录预算，未归档"
        reason.startsWith("copy_failed") -> "复制失败" + reason.removePrefix("copy_failed").trim().let {
            if (it.isEmpty()) "" else "：$it"
        }
        reason.isEmpty() -> "未给出原因"
        else -> reason
    }

    /**
     * 文件条目副标题：普通给大小，被截断的写清"只留尾部多少、原始多大"。
     *
     * 参数刻意用原始值而不是归档条目类型：调用方（记录页）不必把 UI 层的
     * `RecordArchive.Entry` 带进审计域。
     */
    fun entrySummary(bytes: Long, originalBytes: Long?, truncated: String?): String {
        val stored = formatBytes(bytes)
        if (truncated == null) return stored
        val original = originalBytes?.let(::formatBytes) ?: "更大"
        return "$stored · $truncated（原始 $original）"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
