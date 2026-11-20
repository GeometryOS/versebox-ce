// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.audit.android

import android.content.Context
import android.util.Log
import me.kanadex.geometryos.audit.ArchiveRequest
import me.kanadex.geometryos.audit.ArchiveSweepReport
import me.kanadex.geometryos.audit.AuditTrigger
import me.kanadex.geometryos.audit.DeviceLayout
import me.kanadex.geometryos.audit.MaintenanceReport
import me.kanadex.geometryos.audit.model.ArchiveEntry
import me.kanadex.geometryos.audit.model.ArchiveOutcome
import me.kanadex.geometryos.audit.model.SessionId
import me.kanadex.geometryos.audit.policy.ScanRoot
import io.a10vm.records.RecordKind
import io.a10vm.records.RecordStatus
import io.a10vm.records.RecordStore
import io.a10vm.records.VmRecord
import io.a10vm.runtime.VmDevice
import io.a10vm.runtime.VmRegistry
import java.io.File

/**
 * 审计库与项目数据之间的**唯一桥梁**（docs/log-audit/04 §3）。
 *
 * 它做三件事，且只做这三件：
 * 1. 把 [VmDevice] / [VmRecord] 翻译成审计库认识的结构（路径布局、归档索引行）；
 * 2. 把审计库的结论（删了哪些记录）落回 `records.db` —— 库不认识数据库，索引行归这里删；
 * 3. 提供"现在是哪些会话还活着"的唯一答案，清理永不碰活跃会话。
 *
 * 一切方法都不在主线程执行：调用方要么在 Service 的 worker 线程，要么在
 * [AuditBootstrap.scope] 上。
 */
object AuditIndex {
    private const val TAG = "A10VM_AUDIT"

    /** 一台虚拟机的审计路径布局。 */
    fun layout(context: Context, device: VmDevice): DeviceLayout {
        val registry = VmRegistry.get(context)
        val runtime = registry.runtimeOf(device)
        return DeviceLayout(
            vmId = device.id,
            runtimeDir = runtime.absolutePath,
            sessionsDir = File(runtime, "sessions").absolutePath,
            windowLogPath = File(registry.windowOf(device), "gpu-host.log").absolutePath,
        )
    }

    fun layouts(context: Context): List<DeviceLayout> =
        VmRegistry.get(context).list().map { layout(context, it) }

    /** 记录状态 → 淘汰保护级别。进行中也算受保护：绝不能删一条还在写的记录。 */
    fun outcome(status: RecordStatus, kind: RecordKind): ArchiveOutcome = when {
        kind == RecordKind.CRASH -> ArchiveOutcome.PROTECTED
        status == RecordStatus.FAILED -> ArchiveOutcome.PROTECTED
        status == RecordStatus.RUNNING -> ArchiveOutcome.PROTECTED
        status == RecordStatus.CANCELLED -> ArchiveOutcome.CANCELLED
        else -> ArchiveOutcome.SUCCESS
    }

    /**
     * 归档请求：调用方只给事实（哪条记录、哪个会话、什么状态），
     * 扫描根由这里按 [VmDevice] 的布局给 —— RecordCollector 不再知道 `vm/` 在哪。
     */
    fun archiveRequest(
        context: Context,
        device: VmDevice,
        recordId: Long,
        session: SessionId,
        kind: RecordKind,
        status: RecordStatus,
    ): ArchiveRequest {
        val registry = VmRegistry.get(context)
        val root = registry.rootOf(device)
        val runtime = registry.runtimeOf(device)
        val window = registry.windowOf(device)
        return ArchiveRequest(
            vmId = device.id,
            recordId = recordId,
            sessionId = session,
            kind = kind.name,
            status = status.name,
            outcome = outcome(status, kind),
            targetDir = File(File(context.filesDir, "records"), "${device.id}/$recordId").absolutePath,
            roots = listOf(
                ScanRoot(root.absolutePath, ""),
                ScanRoot(File(root, "vm").absolutePath, "vm/"),
                ScanRoot(window.absolutePath, "native-window/"),
                ScanRoot(runtime.absolutePath, "runtime/"),
                ScanRoot(File(runtime, "vm").absolutePath, "runtime/vm/"),
            ),
        )
    }

    /** 归档索引（`records.db` + 目录大小）→ 审计库的淘汰视图。 */
    fun archiveEntries(context: Context): List<ArchiveEntry> {
        val fs = AuditBootstrap.fileSystem(context)
        val rows = runCatching { RecordStore.get(context).query() }.getOrElse {
            Log.w(TAG, "读取记录索引失败：${it.message}")
            return emptyList()
        }
        return rows.mapNotNull { record -> archiveEntry(context, fs, record) }
    }

    private fun archiveEntry(
        context: Context,
        fs: AndroidAuditFileSystem,
        record: VmRecord,
    ): ArchiveEntry? {
        val relative = record.logDir ?: return null
        val dir = File(context.filesDir, relative)
        if (!dir.isDirectory) return null
        val bytes = fs.walkFiles(dir.absolutePath).sumOf { it.bytes }
        return ArchiveEntry(
            recordId = record.id,
            vmId = record.vmId,
            outcome = outcome(record.status, record.kind),
            bytes = bytes,
            finishedAt = record.finishedAt ?: record.startedAt,
            dir = dir.absolutePath,
        )
    }

    /** 走一次唯一自动清理入口，并把索引行删掉。 */
    suspend fun maintain(
        context: Context,
        trigger: AuditTrigger,
        live: Set<SessionId> = emptySet(),
    ): MaintenanceReport? = runCatching {
        val report = AuditBootstrap.engine(context).maintain(
            trigger = trigger,
            devices = layouts(context),
            archives = archiveEntries(context),
            live = live,
        )
        if (report.deletedRecords.isNotEmpty()) {
            RecordStore.get(context).delete(report.deletedRecords)
            Log.i(TAG, "归档淘汰收尾：删索引 ${report.deletedRecords.size} 条")
        }
        report
    }.onFailure { Log.w(TAG, "maintain 失败：${it.message}") }.getOrNull()

    /** 用户显式清理（`confirm = false` 只重算状态，不删）。 */
    suspend fun cleanup(context: Context, confirm: Boolean): ArchiveSweepReport? = runCatching {
        val report = AuditBootstrap.engine(context).cleanupSuccessArchives(confirm, archiveEntries(context))
        if (report.deletedRecords.isNotEmpty()) {
            RecordStore.get(context).delete(report.deletedRecords)
        }
        report
    }.onFailure { Log.w(TAG, "cleanup 失败：${it.message}") }.getOrNull()
}
