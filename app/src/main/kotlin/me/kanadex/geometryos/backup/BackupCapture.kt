// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup

import android.content.Context
import io.a10vm.records.RecordCollector
import io.a10vm.records.RecordKind
import io.a10vm.records.RecordStatus
import io.a10vm.runtime.VmDevice
import io.a10vm.runtime.VmRegistry
import io.a10vm.runtime.VmService
import java.io.File
import java.util.UUID
import org.json.JSONObject
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import me.kanadex.geometryos.audit.android.AuditBootstrap
import me.kanadex.geometryos.rom.RomKeys
import me.kanadex.geometryos.rom.RomPackage
import me.kanadex.geometryos.rom.RomRef
import me.kanadex.geometryos.rom.RomRepository
import me.kanadex.geometryos.rom.RomSource

/*
 * Captures a registry snapshot and archive under the VM service's stopped-instance reservation.
 * The library indexes the archive only after the complete write and verification pass.
 */
internal class BackupCapture(context: Context) {
    private val app = context.applicationContext
    private val library = BackupLibrary.get(app)

    suspend fun create(
        id: String,
        includeRom: Boolean,
        publish: (BackupArchive.Progress) -> Unit,
        onStage: (String) -> Unit = {},
    ): UUID = VmService.captureStopped(id) { device, checkActive ->
        capture(device, includeRom, publish, onStage, checkActive)
    }

