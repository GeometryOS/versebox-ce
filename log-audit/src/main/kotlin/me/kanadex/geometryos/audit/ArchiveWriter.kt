package me.kanadex.geometryos.audit

import me.kanadex.geometryos.audit.json.ManifestJson
import me.kanadex.geometryos.audit.model.ArchiveManifest
import me.kanadex.geometryos.audit.model.ArchiveOutcome
import me.kanadex.geometryos.audit.model.AuditEvent
import me.kanadex.geometryos.audit.model.AuditEventKind
import me.kanadex.geometryos.audit.model.AuditPolicy
import me.kanadex.geometryos.audit.model.ManifestFile
import me.kanadex.geometryos.audit.model.ManifestMissing
import me.kanadex.geometryos.audit.model.SessionId
import me.kanadex.geometryos.audit.port.AuditClock
import me.kanadex.geometryos.audit.port.AuditEventSink
import me.kanadex.geometryos.audit.port.AuditFileSystem
import me.kanadex.geometryos.audit.policy.ScanRoot
import me.kanadex.geometryos.audit.policy.SnapshotPlan
import me.kanadex.geometryos.audit.policy.SnapshotSource

/** 归档请求：调用方（RecordCollector）只填事实，白名单/预算/取尾都在本模块。 */
data class ArchiveRequest(
    val vmId: String,
    val recordId: Long,
    val sessionId: SessionId,
    /** 记录事件类型（`LAUNCH` / `CRASH` …），只用于清单自述。 */
    val kind: String,
    val status: String,
    val outcome: ArchiveOutcome,
    /** 最终归档目录（绝对路径）。 */
    val targetDir: String,
    /** 扫描根（由 :app 的适配层给出，不在这里拼 `filesDir`）。 */
    val roots: List<ScanRoot>,
    /** 明确"本应存在"的归档内路径，缺了要写 missing。 */
    val expected: List<String> = listOf("startup-events.log"),
)

sealed interface ArchiveResult {
    data class Success(val manifest: ArchiveManifest) : ArchiveResult

    data class Failed(val message: String) : ArchiveResult
}

/**
 * 执行复制计划并产出 `manifest.json`（docs/log-audit/03 §3、§4）。
 *
 * 原子性：先写 `<vmId>/.tmp-<recordId>/`，全部成功再同目录 rename 成 `<recordId>/`；
 * 失败则删掉临时目录并返回 [ArchiveResult.Failed] —— 不允许留下"有目录、没清单"的半个快照。
 */
class ArchiveWriter(
    private val fs: AuditFileSystem,
    private val clock: AuditClock,
    private val sink: AuditEventSink,
) {
    fun write(request: ArchiveRequest, policy: AuditPolicy): ArchiveResult {
        val normalized = policy.normalized()
        val target = request.targetDir.trimEnd('/')
        val parent = target.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isEmpty()) return ArchiveResult.Failed("归档目录非法：${request.targetDir}")
        val tmp = "$parent/.tmp-${request.recordId}"

        val sources = ArrayList<SnapshotSource>()
        for (root in request.roots) {
            if (!fs.isDirectory(root.directory)) continue
            for (entry in fs.list(root.directory)) {
                if (entry.directory) continue
                val stored = root.prefix + entry.path.substringAfterLast('/')
                if (!SnapshotPlan.wanted(stored)) continue
                sources += SnapshotSource(
                    path = entry.path,
                    stored = stored,
                    bytes = entry.bytes,
                    priority = SnapshotPlan.priorityOf(stored),
                )
            }
        }
        val plan = SnapshotPlan.plan(sources, normalized, request.expected)

        val files = ArrayList<ManifestFile>(plan.copies.size)
        val missing = ArrayList<ManifestMissing>(plan.missing.size + 2)
        for (item in plan.missing) missing += ManifestMissing(item.path, item.reason)
        fs.deleteRecursively(tmp)
        fs.makeDirectories(tmp)
        for (copy in plan.copies) {
            val targetPath = "$tmp/${copy.stored}"
            val stored = runCatching {
                if (copy.truncated == null) {
                    fs.copyFile(copy.path, targetPath)
                } else {
                    fs.copyTail(copy.path, targetPath, normalized.fileTailBytes)
                }
            }.getOrElse { failure ->
                missing += ManifestMissing(copy.stored, "copy_failed: ${failure.message.orEmpty().take(120)}")
                return@getOrElse -1L
            }
            if (stored < 0L) continue
            files += ManifestFile(
                path = copy.stored,
                bytes = copy.bytes,
                stored = stored,
                truncated = copy.truncated,
            )
        }

        val manifest = ArchiveManifest(
            recordId = request.recordId,
            vmId = request.vmId,
            sessionId = request.sessionId,
            kind = request.kind,
            status = request.status,
            createdAt = clock.now(),
            files = files,
            missing = missing,
            complete = missing.isEmpty() && files.all { it.truncated == null },
        )
        return runCatching {
            fs.writeText("$tmp/manifest.json", ManifestJson.encode(manifest))
            // 同一记录被重复收尾（例如孤儿记录恢复）时目标目录可能已存在：
            // rename 到已存在的非空目录会失败，所以先清掉旧的那份。
            if (fs.exists(target)) fs.deleteRecursively(target)
            if (!fs.rename(tmp, target)) {
                fs.deleteRecursively(tmp)
                return@runCatching ArchiveResult.Failed("rename 失败：$tmp → $target")
            }
            sink.emit(
                AuditEvent(
                    at = clock.now(),
                    kind = if (manifest.complete) {
                        AuditEventKind.ARCHIVE_WRITTEN
                    } else {
                        AuditEventKind.ARCHIVE_INCOMPLETE
                    },
                    fields = mapOf(
                        "vm" to request.vmId,
                        "record" to request.recordId,
                        "session" to request.sessionId.value,
                        "files" to manifest.files.size.toLong(),
                        "stored" to manifest.storedBytes,
                        "complete" to manifest.complete,
                    ),
                ),
            )
            ArchiveResult.Success(manifest)
        }.getOrElse { failure ->
            fs.deleteRecursively(tmp)
            ArchiveResult.Failed("归档写入失败：${failure.message}")
        }
    }
}
