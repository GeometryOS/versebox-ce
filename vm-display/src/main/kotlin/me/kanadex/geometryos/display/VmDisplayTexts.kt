// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.display

/**
 * 显示设置对用户可见的全部文案。放策略库是因为两套 UI 必须一字不差，
 * 各写一遍就会出现取消和稍后混用，用户以为在取消修改，其实只是稍后重启。
 *
 * 这里只放随状态变化、两个分支必须一致的文案，DPI 前缀这类格式由 DisplayGeometry 给。
 */
object VmDisplayTexts {
    const val GROUP = "显示"

    const val TITLE = "分辨率"

    const val NATIVE_PORTRAIT = "真机分辨率（竖屏）"
    const val NATIVE_LANDSCAPE = "真机分辨率（横屏）"

    const val RESTART_TITLE = "重启"
    const val RESTART_MESSAGE = "需要重启才能使这次修改生效，确定重启吗？"
    const val RESTART_CONFIRM = "确定"
    const val RESTART_LATER = "稍后"

    /**
     * 关机过程中的加载框。不写正在关机，用户看到的是整页收尾，
     * 具体到关这个字只会让人以为卡在关机里
     */
    const val IN_PROGRESS = "正在进行中，请稍候..."

    const val SAVE_FAILED = "保存失败，请重试"

    // 等关机超过 90 秒还没回空闲。这时不跳转也不自动启动
    const val STOP_TIMEOUT = "关机没有及时完成，请稍后再试"
}