    private fun capture(
        device: VmDevice,
        includeRom: Boolean,
        publish: (BackupArchive.Progress) -> Unit,
        onStage: (String) -> Unit,
        checkActive: () -> Unit,
    ): UUID {
        val started = System.currentTimeMillis()
        val events = mutableListOf("服务已确认实例停止")
        val repository = RomRepository.get(app)
        var result: UUID? = null
        var failure: Exception? = null
        try {
            checkActive()
            require(!device.legacy && device.root.isNotEmpty()) { "旧布局实例尚无法创建完整备份" }
            onStage("正在读取虚拟机设置")
            val work = library.newWorkspace()
            try {
                val source = RomSource.entries.firstOrNull { it.name == device.romSource }
                val ref = RomRef(device.romId, source, device.romPayload)
                val row = repository.rowFor(ref)
                val selection = BackupRomSelection(RomKeys.idOf(device.romId), RomKeys.versionOf(device.romId),
                    device.romPayload, device.romSource, row?.displayName ?: device.romId)
                events += "已读取名称、显示模式和 ROM 选择 " + selection.key
                val rom = if (includeRom) {
                    val file = work.resolve("rom.zip")
                    val total = row?.archiveBytes ?: 0L
                    var copied = 0L
                    var reported = 0L
                    onStage(backupCopyStage("正在复制所选 ROM 原包", 0L, total))
                    repository.openBackupArchive(ref).use { input ->
                        file.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                copied += count
                                val now = System.nanoTime()
                                if (now - reported >= 500_000_000L) {
                                    reported = now
                                    onStage(backupCopyStage("正在复制所选 ROM 原包", copied, total))
                                }
                            }
                        }
                    }
                    onStage(backupCopyStage("正在复制所选 ROM 原包", copied, total))
                    val properties = RomPackage.read(file)
                    check(properties.key == selection.key && properties.payloadSha256 == selection.payloadSha256)
                    events += "已复制所选 ROM 原包 bytes=" + file.length()
                    file
                } else {
                    events += "保留 ROM 选择，省略原包"
                    null
                }
                val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
                val client = BackupClient(app.packageName, packageInfo.versionName.orEmpty(), packageInfo.longVersionCode)
                var lastPhase: BackupArchive.Phase? = null
                var lastTime = 0L
                var lastFiles = -1
                var lastPublish = 0L
                // 归档按 64 KB 回调进度；这里再按 250ms 节流，避免每块都触发一次状态更新与重组。
                val progress: (BackupArchive.Progress) -> Unit = { value ->
                    val now = System.nanoTime()
                    val terminal = value.totalFiles > 0 && value.files == value.totalFiles
                    val record = value.phase != lastPhase || now - lastTime >= 1_000_000_000L ||
                        (terminal && value.files != lastFiles)
                    if (record) {
                        events += "phase=" + value.phase + " files=" + value.files + "/" + value.totalFiles + " bytes=" + value.bytes
                        lastPhase = value.phase
                        lastTime = now
                        lastFiles = value.files
                    }
                    if (record || terminal || now - lastPublish >= 250_000_000L) {
                        lastPublish = now
                        publish(value)
                    }
                }
                events += "正在写入备份包"
                onStage("正在写入备份包")
                val staged = work.resolve("backup.zip")
                val root = VmRegistry.get(app).rootOf(device)
                val manifest = BackupArchive.write(staged, root,
                    BackupVmSettings(device.name, device.displayMode, selection), client, rom, progress, checkActive,
                    derivedLinks(repository, row, rom, root),
                    onOmitted = { omitted ->
                        events += "省略运行期端点 " + omitted.runtimeEntries + " 个，派生链接 " + omitted.derivedLinks +
                            " 个，按原文保存链接 " + omitted.literalLinks + " 个"
                    })
                checkActive()
                events += "正在登记备份"
                onStage("正在登记备份")
                return library.publish(staged, manifest).also {
                    result = it
                    events += "已登记备份 " + it
                }
            } finally {
                work.deleteRecursively()
            }
        } catch (error: Exception) {
            failure = error
            throw error
        } finally {
            val issue = failure
            val status = when {
                issue is CancellationException -> RecordStatus.CANCELLED
                result != null -> RecordStatus.SUCCESS
                else -> RecordStatus.FAILED
            }
            RecordCollector.recordMeta(app, device.id, device.name, device.romId, null, 0, RecordKind.BACKUP_VM,
                status, issue?.message ?: "备份虚拟机", started, events, issue)
            AuditBootstrap.emit(app, "vm_backup_create", mapOf("vm" to device.id, "backup" to result?.toString().orEmpty(),
                "status" to status.name, "reason" to issue?.message.orEmpty()))
        }
    }

    private fun derivedLinks(
        repository: RomRepository, row: me.kanadex.geometryos.rom.RomRow?, archive: File?, root: File,
    ): (String, String) -> Boolean {
        val spec = row?.let(repository::specOf)
        val inputs = when {
            archive != null -> ZipFile(archive).use { zip ->
                val name = RomPackage.read(archive).inputs
                zip.getInputStream(zip.getEntry(name)).bufferedReader().use { JSONObject(it.readText()) }
            }
            else -> spec?.romRoot?.resolve(spec.runtimeManifest)?.takeIf(File::isFile)?.let { JSONObject(it.readText()) }
        }
        val aliases = mutableMapOf(
            "vm/rootfs/product" to "/system/product",
            "vm/rootfs/etc" to "/system/etc",
            "vm/rootfs/data/data" to "/data/user/0",
        )
        if (inputs?.optBoolean("apexAliases", true) == true) {
            aliases["vm/rootfs/apex/com.android.runtime"] = "/system/apex/com.android.runtime.debug"
            for (name in listOf("conscrypt", "media", "media.swcodec", "resolv", "tzdata")) {
                aliases["vm/rootfs/apex/com.android." + name] = "/system/apex/com.android." + name
            }
        }
        inputs?.optJSONArray("symlinks")?.let { links ->
            for (index in 0 until links.length()) {
                val item = links.getJSONObject(index)
                aliases["vm/" + item.getString("path")] = item.getString("target")
            }
        }
        inputs?.optJSONObject("rootfsAliases")?.let { declared ->
            for (path in declared.keys()) aliases["vm/rootfs/" + path] = declared.getString(path)
        }
        val nativeTargets = mutableMapOf<String, String>()
        val nativeNameByLogical = mutableMapOf<String, String>()
        inputs?.optJSONArray("files")?.let { files ->
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                val logical = item.getString("logical")
                val name = item.getString("name")
                nativeNameByLogical[logical] = name
                val library = item.optString("libraryName", if (logical.endsWith(".so")) logical else "")
                if (library.isNotEmpty()) {
                    nativeTargets["vm/lib/" + library] = name
                    nativeTargets["vm/rootfs/system/lib64/" + library] = name
                }
                item.optString("guestPath").takeIf { it.startsWith('/') }?.let {
                    nativeTargets["vm/rootfs" + it] = name
                }
            }
        }
        inputs?.optJSONArray("nativeLinks")?.let { links ->
            for (index in 0 until links.length()) {
                val item = links.getJSONObject(index)
                nativeNameByLogical[item.getString("logical")]?.let { name ->
                    nativeTargets["vm/" + item.getString("path")] = name
                }
            }
        }
        val libraryRoots = listOfNotNull(File(app.applicationInfo.nativeLibraryDir), spec?.romRoot?.resolve("payload/lib"))
            .map { it.toPath().toAbsolutePath().normalize() }
        return { path, target ->
            aliases[path] == target ||
                (path == "vm/rootfs/dev/socket" && target == root.resolve("vm/sock").path) ||
                isDeclaredNativeLink(target, nativeTargets[path], app.packageName, libraryRoots)
        }
    }
}

private val apkDirectoryToken = Regex("[A-Za-z0-9_-]+={0,2}")

// Package replacement changes the installation directory while stopped guests retain the old link.
internal fun isDeclaredNativeLink(
    target: String,
    declaredName: String?,
    packageName: String,
    libraryRoots: List<java.nio.file.Path>,
): Boolean {
    if (declaredName == null || !target.startsWith('/')) return false
    val parts = target.drop(1).split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." } || parts.last() != declaredName) return false
    val file = File(target).toPath()
    if (libraryRoots.any { file.parent == it }) return true
    if (parts.size != 7 || parts[0] != "data" || parts[1] != "app" || parts[4] != "lib" || parts[5] != "arm64") {
        return false
    }
    val installToken = parts[3].removePrefix(packageName + "-")
    val containerToken = parts[2].removePrefix("~~")
    return installToken != parts[3] && apkDirectoryToken.matches(installToken) &&
        containerToken != parts[2] && apkDirectoryToken.matches(containerToken)
}
