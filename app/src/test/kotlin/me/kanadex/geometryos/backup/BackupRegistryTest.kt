// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup

import io.a10vm.runtime.VmRegistry
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import io.a10vm.ui.verse.AppState
import io.a10vm.ui.vm.CreateVmAction
import io.a10vm.ui.vm.CreateVmViewModel
import me.kanadex.geometryos.shared.BackupDateGrouping
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/*
 * This fixture exercises the real registry and archive against Robolectric's private files.
 * It keeps the source instance registered throughout both restores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRegistryTest {
    @Test fun progressTextShowsByteProgressWhileWritingRom() {
        val state = BackupState(progress = BackupArchive.Progress(BackupArchive.Phase.WriteRom, 0, 1, 1024, 4096))
        val text = state.progressText
        assertTrue(text.startsWith("正在写入 ROM 原包"))
        assertTrue(text.contains("/"))
        assertTrue(text.contains("KB"))
    }

    @Test fun backupManagementGroupsArchivesAndConfirmsThroughCreationForm() {
        val context = RuntimeEnvironment.getApplication()
        val registry = VmRegistry.get(context)
        val rom = BackupRomSelection("android10", "1.0.1", "b".repeat(64), "Imported", "Android 10")
        val original = registry.create(rom.key, rom.payloadSha256, rom.source, rom.name)
        val root = registry.rootOf(original)
        val settings = BackupVmSettings(original.name, "480x800@240", rom)
        val client = BackupClient(context.packageName, "fixture", 1)
        val work = Files.createTempDirectory(context.filesDir.toPath(), "backup-list-").toFile()
        val library = BackupLibrary.get(context)
        val dated = work.resolve("dated.zip")
        val manifest = BackupArchive.write(dated, root, settings, client, null)
        library.publish(dated, manifest)
        val old = work.resolve("undated.zip")
        val seed = work.resolve("seed.zip")
        BackupArchive.write(seed, root, settings, client, null)
        java.util.zip.ZipFile(seed).use { input ->
            java.util.zip.ZipOutputStream(old.outputStream()).use { output ->
                for (entry in input.entries()) {
                    output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    val bytes = input.getInputStream(entry).use { it.readBytes() }
                    val data = if (entry.name == "backup.yaml") bytes.decodeToString().lineSequence()
                        .filterNot { it.startsWith("createdAtMillis:") }.joinToString("\n").encodeToByteArray() else bytes
                    output.write(data)
                    output.closeEntry()
                }
            }
        }
        val legacy = library.import(android.net.Uri.fromFile(old), {}, {})
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val appState = AppState(context)
            val backup = BackupViewModel(context, appState, scope)
            runBlocking {
                withTimeout(10_000) {
                    val list = backup.state.first { !it.isLoading }
                    assertEquals(listOf(BackupDateGrouping.Group.TODAY, BackupDateGrouping.Group.UNKNOWN), list.groups.map { it.first })
                    assertEquals("Android 10(未知/android10)", list.summaries[legacy])
                    assertTrue(checkNotNull(list.summaries[manifest.id]).endsWith("/android10)"))
                    val route = checkNotNull(backup.createRoute(legacy))
                    val form = CreateVmViewModel(appState, scope, backup, route)
                    form.state.first { it.backupSummary == original.name }
                    assertEquals(listOf(original), registry.list())
                    form.dispatch(CreateVmAction.SetName(original.name))
                    form.dispatch(CreateVmAction.Create)
                    val result = backup.state.first { it.createdVmId != null || it.error != null }
                    val restored = checkNotNull(registry.find(result.createdVmId)) { result.error.orEmpty() }
                    assertEquals(original.name + "(1)", restored.name)
                    assertEquals(rom.key, restored.romId)
                    assertEquals(settings.displayMode, restored.displayMode)
                    assertFalse(restored.root == original.root)
                    val rename = registry.rename(restored.id, original.name)
                    val renamed = rename as io.a10vm.runtime.RenameResult.Renamed
                    assertEquals(restored.name, renamed.device.name)
                    assertTrue(checkNotNull(renamed.correctionMessage).contains(restored.name))
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun restoresSettingsAndDataWithFreshIds() {
        val context = RuntimeEnvironment.getApplication()
        val registry = VmRegistry.get(context)
        val rom = BackupRomSelection("android10", "1.0.1", "b".repeat(64), "Imported", "Android 10")
        val original = registry.create(rom.key, rom.payloadSha256, rom.source)
        assertTrue(registry.setDisplayMode(original.id, "480x800@240"))
        val source = checkNotNull(registry.find(original.id))
        val root = registry.rootOf(source)
        val path = "vm/rootfs/data/local/backup-sentinel"
        root.resolve(path).apply { checkNotNull(parentFile).mkdirs(); writeText("guest-data") }
        val work = Files.createTempDirectory(context.filesDir.toPath(), "backup-fixture-").toFile()
        val zip = work.resolve("backup.zip")
        val settings = BackupVmSettings(source.name, source.displayMode, rom)
        BackupArchive.write(zip, root, settings, BackupClient(context.packageName, "fixture", 1), null)

        val conflict = work.resolve("conflict")
        BackupArchive.restoreTree(zip, conflict)
        val events = mutableListOf<String>()
        val corrected = registry.commitRestored(settings, conflict, events::add)
        assertEquals(source.name + "(1)", corrected.name)
        assertTrue(events.single().contains(corrected.name))
        assertEquals("guest-data", registry.rootOf(corrected).resolve(path).readText())

        val ids = mutableSetOf(source.id)
        repeat(2) { index ->
            val staging = work.resolve("restore-$index")
            val manifest = BackupArchive.restoreTree(zip, staging)
            val restored = registry.commitRestored(manifest.settings.copy(name = "恢复夹具 "+ (index + 1)), staging)
            val stored = checkNotNull(registry.find(restored.id))
            assertTrue(ids.add(stored.id))
            assertNotEquals(source.root, stored.root)
            assertEquals(restored.name, stored.name)
            assertEquals(source.displayMode, stored.displayMode)
            assertEquals(source.romId, stored.romId)
            assertEquals(source.romPayload, stored.romPayload)
            assertEquals(source.romSource, stored.romSource)
            assertEquals("guest-data", registry.rootOf(stored).resolve(path).readText())
        }
        assertEquals(source, registry.find(source.id))
        assertEquals("guest-data", root.resolve(path).readText())
    }
}
