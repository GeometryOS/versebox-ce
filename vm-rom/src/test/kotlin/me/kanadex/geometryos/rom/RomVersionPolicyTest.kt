// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本管理的唯一判据（`docs/rom-package/14-版本管理/02-版本模型与判据.md`）。
 *
 * 这些用例覆盖的正是页面上按钮「亮/灰」的每一种组合：
 * 同 id 多版本、内置比当前新、当前版本被删（强制更新）、降级、
 * **同版本不同内容**、**同版本同内容不同来源**、跨系列。
 */
class RomVersionPolicyTest {

    private fun entry(
        id: String = "android15",
        version: String = "1.0.0",
        available: Boolean = true,
        source: RomSource = RomSource.Builtin,
        payload: String = "sha-" + version,
    ) = RomVersionEntry(
        key = RomKeys.key(id, version),
        id = id,
        version = version,
        displayName = if (id == "android15") "Android 15" else "Android 10",
        source = source,
        apiLevel = if (id == "android15") 35 else 29,
        available = available,
        payloadSha256 = payload,
    )

    @Test
    fun seriesSortedByVersionDescending() {
        val entries = listOf(entry(version = "1.0.0"), entry(version = "1.0.10"), entry(version = "1.2.0"))
        val series = RomVersionPolicy.series(entries, "android15")
        assertEquals(listOf("1.2.0", "1.0.10", "1.0.0"), series.map { it.version })
    }

    @Test
    fun latestAvailableSkipsNotExtracted() {
        val entries = listOf(
            entry(version = "1.0.0", available = true),
            entry(version = "1.1.0", available = false),
        )
        assertEquals("1.0.0", RomVersionPolicy.latestAvailable(entries, "android15")!!.version)
        // 「最新」仍然按版本号算，只是不可用——页面据此先解包再切。
        assertEquals("1.1.0", RomVersionPolicy.latest(entries, "android15")!!.version)
    }

    @Test
    fun upToDateWhenOnlyCurrentExists() {
        val entries = listOf(entry(version = "1.0.0"))
        assertEquals(
            RomVersionDecision.UpToDate,
            RomVersionPolicy.decision(entries, "android15@1.0.0"),
        )
    }

    @Test
    fun updateAvailableWhenBuiltinIsNewer() {
        val entries = listOf(
            entry(version = "1.0.0", source = RomSource.Imported),
            entry(version = "1.0.1", source = RomSource.Builtin),
        )
        val decision = RomVersionPolicy.decision(entries, "android15@1.0.0")
        assertTrue(decision is RomVersionDecision.UpdateAvailable)
        decision as RomVersionDecision.UpdateAvailable
        assertEquals("1.0.1", decision.latest.version)
    }

    @Test
    fun notExtractedButPackagePresentIsNotForced() {
        // 内置包首次使用：还没解包，但包就在 APK 里。这时**不许**弹"当前版本不在了"，
        // 否则新建的虚拟机第一次启动会被强制跳去版本管理（2026-11-12 自查发现）。
        val entries = listOf(entry(version = "1.0.0", available = false).copy(extractable = true))
        assertEquals(
            RomVersionDecision.UpToDate,
            RomVersionPolicy.decision(entries, "android15@1.0.0"),
        )
    }

    @Test
    fun placeholderWithoutArchiveIsForced() {
        val entries = listOf(
            entry(version = "1.0.0", available = false).copy(extractable = false, placeholder = true),
            entry(version = "1.0.1"),
        )
        val decision = RomVersionPolicy.decision(entries, "android15@1.0.0")
        assertTrue(decision is RomVersionDecision.CurrentMissing)
        assertEquals("1.0.1", (decision as RomVersionDecision.CurrentMissing).fallback!!.version)
    }

    @Test
    fun forcedUpdateWhenSelectedVersionIsGone() {
        val entries = listOf(entry(version = "1.0.1"))
        val decision = RomVersionPolicy.decision(entries, "android15@1.0.0")
        assertTrue(decision is RomVersionDecision.CurrentMissing)
        decision as RomVersionDecision.CurrentMissing
        assertEquals("1.0.1", decision.fallback!!.version)
    }

    @Test
    fun unavailableWhenNothingForThatSeries() {
        val entries = listOf(entry(id = "android10", version = "1.0.0"))
        assertTrue(RomVersionPolicy.decision(entries, "android15@1.0.0") is RomVersionDecision.Unavailable)
    }

    @Test
    fun sameVersionDifferentPayloadIsNotAnUpdate() {
        // 2026-11-12 维护者点名：**同版本号不是更新**。
        // 内置 1.0.0 与本地导入 1.0.0（内容不同）是"同一版本的两个包"，在版本页里可选，
        // 但启动时提示"有新版本可用"是假信息——版本号根本没变。
        val entries = listOf(
            entry(version = "1.0.0", payload = "aaa"),
            entry(version = "1.0.0", source = RomSource.Imported, payload = "bbb"),
        )
        val current = RomRef("android15@1.0.0", RomSource.Builtin, "aaa")
        assertEquals(RomVersionDecision.UpToDate, RomVersionPolicy.decision(entries, current))
    }

    @Test
    fun sameVersionOtherSourceIsNotAnUpdate() {
        // 同版本、同内容、来源不同：也不是更新（内置那份永远在列表里，不能每次都提示一遍）。
        val entries = listOf(
            entry(version = "1.0.0", source = RomSource.Builtin, payload = "same"),
            entry(version = "1.0.0", source = RomSource.Imported, payload = "same"),
        )
        val current = RomRef("android15@1.0.0", RomSource.Imported, "same")
        assertEquals(RomVersionDecision.UpToDate, RomVersionPolicy.decision(entries, current))
    }

