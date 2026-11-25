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
    fun detailSectionsShowMetadataUsageAndRealDatesWithoutInventingMissingValues() {
        val rom = row(RomSource.Builtin).copy(
            usedByVms = 2,
            installedAt = 1_700_000_000_000L,
            lastUsedAt = 1_700_000_100_000L,
            usedByNames = listOf("工作机", "测试机"),
        )
        val facts = RomTexts.detailSections(rom).flatMap { it.facts }.associate { it.label to it.value }
        assertEquals("Android 15", facts[RomTexts.DETAIL_LABEL_NAME])
        assertEquals("1.0.0", facts[RomTexts.DETAIL_LABEL_VERSION])
        assertEquals("35", facts[RomTexts.DETAIL_LABEL_API])
        assertEquals("内置", facts[RomTexts.DETAIL_LABEL_SOURCE])
        assertEquals("653.3 MB", facts[RomTexts.DETAIL_LABEL_ARCHIVE])
        assertEquals("1.8 GB", facts[RomTexts.DETAIL_LABEL_EXTRACTED])
        assertEquals("2 台", facts[RomTexts.DETAIL_LABEL_VM_COUNT])
        assertEquals("工作机、测试机", facts[RomTexts.DETAIL_LABEL_VM_NAMES])
        assertTrue(facts.containsKey(RomTexts.DETAIL_LABEL_LAST_USED))
        assertFalse(facts.containsKey(RomTexts.DETAIL_LABEL_IMPORTED))

        val sparse = RomTexts.detailSections(rom.copy(
            version = "",
            androidVersion = "",
            apiLevel = 0,
            description = "",
            usedByNames = emptyList(),
            lastUsedAt = null,
        )).flatMap { it.facts }.associate { it.label to it.value }
        assertFalse(sparse.containsKey(RomTexts.DETAIL_LABEL_DESCRIPTION))
        assertFalse(sparse.containsKey(RomTexts.DETAIL_LABEL_VERSION))
        assertFalse(sparse.containsKey(RomTexts.DETAIL_LABEL_API))
        assertFalse(sparse.containsKey(RomTexts.DETAIL_LABEL_VM_NAMES))
        assertFalse(sparse.containsKey(RomTexts.DETAIL_LABEL_LAST_USED))
    }

    @Test
    fun legacyDetailsDoNotExposeTheInternalVersionOrAnUnpackedDirectory() {
        val facts = RomTexts.detailSections(row(RomSource.Legacy).copy(version = RomKeys.LEGACY_VERSION)).flatMap { it.facts }.associate { it.label to it.value }
        assertFalse(facts.containsKey(RomTexts.DETAIL_LABEL_VERSION))
        assertEquals(RomTexts.DETAIL_LEGACY, facts[RomTexts.DETAIL_LABEL_STATUS])
        assertEquals(RomTexts.DETAIL_LEGACY, facts[RomTexts.DETAIL_LABEL_ARCHIVE])
        assertEquals(RomTexts.DETAIL_LEGACY, facts[RomTexts.DETAIL_LABEL_EXTRACTED])
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
