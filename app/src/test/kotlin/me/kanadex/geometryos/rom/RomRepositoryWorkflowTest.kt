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
    }
}
