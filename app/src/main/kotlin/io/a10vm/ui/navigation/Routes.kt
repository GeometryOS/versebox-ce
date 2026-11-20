// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 类型安全的路由表（照 InstallerX 的 Routes.kt）。
 *
 * 每一条都是 NavKey：miuix-nav 会把返回栈序列化成 JSON 存进 SavedState，
 * 缺 @Serializable 会在首次组合时抛 SerializationException（已踩过）。
 *
 * Main 是四 Tab 宿主，其余都是二级 NavPage —— 除了虚拟机显示窗口
 * （[io.a10vm.ui.FullscreenActivity]，它要独占 Surface），本应用不再为页面开 Activity。
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    // ---- 主题 / 设置类二级页 ----
    @Serializable
    data object Theme : Route

    @Serializable
    data object Network : Route

    @Serializable
    data object Lab : Route

    @Serializable
    data object About : Route

    @Serializable
    data object OpenSourceLicense : Route

    // ---- 虚拟机 / 记录类二级页 ----
    @Serializable
    data object ProcessLimit : Route

    @Serializable
    data object CreateVm : Route

    /**
     * ROM 列表（唯一的 ROM 页面，两种用途共用一套版式与一个 ViewModel）。
     *
     * @param pickForCreate true = 新建虚拟机页「安卓版本」行进来的**选择**模式：
     *   右下角是「选择 / 已选」，选中即回上一页，不在这里创建
     *   （`docs/rom-package/14-版本管理/` §6：选择 ROM 只有这一页）。
     */
    @Serializable
    data class RomList(val pickForCreate: Boolean = false) : Route

    @Serializable
    data class VmManage(val id: String) : Route

    /**
     * ROM 版本管理：管理页「运行」分组的第一行进入（`docs/rom-package/14-版本管理/01` §2）。
     *
     * 顶部单独一档「使用最新版本」，下面按同系列列出版本；切换/降级都要标准对话框确认。
     */
    @Serializable
    data class VmRomVersion(val id: String) : Route

    /** 分辨率设置页：管理页「显示」分组的「分辨率」行进入（docs/display-settings/02）。 */
    @Serializable
    data class VmDisplay(val id: String) : Route

    @Serializable
    data class RecordDetail(val id: Long) : Route

    /** 日志原文预览：独立页面（不是对话框），带分享。 */
    @Serializable
    data class RecordLog(val id: Long, val path: String) : Route
}
