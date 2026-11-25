// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomTextsTest {
    private fun row(source: RomSource = RomSource.Imported, state: RomState = RomState.Ready) = RomRow(
        key = "android15@1.0.0",
        id = "android15",
        version = "1.0.0",
        displayName = "Android 15",
        description = "API 35",
        androidVersion = "15",
        apiLevel = 35,
        source = source,
        state = state,
        payloadBytes = 1_800_000_000L,
        archiveBytes = 685_034_701L,
        extractedBytes = 1_932_735_283L,
        usedByVms = 3,
        payloadSha256 = "a".repeat(64),
        hasArchive = true,
    )

    @Test
    fun metadataShowsArchiveExtractedSizeAndUseCountOnce() {
        val rom = row()
        val meta = RomTexts.meta(rom)
        assertEquals("653.3 MB / 已解包 1.8 GB / 3 台虚拟机使用", meta)
        assertFalse(meta.contains(rom.version))
        assertFalse(meta.contains("·"))
        assertTrue(RomTexts.titleWithVersion(rom).endsWith("V1.0.0"))
    }

    @Test
    fun metadataShowsUnpackedStateAndZeroUseCount() {
        val meta = RomTexts.meta(row(state = RomState.Pending).copy(usedByVms = 0))
        assertTrue(meta.contains("尚未解包"))
        assertTrue(meta.endsWith("0 台虚拟机使用"))
    }

    @Test
    fun bothSourcesExposeTheSameRepairActionsFromTheRowModel() {
        val builtin = RomTexts.item(row(RomSource.Builtin))
        val imported = RomTexts.item(row(RomSource.Imported))
        assertTrue(builtin.canCheck && builtin.canRepair && builtin.canClearCache)
        assertFalse(builtin.canDelete)
        assertTrue(imported.canCheck && imported.canRepair && imported.canDelete)
        assertFalse(imported.canClearCache)
    }
}