    @Test
    fun strictlyNewerVersionIsTheOnlyUpdatePrompt() {
        val entries = listOf(
            entry(version = "1.0.0", payload = "aaa"),
            entry(version = "1.0.1", payload = "bbb"),
        )
        val decision = RomVersionPolicy.decision(entries, RomRef("android15@1.0.0", RomSource.Builtin, "aaa"))
        assertTrue(decision is RomVersionDecision.UpdateAvailable)
        assertEquals("1.0.1", (decision as RomVersionDecision.UpdateAvailable).latest.version)
    }

    @Test
    fun matchIsByPayloadNotByKey() {
        val builtin = entry(version = "1.0.0", source = RomSource.Builtin, payload = "aaa")
        val imported = entry(version = "1.0.0", source = RomSource.Imported, payload = "bbb")
        val entries = listOf(builtin, imported)
        assertEquals(builtin, RomVersionPolicy.match(entries, RomRef(builtin.key, RomSource.Builtin, "aaa")))
        assertEquals(imported, RomVersionPolicy.match(entries, RomRef(imported.key, RomSource.Imported, "bbb")))
        // 指纹对不上 = 这一份内容真的不在了，不许拿同版本的另一份顶替。
        assertNull(RomVersionPolicy.match(entries, RomRef(builtin.key, RomSource.Builtin, "ccc")))
        // 旧实例只记了 romKey：允许按 key 命中（来源不限）。
        assertEquals(builtin, RomVersionPolicy.match(entries, RomRef(builtin.key)))
    }

    @Test
    fun missingPayloadIsForcedToReselect() {
        val entries = listOf(
            entry(version = "1.0.0", source = RomSource.Builtin, payload = "aaa"),
            entry(version = "1.0.0", source = RomSource.Imported, payload = "bbb"),
        )
        val decision = RomVersionPolicy.decision(entries, RomRef("android15@1.0.0", RomSource.Imported, "zzz"))
        assertTrue(decision is RomVersionDecision.CurrentMissing)
    }

    @Test
    fun switchKindClassifies() {
        val one = entry(version = "1.0.0")
        val newer = entry(version = "1.1.0")
        val older = entry(version = "0.9.0")
        assertEquals(RomSwitch.Same, RomVersionPolicy.switchKind(one, one))
        assertEquals(RomSwitch.Upgrade, RomVersionPolicy.switchKind(one, newer))
        assertEquals(RomSwitch.Downgrade, RomVersionPolicy.switchKind(newer, older))
        assertEquals(
            RomSwitch.SameVersionDifferentPayload,
            RomVersionPolicy.switchKind(
                entry(version = "1.0.0", payload = "aaa"),
                entry(version = "1.0.0", payload = "bbb"),
            ),
        )
        val a10 = entry(id = "android10", version = "1.0.0")
        assertEquals(RomSwitch.DifferentSeries, RomVersionPolicy.switchKind(one, a10))
    }

    @Test
    fun switchKindSeesSourceEvenWhenPayloadIsIdentical() {
        // 这一条就是真机里「选中外置同版本，再点内置同版本只提示切换成功」的那个 bug 的判据。
        val builtin = entry(version = "1.0.0", source = RomSource.Builtin, payload = "same")
        val imported = entry(version = "1.0.0", source = RomSource.Imported, payload = "same")
        assertFalse(RomVersionPolicy.sameIdentity(builtin, imported))
        assertEquals(RomSwitch.SameVersionDifferentSource, RomVersionPolicy.switchKind(imported, builtin))
        assertEquals(RomSwitch.SameVersionDifferentSource, RomVersionPolicy.switchKind(builtin, imported))
        assertEquals(RomSwitch.Same, RomVersionPolicy.switchKind(builtin, builtin))
        // 没有当前绑定时是"选一个"，不是"切换"。
        assertEquals(RomSwitch.Upgrade, RomVersionPolicy.switchKind(null, imported))
    }

    @Test
    fun onlySameSeriesCanSwitch() {
        assertTrue(RomVersionPolicy.canSwitch("android15@1.0.0", "android15@1.1.0"))
        assertFalse(RomVersionPolicy.canSwitch("android15@1.0.0", "android10@1.0.0"))
        assertFalse(RomVersionPolicy.canSwitch("", "android10@1.0.0"))
    }

    @Test
    fun warningsOnlyForRealChanges() {
        assertFalse(RomVersionPolicy.requiresWarning(RomSwitch.Upgrade))
        assertFalse(RomVersionPolicy.requiresWarning(RomSwitch.Same))
        assertTrue(RomVersionPolicy.requiresWarning(RomSwitch.Downgrade))
        assertTrue(RomVersionPolicy.requiresWarning(RomSwitch.SameVersionDifferentPayload))
        // 同版本、同内容、来源不同：**仍然要确认**——列表里它们是两个独立的 ROM 版本
        // （2026-11-12 维护者点名），切换改的是"这台实例绑哪一行"。
        assertTrue(RomVersionPolicy.requiresWarning(RomSwitch.SameVersionDifferentSource))
        assertFalse(RomVersionPolicy.requiresWarning(RomSwitch.DifferentSeries))
    }

    @Test
    fun nameWithVersionFormat() {
        assertEquals("Android 15 V1.0.0", RomTexts.nameWithVersion("Android 15", "1.0.0"))
        assertEquals("Android 15", RomTexts.nameWithVersion("Android 15", RomKeys.LEGACY_VERSION))
    }
}
