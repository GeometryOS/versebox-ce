// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import me.kanadex.geometryos.boot.BootGateSequence
import androidx.compose.ui.Modifier

/**
 * 第一屏的**唯一入口**：两个 UI 分支二选一，签名完全相同（`docs/ui-rework/09/05` §4）。
 *
 * 两条硬规则都在这里表达，页面调用方不需要再判一次：
 *
 * 1. **不许用启动页遮挡「曾有画面」的场景**（`09/03` §5 第 1 条）：
 *    [BootUiState.showsFirstScreen] 为 false 时第一屏整体不组合，画面留在原地，
 *    只剩对话框浮在上面。
 * 2. 对话框与控制对话框的时序两分支各一份，但**状态与动作是同一套** [BootUiState] / [BootAction]。
 */
@Composable
fun BootHost(useMiuix: Boolean, state: BootUiState, onAction: (BootAction) -> Unit, modifier: Modifier = Modifier) {
    if (state.showsFirstScreen) {
        if (useMiuix) MiuixBootScreen(state, onAction) else Material3BootScreen(state, onAction)
    }
    val resources = LocalContext.current.resources
    val visible = remember(resources) { BootGateSequence(resources) }.visible(state)
    if (useMiuix) MiuixBootDialogs(visible, onAction) else Material3BootDialogs(visible, onAction)
}
