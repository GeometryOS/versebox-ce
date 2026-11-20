package io.a10vm.records

import android.content.Context
import me.kanadex.geometryos.audit.json.ManifestJson
import me.kanadex.geometryos.audit.model.ArchiveManifest
import me.kanadex.geometryos.audit.model.RecordEvidence
import java.io.File

/**
 * 归档目录的只读访问层。
 *
 * UI 里的「预览日志原文」只经过这里，不自己拼路径、不自己读文件；
 * 这样归档布局变化（比如以后加 json 清单）不会波及页面。
 */
object RecordArchive {
    /** 单次预览上限：超过就只给前 256 KB，避免把 12 MB 一次性塞进内存。 */
    private const val PREVIEW_LIMIT = 256 * 1024

    /** [bytes] 是归档里实际大小；[originalBytes] / [truncated] 来自 manifest（可能为 null = 旧快照）。 */
    data class Entry(
        val path: String,
        val bytes: Long,
        val originalBytes: Long? = null,
        val truncated: String? = null,
    )

    /** 清单文件名：它自己不进"日志原文"列表，避免用户点开一堆 JSON。 */
    private const val MANIFEST_NAME = "manifest.json"

    private fun dir(context: Context, record: VmRecord): File? {
        val relative = record.logDir ?: return null
        val dir = File(context.filesDir, relative)
        return dir.takeIf { it.isDirectory }
    }

    fun entries(context: Context, record: VmRecord): List<Entry> {
        val root = dir(context, record) ?: return emptyList()
        val manifest = manifest(context, record)
        val byPath = manifest?.files?.associateBy { it.path }.orEmpty()
        return root.walkTopDown()
            .filter { it.isFile && it.name != MANIFEST_NAME }
            .map { file ->
                val path = file.relativeTo(root).path.replace('\\', '/')
                val recorded = byPath[path]
                Entry(
                    path = path,
                    bytes = file.length(),
                    originalBytes = recorded?.bytes,
                    truncated = recorded?.truncated,
                )
            }
            .sortedBy { it.path }
            .toList()
    }

    /** 读清单；没有或结构不认识都返回 null（=`完整性未知`，不删、不改写）。 */
    fun manifest(context: Context, record: VmRecord): ArchiveManifest? {
        val root = dir(context, record) ?: return null
        val file = File(root, MANIFEST_NAME)
        if (!file.isFile) return null
        return runCatching { ManifestJson.decode(file.readText()) }.getOrNull()
    }

    /** 记录详情页要的完整性结论：页面只渲染它。 */
    fun evidence(context: Context, record: VmRecord): RecordEvidence {
        val root = dir(context, record) ?: return RecordEvidence(present = false)
        return RecordEvidence(present = true, manifest = manifest(context, record))
    }

    fun hasArchive(context: Context, record: VmRecord): Boolean = dir(context, record) != null

    /** 读取归档里某个文件的文本（截断到 [PREVIEW_LIMIT]）。 */
    fun readText(context: Context, record: VmRecord, path: String): String {
        val root = dir(context, record) ?: return "归档目录不存在（可能已被清理）"
        val file = File(root, path)
        if (!file.isFile) return "归档里没有这个文件：$path"
        if (file.canonicalPath.startsWith(root.canonicalPath).not()) return "非法路径：$path"
        return runCatching {
            val length = file.length()
            val body = if (length <= PREVIEW_LIMIT) {
                file.readText()
            } else {
                file.inputStream().use { stream ->
                    val buffer = ByteArray(PREVIEW_LIMIT)
                    val read = stream.read(buffer)
                    String(buffer, 0, read.coerceAtLeast(0)) +
                        "\n\n…（此处截断，全文 $length 字节，请导出后查看）"
                }
            }
            // 归档时被取尾的文件必须在这里说清楚，否则用户看到的是"日志从中间开始"
            // 却不知道为什么（docs/log-audit/03 §5 的最后一条）。
            val note = manifest(context, record)
                ?.files
                ?.firstOrNull { it.path == path && it.truncated != null }
                ?.let { file -> "…（归档时只保留了 $file.truncated，原始 $file.bytes 字节）\n\n" }
                .orEmpty()
            note + body
        }.getOrElse { "读取失败：${it.message}" }
    }

    /** 概览用：把最有价值的那份时间线读出来。 */
    fun startupLog(context: Context, record: VmRecord): String {
        val entry = entries(context, record).firstOrNull { it.path.endsWith("startup-events.log") }
            ?: return ""
        return readText(context, record, entry.path)
    }
}
