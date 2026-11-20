// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表分组的唯一判据（[RomGrouping]）。
 *
 * 守的是三件用户直接看得见的事（红线见 `docs/rom-release/01-入库配置/04-ROM身份与分组红线.md` R5）：
 *  1. 管理页与选择页用**同一套**来源分组：本地 ROM / 内置 ROM / 旧格式 ROM；
 *  2. **分组绝不按安卓版本**：内置 ROM 拿 `android15` 当 id 只是历史特例，
 *     第三方 ROM（HnOS）从 A9 升到 A15 仍是同一个 id；
 *  3. 「已选」按 **rowId**（`<来源>:<romKey>`）判定：同版本的本地行与内置行只能有一行打勾。
 */
class RomGroupingTest {

    private fun row(
        id: String = "android15",
        version: String = "1.0.0",
        name: String = "Android 15",
        api: Int = 35,
        source: RomSource = RomSource.Builtin,
        state: RomState = RomState.Ready,
        hasArchive: Boolean = true,
        installedAt: Long = 0L,
        payload: String = "sha-" + id + "-" + version,
    ) = RomRow(
        key = RomKeys.key(id, version),
        id = id,
        version = version,
        displayName = name,
        description = "",
        androidVersion = "",
        apiLevel = api,
        source = source,
        state = state,
        installedAt = installedAt,
        hasArchive = hasArchive,
        payloadSha256 = payload,
    )

    @Test
    fun manageGroupsInFixedOrderAndSkipsEmpty() {
        val rows = listOf(
            row(version = "1.0.0", source = RomSource.Builtin),
            row(id = "android9", version = "1.0.0", name = "Android 9", api = 28, source = RomSource.Imported),
        )
        val groups = RomGrouping.groups(rows, selectedRowId = null)
        assertEquals(listOf(RomGrouping.GROUP_LOCAL, RomGrouping.GROUP_BUILTIN), groups.map { it.title })
    }

    @Test
    fun pickUsesTheSameSourceGroupsAsManage() {
        // 选择页与管理页是同一个列表的两种用途：分组必须同一套（来源），否则就是两套分组。
        val rows = listOf(
            row(version = "1.0.0", source = RomSource.Builtin),
            row(version = "1.1.0", source = RomSource.Imported, installedAt = 10L),
            row(id = "android9", version = "1.0.0", name = "Android 9", api = 28, source = RomSource.Imported),
        )
        val manage = RomGrouping.groups(rows, selectedRowId = null).map { it.title }
        val pick = RomGrouping.groups(rows, selectedRowId = null).map { it.title }
        assertEquals(manage, pick)
        assertEquals(listOf(RomGrouping.GROUP_LOCAL, RomGrouping.GROUP_BUILTIN), pick)
    }

    @Test
    fun noGroupTitleIsAnAndroidVersion() {
        // 这一条是给「按安卓版本分类」立的判据：组标题只能来自来源常量。
        val rows = listOf(
            row(id = "android15", version = "1.0.0", name = "Android 15", api = 35),
            row(id = "android10", version = "1.0.0", name = "Android 10", api = 29),
            row(id = "android9", version = "1.0.0", name = "Android 9", api = 28),
        )
        val titles = RomGrouping.groups(rows, selectedRowId = null).map { it.title }
        assertTrue(titles.none { it.contains("Android") })
        assertEquals(listOf(RomGrouping.GROUP_BUILTIN), titles)
    }

    @Test
    fun groupItemsSortedByIdThenVersionDescending() {
        // 组内：同一个 id 的版本挨在一起，版本号倒序（1.0.10 > 1.0.9，不是字符串比较）。
        val rows = listOf(
            row(id = "hnos", version = "1.0.9", name = "HnOS", api = 28),
            row(id = "android15", version = "1.0.0", name = "Android 15", api = 35),
            row(id = "android15", version = "1.0.10", name = "Android 15", api = 35),
        )
        val items = RomGrouping.groups(rows, selectedRowId = null).single().items
        assertEquals(
            listOf("Android 15 V1.0.10", "Android 15 V1.0.0", "HnOS V1.0.9"),
            items.map { it.title },
        )
    }

    @Test
    fun sameVersionTwoSourcesAreTwoRows() {
        // 同版本号的本地行与内置行是**两行**，不会互相顶掉。
        val rows = listOf(
            row(version = "1.0.0", source = RomSource.Builtin),
            row(version = "1.0.0", source = RomSource.Imported, installedAt = 5L),
        )
        val groups = RomGrouping.groups(rows, selectedRowId = null)
        assertEquals(2, groups.size)
        assertEquals(1, groups.first { it.title == RomGrouping.GROUP_LOCAL }.items.size)
        assertEquals(1, groups.first { it.title == RomGrouping.GROUP_BUILTIN }.items.size)
    }

    @Test
    fun selectedRowIsMarkedByRowIdOnly() {
        // 同 key 两行：只有 rowId 命中的那一行打勾（「内置 A10 已选」不等于「本地 A10 已选」）。
        val builtin = row(id = "android10", version = "1.0.0", name = "Android 10", api = 29)
        val imported = row(id = "android10", version = "1.0.0", name = "Android 10", api = 29, source = RomSource.Imported)
        assertTrue(builtin.rowId != imported.rowId)
        val groups = RomGrouping.groups(listOf(builtin, imported), selectedRowId = builtin.rowId)
        val items = groups.flatMap { it.items }
        assertEquals(2, items.size)
        assertTrue(items.first { it.rowId == builtin.rowId }.selected)
        assertFalse(items.first { it.rowId == imported.rowId }.selected)
    }

    @Test
    fun placeholderIsVisibleButNotUsable() {
        // 最小入包：列表里必须能看到这一版（否则用户以为安装包不支持），但不能选。
        val rows = listOf(
            row(
                version = "1.0.0",
                state = RomState.Pending,
                hasArchive = true,
            ).copy(placeholder = true),
        )
        val item = RomGrouping.groups(rows, selectedRowId = null).single().items.single()
        assertFalse(item.canUse)
        assertFalse(item.canRepair)
    }

    @Test
    fun builtinAndImportedBothCanCheckAndRepair() {
        // 这条是给「内置 ROM 的解包按钮被吃掉」立的判据：
        // 内置与本地导入都必须有「检查」与「重新解包」，且走同一条链。
        val builtin = row(source = RomSource.Builtin)
        val imported = row(source = RomSource.Imported)
        assertTrue(builtin.canCheck && builtin.canRepair)
        assertTrue(imported.canCheck && imported.canRepair)
        // 旧格式跟着安装包走，没有派生层。
        val legacy = row(source = RomSource.Legacy)
        assertFalse(legacy.canCheck || legacy.canRepair)
        // 原包不在就没有可重解的来源。
        assertFalse(builtin.copy(hasArchive = false).canRepair)
    }

    @Test
    fun itemTitleKeepsVersionAndSummaryIsOneLine() {
        val item = RomTexts.item(row(version = "1.0.0"))
        assertEquals("Android 15 V1.0.0", item.title)
        assertTrue(item.summary.contains("API 35"))
        assertTrue(item.summary.contains("内置"))
    }
}