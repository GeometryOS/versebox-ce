// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「一台实例只归属一行」的判据（`docs/rom-package/14-版本管理/05-一行一个版本.md` §3）。
 *
 * 这组用例守的是维护者点名的那条现象：内置与本地同版本共存时，
 * 一台实例**不能**同时算进两行（真机："两边都是 1 台"）。
 */
class RomUsageTest {

    private fun row(
        source: RomSource,
        version: String = "1.1.0",
        payload: String,
    ) = RomRow(
        key = RomKeys.key("android15", version),
        id = "android15",
        version = version,
        displayName = "Android 15",
        description = "",
        androidVersion = "15",
        apiLevel = 35,
        source = source,
        state = RomState.Ready,
        payloadSha256 = payload,
    )

    private val builtin = row(RomSource.Builtin, payload = "aaaa")
    private val imported = row(RomSource.Imported, payload = "aaaa")

    @Test
    fun sameVersionTwoRowsAreTwoOwners() {
        // 绑在内置那一行：只有内置行命中；绑在本地那一行：只有本地行命中。
        assertEquals(builtin, RomUsage.owner(listOf(builtin, imported), builtin.key, "aaaa", "Builtin"))
        assertEquals(imported, RomUsage.owner(listOf(builtin, imported), imported.key, "aaaa", "Imported"))
    }

    @Test
    fun identityWinsOverRowOrder() {
        // 即使本地行排在前面，显式记了 Builtin 的实例也只能归属内置行。
        assertEquals(builtin, RomUsage.owner(listOf(imported, builtin), builtin.key, "aaaa", "Builtin"))
    }

    @Test
    fun legacyInstanceFallsBackToFirstRowOnly() {
        // 旧实例（v2 以前只记 rom_id）：归属第一个匹配行，且只有一行。
        assertEquals(builtin, RomUsage.owner(listOf(builtin, imported), builtin.key, "", ""))
        assertNull(RomUsage.owner(listOf(builtin, imported), "", "", ""))
    }

    @Test
    fun differentPayloadIsAnotherOwner() {
        val other = row(RomSource.Imported, payload = "bbbb")
        val rows = listOf(builtin, imported, other)
        assertEquals(other, RomUsage.owner(rows, other.key, "bbbb", "Imported"))
    }

    @Test
    fun legacyFormatBelongsToItsOwnRow() {
        val legacy = RomRow(
            key = "android9",
            id = "android9",
            version = RomKeys.LEGACY_VERSION,
            displayName = "Android 9",
            description = "",
            androidVersion = "9",
            apiLevel = 28,
            source = RomSource.Legacy,
            state = RomState.Ready,
        )
        assertEquals(legacy, RomUsage.owner(listOf(legacy, builtin), "android9", "", ""))
    }
}
