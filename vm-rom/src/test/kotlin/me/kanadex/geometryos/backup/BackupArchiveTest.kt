// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/*
 * The archive fixture uses an isolated VM tree and a byte-for-byte ROM stand-in.
 * ROM validation and installation belong to the Android workflow fixture.
 */
class BackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private val settings = BackupVmSettings(
        "测试 # \"设备", "480x800@240",
        BackupRomSelection("android10", "1.0.1", "a".repeat(64), "Imported", "Android 10"),
    )
    private val client = BackupClient("test.versebox", "fixture", 42)
    private val sentinel = "vm/rootfs/data/local/sentinel.txt"

    @Test fun restoresSettingsAndSentinelWithoutReplacingSource() {
        val source = vmTree()
        val rom = temp.newFile("original.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val archive = File(temp.root, "backup.zip")
        val seen = mutableListOf<BackupArchive.Progress>()
        val manifest = BackupArchive.write(archive, source, settings, client, rom, progress = { seen += it })
        val romWrites = seen.filter { it.phase == BackupArchive.Phase.WriteRom }
        assertTrue(romWrites.isNotEmpty())
        assertEquals(rom.length(), romWrites.last().bytes)
        assertEquals(rom.length(), romWrites.last().totalBytes)
        assertEquals(settings, manifest.settings)
        assertEquals(client, manifest.client)
        assertEquals(BackupArchive.readManifest(archive), manifest)
        assertEquals(3L, manifest.romArchive?.bytes)
        val extractedRom = File(temp.root, "import.zip")
        BackupArchive.extractRom(archive, extractedRom)
        assertArrayEquals(rom.readBytes(), extractedRom.readBytes())
        val measured = BackupArchive.readEmbeddedRomMetadata(archive) { _, bytes, input -> bytes to input.readBytes().size }
        assertEquals(rom.length(), measured.first)
        assertEquals(rom.length().toInt(), measured.second)
        repeat(2) { index ->
            val root = File(temp.root, "restore-$index")
            assertEquals(manifest, BackupArchive.restoreTree(archive, root))
            assertEquals("keep guest data", root.resolve(sentinel).readText())
            assertTrue(root.resolve("native-window").isDirectory)
            assertNotEquals(source.canonicalPath, root.canonicalPath)
        }
        assertEquals("keep guest data", source.resolve(sentinel).readText())
    }

    @Test fun verifiesAndRestoresLegacyArchivesWithoutCreationTime() {
        val modern = File(temp.root, "dated.zip")
        val written = BackupArchive.write(modern, vmTree(), settings, client, null)
        assertTrue(written.createdAtMillis > 0L)
        assertEquals(written.createdAtMillis, BackupArchive.readManifest(modern).createdAtMillis)
        val legacy = File(temp.root, "undated.zip")
        rewrite(modern, legacy, "backup.yaml") { bytes ->
            bytes.toString(Charsets.UTF_8).lineSequence()
                .filterNot { it.startsWith("createdAtMillis:") }
                .joinToString("\n").toByteArray(Charsets.UTF_8)
        }
        val manifest = BackupArchive.verify(legacy)
        assertEquals(0L, manifest.createdAtMillis)
        val restored = File(temp.root, "legacy-restored")
        assertEquals(manifest, BackupArchive.restoreTree(legacy, restored))
        assertEquals("keep guest data", restored.resolve(sentinel).readText())
    }

    @Test fun omittingRomPreservesExactSelectionAndDoesNotCreateAnImportFile() {
        val archive = File(temp.root, "without-rom.zip")
        val manifest = BackupArchive.write(archive, vmTree(), settings, client, null)
        assertNull(manifest.romArchive)
        assertEquals(settings.rom, BackupArchive.verify(archive).settings.rom)
        val output = File(temp.root, "must-not-exist.zip")
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.extractRom(archive, output) }
        assertFalse(output.exists())
    }

    @Test fun digestFailureRemovesOnlyTheNewRestoreTree() {
        val source = vmTree()
        val original = File(temp.root, "original.zip")
        BackupArchive.write(original, source, settings, client, null)
        val damaged = File(temp.root, "damaged.zip")
        rewrite(original, damaged, "instance/tree/$sentinel") { it.clone().apply { this[0] = 0 } }
        val dest = File(temp.root, "incomplete")
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.restoreTree(damaged, dest) }
        assertFalse(dest.exists())
        assertEquals("keep guest data", source.resolve(sentinel).readText())
    }

    @Test fun existingDestinationIsNeverModified() {
        val source = vmTree()
        val archive = File(temp.root, "backup.zip")
        BackupArchive.write(archive, source, settings, client, null)
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            BackupArchive.restoreTree(archive, source)
        }
        assertEquals("keep guest data", source.resolve(sentinel).readText())
    }

    @Test fun restoresRelativeLinksWithoutTraversingThemDuringCapture() {
        val source = vmTree()
        if (!Files.getFileStore(source.toPath()).supportsFileAttributeView("posix")) return
        val alias = source.resolve("data-link").toPath()
        Files.createSymbolicLink(alias, alias.parent.relativize(source.resolve(sentinel).toPath()))
        val hard = source.resolve("data-hard-link").toPath()
        Files.createLink(hard, source.resolve(sentinel).toPath())
        val archive = File(temp.root, "links.zip")
        BackupArchive.write(archive, source, settings, client, null)
        val restored = File(temp.root, "links-restored")
        BackupArchive.restoreTree(archive, restored)
        assertTrue(Files.isSymbolicLink(restored.resolve("data-link").toPath()))
        assertEquals("keep guest data", restored.resolve("data-link").readText())
        assertTrue(Files.isSameFile(restored.resolve("data-hard-link").toPath(), restored.resolve(sentinel).toPath()))
    }

    @Test fun omitsRuntimeEndpointsAndKeepsLinksThatLeaveTheInstance() {
        val source = vmTree()
        if (!Files.getFileStore(source.toPath()).supportsFileAttributeView("posix")) return
        val socket = source.resolve("vm/sock/zygote").toPath()
        Files.createDirectories(socket.parent)
        val channel = try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket)) }
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.io.IOException) {
            return
        }
        try {
            val guestAbsolute = source.resolve("vm/rootfs/sdcard").toPath()
            Files.createDirectories(guestAbsolute.parent)
            Files.createSymbolicLink(guestAbsolute, Paths.get("/storage/self/primary"))
            val archive = File(temp.root, "endpoints.zip")
            var omitted: BackupArchive.Omissions? = null
            BackupArchive.write(archive, source, settings, client, null, onOmitted = { omitted = it })
            assertEquals(BackupArchive.Omissions(1, 0, 1), omitted)
            omitted = null
            BackupArchive.verify(archive, onOmitted = { omitted = it })
            assertEquals(1, checkNotNull(omitted).runtimeEntries)
            val restored = File(temp.root, "endpoints-restored")
            BackupArchive.restoreTree(archive, restored)
            assertFalse(Files.exists(restored.resolve("vm/sock/zygote").toPath(), LinkOption.NOFOLLOW_LINKS))
            val link = restored.resolve("vm/rootfs/sdcard").toPath()
            assertTrue(Files.isSymbolicLink(link))
            assertEquals("/storage/self/primary", Files.readSymbolicLink(link).toString())
        } finally {
            channel.close()
        }
    }

    private fun vmTree(): File = temp.newFolder().apply {
        resolve(sentinel).apply { parentFile.mkdirs(); writeText("keep guest data") }
        assertTrue(resolve("native-window").mkdir())
    }

    private fun rewrite(input: File, output: File, name: String, transform: (ByteArray) -> ByteArray) {
        ZipFile(input).use { source ->
            ZipOutputStream(output.outputStream()).use { target ->
                for (entry in source.entries()) {
                    target.putNextEntry(ZipEntry(entry.name))
                    val bytes = source.getInputStream(entry).use { it.readBytes() }
                    target.write(if (entry.name == name) transform(bytes) else bytes)
                    target.closeEntry()
                }
            }
        }
    }
}
