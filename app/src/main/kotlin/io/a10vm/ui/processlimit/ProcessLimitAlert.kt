// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.processlimit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.main.widget.dialog.LimitDialog
import io.a10vm.ui.page.miuix.widgets.MiuixLimitDialog
import io.a10vm.ui.verse.LocalAppState

/**
 * 「系统在限制子进程」提示的**唯一宿主**（两套 UI 分支共用同一套时序，只换对话框组件）。
 *
 * 挂在导航宿主最外层，所以：
 * - 启动时的自动检测命中异常，无论用户当前在哪个 tab、哪个二级页都会弹出来；
 * - 只在 StoredLimit.shouldAlert 给出的那一次跃迁弹一次，弹出后立刻 consumeLimitAlert()，
 *   切 tab / 重进页面 / 转屏都不会重复弹；
 * - 「取消」只关掉这一次，「不再提示」写抑制位到下次真正解除；
 * - 正文里的蓝色链接是去解决页的唯一入口，按钮区不重复放按钮。
 */
@Composable
fun ProcessLimitAlertHost(useMiuix: Boolean) {
    val appState = LocalAppState.current
    val navigator = LocalNavigator.current
    val alert by appState.limitAlert.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(alert) {
        if (alert) {
            visible = true
            // 一次性事件：弹过就清掉，避免重进页面又弹一遍。
            appState.consumeLimitAlert()
        }
    }
    if (!visible) return

    val onOpenFix = {
        visible = false
        navigator.push(Route.ProcessLimit)
    }
    val onDismiss = { visible = false }
    val onMute = {
        visible = false
        appState.muteLimitAlert()
    }

    // 两个出口的时序完全一致，只有对话框组件按分支换。
    if (useMiuix) {
        MiuixLimitDialog(show = true, onOpenFix = onOpenFix, onDismiss = onDismiss, onMute = onMute)
    } else {
        LimitDialog(show = true, onOpenFix = onOpenFix, onDismiss = onDismiss, onMute = onMute)
    }
}
