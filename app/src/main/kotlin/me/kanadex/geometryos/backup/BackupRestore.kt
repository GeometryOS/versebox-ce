// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup

import android.content.Context
import android.net.Uri
import io.a10vm.records.RecordCollector
import io.a10vm.records.RecordKind
import io.a10vm.records.RecordStatus
import io.a10vm.runtime.VmDevice
import io.a10vm.runtime.VmRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import me.kanadex.geometryos.audit.android.AuditBootstrap
import me.kanadex.geometryos.rom.RomPackage
import me.kanadex.geometryos.rom.RomRepository

/*
 * Restoration stages verified files and hands the embedded ROM to the ROM management flow.
 * The registry publishes the new instance only after that flow returns successfully.
 */
internal class BackupRestore(context: Context) {
    private val app = context.applicationContext
    private val library = BackupLibrary.get(app)
    private val registry by lazy { VmRegistry.get(app) }

    class Prepared internal constructor(
        internal val work: File,
        internal val archive: File,
        internal val tree: File,
        internal val manifest: BackupManifest,
        val romUri: Uri?,
        internal val startedAt: Long,
        internal val events: MutableList<String>,
    )

    fun prepare(
        id: UUID,
        publish: (BackupArchive.Progress) -> Unit,
        checkActive: () -> Unit,
        onStage: (String) -> Unit = {},
    ): Prepared {
        val startedAt = System.currentTimeMillis()
        val events = mutableListOf("读取备份 " + id)
        onStage("正在读取备份清单")
        val work = library.newWorkspace()
        var manifest: BackupManifest? = null
        try {
            val archive = library.archive(id)
            val meta = BackupArchive.readManifest(archive).also { require(it.id == id) }
            manifest = meta
            val tree = work.resolve("instance")
            var last: BackupArchive.Progress? = null
            var lastRecorded = 0L
            val progress: (BackupArchive.Progress) -> Unit = { value ->
                val now = System.nanoTime()
                if (last?.phase != value.phase || now - lastRecorded >= 1_000_000_000L ||
                    (value.files == value.totalFiles && last?.files != value.files)) {
                    events += "phase=" + value.phase + " files=" + value.files + "/" + value.totalFiles + " bytes=" + value.bytes
                    lastRecorded = now
                }
                last = value
                publish(value)
            }
            onStage("正在校验备份内容")
            val verified = BackupArchive.verify(
                archive, progress = progress, checkActive = checkActive, onOmitted = { omitted ->
                    events += "备份内省略运行期端点 " + omitted.runtimeEntries + " 个，按原文保存链接 " +
                        omitted.literalLinks + " 个"
                },
            )
            check(verified == meta)
            events += "备份条目已逐项校验"
            val romUri = meta.romArchive?.let {
                onStage("正在提取备份中的 ROM")
                events += "正在提取备份中的 ROM"
                val rom = work.resolve("rom.zip")
                check(BackupArchive.extractRom(archive, rom, progress, checkActive) == meta)
                val selected = meta.settings.rom
                val properties = RomPackage.read(rom)
                require(!properties.placeholder && properties.key == selected.key &&
                    properties.payloadSha256 == selected.payloadSha256) { "备份 ROM 与所选版本不一致" }
                events += "ROM 原包已验证，等待 ROM 管理导入 " + properties.key
                Uri.fromFile(rom)
            }
            return Prepared(work, archive, tree, meta, romUri, startedAt, events)
        } catch (failure: Exception) {
            cleanup(work)
            record(id, manifest, startedAt, events, null, failure)
            throw failure
        }
    }

    fun commit(prepared: Prepared, name: String?, imported: RomRepository.Outcome?,
        publish: (BackupArchive.Progress) -> Unit = {}, checkActive: () -> Unit = {},
        onStage: (String) -> Unit = {},
    ): VmDevice {
        val (manifest, events) = prepared.manifest to prepared.events
        try {
            val source = manifest.settings
            val selection = source.rom
            val binding = if (prepared.romUri != null) {
                val outcome = requireNotNull(imported) { "ROM 导入尚未完成" }
                val row = outcome.row
                require(row.key == selection.key && row.payloadSha256 == selection.payloadSha256) {
                    "ROM 导入返回了不同的版本"
                }
                events += "ROM 管理导入返回 source=" + row.source.name + " duplicate=" + outcome.duplicate
                selection.copy(source = row.source.name)
            } else {
                require(imported == null)
                events += "备份未携带 ROM 原包，保留所选版本"
                selection
            }
            var lastReport = 0L
            onStage("正在恢复虚拟机文件")
            check(BackupArchive.restoreTree(prepared.archive, prepared.tree, { progress ->
                val now = System.nanoTime()
                if (now - lastReport >= 1_000_000_000L || progress.files == progress.totalFiles) {
                    events += "恢复实例文件 " + progress.files + "/" + progress.totalFiles + " bytes=" + progress.bytes
                    lastReport = now
                    publish(progress)
                }
            }, checkActive) == manifest)
            checkActive()
            onStage("正在登记新虚拟机")
            events += "正在登记新虚拟机"
            val device = registry.commitRestored(
                source.copy(name = name?.ifBlank { source.name } ?: source.name, rom = binding),
                prepared.tree,
                events::add,
            )
            events += "新实例设置写入并读回成功 id=" + device.id
            record(manifest.id, manifest, prepared.startedAt, events, device, null)
            return device
        } catch (failure: Exception) {
            record(manifest.id, manifest, prepared.startedAt, events, null, failure)
            throw failure
        } finally {
            cleanup(prepared.work)
        }
    }

    fun abort(prepared: Prepared, failure: Exception) {
        cleanup(prepared.work)
        record(prepared.manifest.id, prepared.manifest, prepared.startedAt, prepared.events, null, failure)
    }

    private fun cleanup(work: File) {
        try {
            Files.walk(work.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        } catch (failure: Exception) {
            AuditBootstrap.emit(app, "vm_backup_staging_cleanup_failed", mapOf("reason" to failure.message.orEmpty()))
        }
    }

    private fun record(id: UUID, manifest: BackupManifest?, startedAt: Long, events: List<String>,
        device: VmDevice?, failure: Exception?) {
        val status = when (failure) {
            null -> RecordStatus.SUCCESS
            is CancellationException -> RecordStatus.CANCELLED
            else -> RecordStatus.FAILED
        }
        val settings = manifest?.settings
        RecordCollector.recordMeta(app, device?.id ?: id.toString(), device?.name ?: settings?.name.orEmpty(),
            settings?.rom?.key.orEmpty(), settings?.rom?.name, 0, RecordKind.RESTORE_VM, status,
            failure?.message ?: "已恢复虚拟机", startedAt, events.toList(), failure)
        AuditBootstrap.emit(app, "vm_backup_restore", mapOf("backup" to id.toString(),
            "vm" to device?.id.orEmpty(), "status" to status.name, "reason" to failure?.message.orEmpty()))
    }
}
