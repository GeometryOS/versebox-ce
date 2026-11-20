package io.a10vm.records

import io.a10vm.ui.BootLogText

/**
 * 开机/运行日志的摘要器。
 *
 * 输入是运行时写出来的 `startup-events.log` 行（`[012.34s] SPAWN  input process started`），
 * 输出是「人看的一句话」。**只读不写**：日志格式由运行时决定，这里绝不回写文件。
 *
 * 之所以单独一个文件：它是纯函数，能被记录列表、概览页、导出包共用，
 * 也可以在没有 Android 环境的情况下单测（不依赖 Context）。
 */
object LogDigest {
    private val linePattern = Regex("^\\[(\\d+\\.\\d+)s]\\s+([A-Z-]+)\\s*(.*)$")

    /** 里程碑标签：只挑这些进概览，避免把 200 行进度刷屏。 */
    private val milestones = listOf(
        "BOOT", "PREPARE", "SHMEM", "ART", "DEVICES", "GPU", "READY",
    )

    data class Line(val seconds: Double, val tag: String, val detail: String) {
        val text: String get() = "[%.2fs] %s %s".format(seconds, tag, detail)
        val human: String get() = BootLogText.message("[%.2fs] %s  %s".format(seconds, tag, detail))
    }

    fun parse(text: String): List<Line> = text.lineSequence().mapNotNull { raw ->
        val match = linePattern.find(raw.trim()) ?: return@mapNotNull null
        Line(match.groupValues[1].toDoubleOrNull() ?: 0.0, match.groupValues[2], match.groupValues[3])
    }.toList()

    /** 概览里的关键节点（最多 8 条，按时间顺序）。 */
    fun milestones(lines: List<Line>): List<String> {
        val picked = mutableListOf<String>()
        for (line in lines) {
            val keep = when {
                line.tag in milestones -> true
                line.tag == "VERIFY" && line.detail.contains("/") ->
                    line.detail.substringAfter(' ').let { it.substringBefore('/') == it.substringAfter('/') }
                line.tag == "DATA" && line.detail.contains("/") ->
                    line.detail.substringAfter(' ').let { it.substringBefore('/') == it.substringAfter('/') }
                else -> false
            }
            if (keep) picked += line.text
        }
        return picked.takeLast(8)
    }

    /** 失败原因：第一条 ERROR/FATAL/abort 行，压成一句人话；没有就返回 null。 */
    fun failure(lines: List<Line>): String? {
        val hit = lines.firstOrNull { it.tag in setOf("ERROR", "FATAL") || it.detail.startsWith("ERROR") }
            ?: return null
        val reason = hit.detail.ifBlank { hit.tag }
        return reason.take(160)
    }

    /** 成功但不完整（没走到 「framework accepting input」）时，用最后一条事件说明卡在哪。 */
    fun lastEvent(lines: List<Line>): String? = lines.lastOrNull()?.human

    fun bootComplete(lines: List<Line>): Boolean =
        lines.any { it.tag == "READY" && it.detail.contains("accepting input") }

    fun durationMs(lines: List<Line>): Long = ((lines.lastOrNull()?.seconds ?: 0.0) * 1000).toLong()
}
