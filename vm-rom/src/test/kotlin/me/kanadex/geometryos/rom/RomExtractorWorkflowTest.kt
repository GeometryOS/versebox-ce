// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RomExtractorWorkflowTest {
    @Test
    fun importsChecksExtractsAndReextractsInsideTemporaryDirectory() {
        val root = Files.createTempDirectory("versebox-rom-ci-").toFile()
        try {
            val sourcePayload = File(root, "source/payload/rootfs/system/build.prop")
            sourcePayload.parentFile!!.mkdirs()
            sourcePayload.writeText("ro.build.version.release=15\n", Charsets.UTF_8)
            val payloadRoot = sourcePayload.parentFile!!.parentFile!!.parentFile!!
            val digest = RomHash.payloadSha256(payloadRoot).first
            val propertiesText = listOf(
                "schema: versebox-rom/2",
                "id: android15",
                "romVersion: 1.0.0",
                "name: Android 15",
                "androidVersion: 15",
                "apiLevel: 35",
                "description: CI fixture",
                "inputs: versebox_inputs.json",
                "payloadRoot: payload/",
                "payloadBytes: " + sourcePayload.length(),
                "payloadSha256: " + digest,
                "libMode: rom",
                "packedBy: RomExtractorWorkflowTest",
                "packedAt: test",
            ).joinToString("\n")
            val inputsText = "{\"data\":[{\"name\":\"payload/rootfs/system/build.prop\",\"bytes\":" + sourcePayload.length() + "}]}"
            val archive = File(root, "android15-1.0.0.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(RomLayout.PROPERTIES))
                zip.write(propertiesText.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(RomLayout.INPUTS))
                zip.write(inputsText.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("payload/rootfs/system/build.prop"))
                zip.write(sourcePayload.readBytes())
                zip.closeEntry()
            }
            val properties = ZipFile(archive).use { zip ->
                RomPropertiesYaml.read(zip.getInputStream(zip.getEntry(RomLayout.PROPERTIES)).bufferedReader().use { it.readText() })
            }
            val entry = RomEntry(
                "payload/rootfs/system/build.prop",
                sourcePayload.length(),
                RomHash.fileSha256(sourcePayload),
            )
            val layout = RomLayout(File(root, "files/roms"))
            val extractor = RomExtractor(layout, { Long.MAX_VALUE }, {})
            val first = extractor.extract(archive, properties, listOf(entry), {})
            assertEquals(sourcePayload.readText(), File(first, entry.path).readText())
            assertEquals(digest, RomHash.payloadSha256(File(first, RomLayout.PAYLOAD)).first)

            File(first, entry.path).delete()
            assertFalse(File(first, entry.path).isFile)
            val second = extractor.extract(archive, properties, listOf(entry), {})
            assertEquals(sourcePayload.readText(), File(second, entry.path).readText())
            assertEquals(digest, RomHash.payloadSha256(File(second, RomLayout.PAYLOAD)).first)
        } finally {
            root.deleteRecursively()
        }
    }
}
