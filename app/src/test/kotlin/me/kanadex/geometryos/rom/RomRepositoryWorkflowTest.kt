// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.a10vm.records.RecordArchive
import io.a10vm.records.RecordCollector
import io.a10vm.records.RecordFilter
import io.a10vm.records.RecordKind
import io.a10vm.records.RecordStatus
import io.a10vm.records.RecordStore
import io.a10vm.runtime.VmRegistry
import io.a10vm.runtime.VmRomResolutionCache
import io.a10vm.runtime.romBinding
import me.kanadex.geometryos.backup.BackupArchive
import me.kanadex.geometryos.backup.BackupClient
import me.kanadex.geometryos.backup.BackupLibrary
import me.kanadex.geometryos.backup.BackupRestore
import me.kanadex.geometryos.backup.BackupRomSelection
import me.kanadex.geometryos.backup.BackupVmSettings
import me.kanadex.geometryos.boot.BootGateSequence

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RomRepositoryWorkflowTest {
    @Test
    fun importsListsRepairsAndReextractsUsingIsolatedData() {
        val context = RuntimeEnvironment.getApplication() as Context
        val work = Files.createTempDirectory(context.cacheDir.toPath(), "rom-workflow-").toFile()
        val payloadFile = File(work, "payload/rootfs/system/build.prop")
        payloadFile.parentFile!!.mkdirs()
        payloadFile.writeText("ro.build.version.release=15\n", Charsets.UTF_8)
        val payloadRoot = File(work, "payload")
        val payloadSha = RomHash.payloadSha256(payloadRoot).first
        val inputsText = JSONObject()
            .put("guestSdk", 35)
            .put("data", JSONArray().put(JSONObject()
                .put("name", "payload/rootfs/system/build.prop")
                .put("bytes", payloadFile.length())
                .put("sha256", RomHash.fileSha256(payloadFile))))
            .put("files", JSONArray())
            .toString()
        val propertiesText = listOf(
            "schema: versebox-rom/2",
            "id: android15",
            "romVersion: 9.8.7",
            "name: Android 15 Fixture",
            "androidVersion: 15",
            "apiLevel: 35",
            "description: Repository fixture",
            "inputs: versebox_inputs.json",
            "payloadRoot: payload/",
            "payloadBytes: " + payloadFile.length(),
            "payloadSha256: " + payloadSha,
            "libMode: rom",
            "packedBy: app-unit-test",
            "packedAt: test",
        ).joinToString("\n")
        val sourceZip = File(work, "android15-9.8.7.zip")
        ZipOutputStream(sourceZip.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(RomLayout.PROPERTIES))
            zip.write(propertiesText.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(RomLayout.INPUTS))
            zip.write(inputsText.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("payload/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("payload/rootfs/system/build.prop"))
            zip.write(payloadFile.readBytes())
            zip.closeEntry()
        }
        val uri = Uri.fromFile(sourceZip)
        org.robolectric.shadows.ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        val repository = RomRepository.get(context)
        val imported = repository.importFromUri(uri) {}
        assertFalse(imported.duplicate)
        runBlocking { RecordCollector.awaitOperationArchives("rom") }
        val backupRom = File(work, "backup-rom.zip")
        repository.openBackupArchive(RomRef(imported.row.key, imported.row.source, imported.row.payloadSha256)).use { input ->
            backupRom.outputStream().use(input::copyTo)
        }
        assertEquals(sourceZip.length(), backupRom.length())
        assertEquals(payloadSha, RomPackage.read(backupRom).payloadSha256)

        val device = VmRegistry.get(context).create(imported.row.key, payloadSha, RomSource.Imported.name)
        val rows = repository.rows()
        val row = rows.single { it.rowId == imported.row.rowId }
        assertEquals(sourceZip.length(), row.archiveBytes)
        assertTrue(row.extractedBytes > 0L)
        assertEquals(1, row.usedByVms)
        assertEquals(
            RomTexts.bytes(row.archiveBytes) + " / 已解包 " + RomTexts.bytes(row.extractedBytes) + " / 1 台虚拟机使用",
            RomTexts.meta(row),
        )
        assertTrue(device.id.isNotBlank())
        assertEquals(1, rows.sumOf { it.usedByVms })

        val layout = RomStorage.layout(context)
        val romDir = layout.dirFor(imported.row.key, payloadSha)
        val missing = File(romDir, "payload/rootfs/system/build.prop")
        missing.delete()
        repository.ensureUsable(imported.row.key, payloadSha, {})
        assertTrue(missing.isFile)
        runBlocking { RecordCollector.awaitOperationArchives("rom") }
        romDir.deleteRecursively()
        repository.ensureUsable(imported.row.key, payloadSha, {})
        assertTrue(missing.isFile)
        runBlocking { RecordCollector.awaitOperationArchives("rom") }

        RecordCollector.recordMeta(
            context = context,
            vmId = "rom",
            vmName = "ROM 管理",
            romId = imported.row.key,
            romName = imported.row.displayName,
            apiLevel = imported.row.apiLevel,
            kind = RecordKind.ROM_CHECK,
            status = RecordStatus.FAILED,
            detail = "CI异常堆栈夹具",
            failure = IllegalStateException("fixture failure"),
        )
        runBlocking { RecordCollector.awaitOperationArchives("rom") }
        val records = RecordStore.get(context).query(RecordFilter(vmId = "rom"))
        val importedRecord = records.single { it.kind == RecordKind.ROM_IMPORT }
        val repair = records.single { it.kind == RecordKind.ROM_REPAIR }
        val reextract = records.single { it.kind == RecordKind.ROM_REEXTRACT }
        assertTrue(repair.startedAt <= reextract.startedAt)
        assertNotNull(importedRecord.logDir)
        assertTrue(RecordArchive.entries(context, repair).any { it.path == "operation.log" })
        val failed = records.single { it.kind == RecordKind.ROM_CHECK }
        assertTrue(RecordArchive.readText(context, failed, "operation.log").contains("stackTrace"))

        val second = repository.importFromUri(Uri.fromFile(createRomFixture(work, "different same-version payload"))) {}
        assertEquals(imported.row.key, second.row.key)
        assertTrue(imported.row.payloadSha256 != second.row.payloadSha256)
        assertTrue(VmRegistry.get(context).setRomId(
            device.id, second.row.key, second.row.payloadSha256, second.row.source.name,
        ))
        repository.delete(second.row.rowId)

        val selected = RomRef(second.row.key, second.row.source, second.row.payloadSha256)
        val decision = RomVersionPolicy.decision(repository.versionEntries()[second.row.id].orEmpty(), selected)
        assertTrue(decision is RomVersionDecision.CurrentMissing)
        assertTrue(repository.resolve(second.row.key, second.row.payloadSha256) == null)
        assertTrue(repository.resolve(imported.row.key, payloadSha) != null)
        assertFalse(repository.hasArchive(second.row.key, second.row.payloadSha256))

        val registry = VmRegistry.get(context)
        val cache = VmRomResolutionCache()
        val revision = repository.revision.value
        fun resolve() = cache.resolve(checkNotNull(registry.find(device.id)).romBinding(), repository.revision.value) {
            repository.resolve(it.key, it.payload)
        }
        assertEquals(null, resolve())
        assertTrue(registry.setRomId(device.id, imported.row.key, payloadSha, imported.row.source.name))
        assertEquals(revision, repository.revision.value)
        assertEquals(romDir.canonicalFile, resolve()?.romRoot?.canonicalFile)
    }

    @Test
    fun restoreRoutesEmbeddedRomThroughImportEvenWhenAlreadyInstalled() {
        val context = RuntimeEnvironment.getApplication()
        val work = Files.createTempDirectory(context.cacheDir.toPath(), "backup-rom-workflow-").toFile()
        val archive = createRomFixture(work, "guest boot fixture")
        val properties = RomPackage.read(archive)
        val repository = RomRepository.get(context)
        val registry = VmRegistry.get(context)
        val library = BackupLibrary.get(context)
        val restore = BackupRestore(context)
        org.robolectric.shadows.ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        val original = registry.create(properties.key, properties.payloadSha256, RomSource.Imported.name)
        val root = registry.rootOf(original)
        val sentinel = "vm/rootfs/data/local/backup-sentinel"
        root.resolve(sentinel).apply { checkNotNull(parentFile).mkdirs(); writeText("original data") }
        val settings = BackupVmSettings(original.name, "480x800@240", BackupRomSelection(
            properties.id, properties.romVersion, properties.payloadSha256, RomSource.Imported.name, properties.name,
        ))
        val staged = work.resolve("backup.zip")
        val manifest = BackupArchive.write(staged, root, settings, BackupClient(context.packageName, "test", 1), archive)
        val id = library.publish(staged, manifest)
        assertEquals(id, library.rows().single().id)
        assertTrue(repository.rowFor(RomRef(properties.key, RomSource.Imported, properties.payloadSha256)) == null)
        val vmIds = mutableSetOf(original.id)
        repeat(2) { index ->
            val prepared = restore.prepare(id, {}, {})
            val outcome = repository.importFromUri(checkNotNull(prepared.romUri)) {}
            assertEquals(index == 1, outcome.duplicate)
            val instance = restore.commit(prepared, "备份恢复 " + (index + 1), outcome)
            assertTrue(vmIds.add(instance.id))
            assertEquals(settings.displayMode, instance.displayMode)
            assertEquals(properties.payloadSha256, instance.romPayload)
            assertEquals("original data", registry.rootOf(instance).resolve(sentinel).readText())
            if (index == 1) runBlocking {
                val binding = RomRef(instance.romId, outcome.row.source, instance.romPayload)
                val extracted = repository.specOf(outcome.row).romRoot!!
                assertTrue(extracted.resolve("payload/rootfs/system/build.prop").delete())
                val sequence = BootGateSequence(context.resources)
                suspend fun gate() = sequence.run(
                    binding = { binding },
                    entries = { repository.versionEntries()[properties.id].orEmpty() },
                    inspect = { ref -> repository.rowFor(ref)?.let { row ->
                        BootGateSequence.Inspection(repository.gate(row), row.key, row.rowId)
                    } },
                    ask = { false },
                )
                assertEquals(BootGateSequence.Result.Repair(outcome.row.key, outcome.row.rowId), gate())
                repository.ensureUsable(outcome.row.key, outcome.row.payloadSha256, {})
                assertEquals(BootGateSequence.Result.Start, gate())
                assertEquals("original data", registry.rootOf(instance).resolve(sentinel).readText())
            }
        }
        assertEquals("original data", root.resolve(sentinel).readText())
        runBlocking { RecordCollector.awaitOperationArchives("rom") }
    }

    private fun createRomFixture(work: File, payloadText: String): File {
        val payload = File(work, "payload-" + payloadText.hashCode() + "/rootfs/system/build.prop")
        payload.parentFile!!.mkdirs()
        payload.writeText(payloadText, Charsets.UTF_8)
        val payloadRoot = payload.parentFile!!.parentFile!!.parentFile!!
        val sha = RomHash.payloadSha256(payloadRoot).first
        val inputs = JSONObject()
            .put("guestSdk", 35)
            .put("data", JSONArray().put(JSONObject()
                .put("name", "payload/rootfs/system/build.prop")
                .put("bytes", payload.length())
                .put("sha256", RomHash.fileSha256(payload))))
            .put("files", JSONArray())
            .toString()
        val properties = listOf(
            "schema: versebox-rom/2", "id: android15", "romVersion: 9.8.7",
            "name: Android 15 Fixture", "androidVersion: 15", "apiLevel: 35",
            "description: Repository fixture", "inputs: versebox_inputs.json", "payloadRoot: payload/",
            "payloadBytes: " + payload.length(), "payloadSha256: " + sha, "libMode: rom",
            "packedBy: app-unit-test", "packedAt: test",
        ).joinToString("\n")
        val zipFile = File(work, "fixture-" + payloadText.hashCode() + ".zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            val entries = linkedMapOf(
                RomLayout.PROPERTIES to properties.toByteArray(Charsets.UTF_8),
                RomLayout.INPUTS to inputs.toByteArray(Charsets.UTF_8),
                "payload/" to ByteArray(0),
                "payload/rootfs/system/build.prop" to payload.readBytes(),
            )
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return zipFile
    }
}
