// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

// 列表里的一个分类，标题和算好的行。页面拿到什么画什么，不自己分组排序
data class RomGroup(val title: String, val items: List<RomItem>)

/**
 * ROM 列表的唯一分组。管理页和选择页共用这一份，写两套就会让用户看到两套分类。
 *
 * 按来源分组，组内先按 id 再按版本号倒序，选中判定用 rowId。
 * 不按安卓版本分：ROM 的身份是 id，第三方 ROM 可以从 Android 9 升到 15。
 */
object RomGrouping {

    // 按来源分组，顺序固定为本地、内置、旧格式，空组不出现
    fun groups(rows: List<RomRow>, selectedRowId: String?): List<RomGroup> {
        val local = ordered(rows.filter { it.source == RomSource.Imported })
        val builtin = ordered(rows.filter { it.source == RomSource.Builtin })
        val legacy = ordered(rows.filter { it.source == RomSource.Legacy })
        return listOf(
            RomGroup(GROUP_LOCAL, items(local, selectedRowId)),
            RomGroup(GROUP_BUILTIN, items(builtin, selectedRowId)),
            RomGroup(GROUP_LEGACY, items(legacy, selectedRowId)),
        ).filter { it.items.isNotEmpty() }
    }

    // 组内先按 id 排，同系统的挨在一起，再按版本号倒序
    private fun ordered(rows: List<RomRow>): List<RomRow> = rows.sortedWith(
        compareBy<RomRow> { it.id }
            .thenComparator { left, right -> RomKeys.compareVersions(right.version, left.version) },
    )

    private fun items(rows: List<RomRow>, selectedRowId: String?): List<RomItem> =
        rows.map { RomTexts.item(it, selected = selectedRowId != null && it.rowId == selectedRowId) }

    const val GROUP_LOCAL = "本地 ROM"
    const val GROUP_BUILTIN = "内置 ROM"
    const val GROUP_LEGACY = "旧格式 ROM（跟随安装包）"
}
