// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup

import io.a10vm.runtime.VmRegistry
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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
        assertThrows(IllegalArgumentException::class.java) { registry.commitRestored(settings, conflict) }
        assertEquals("guest-data", conflict.resolve(path).readText())

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
