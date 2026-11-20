// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.verse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import io.a10vm.ui.settings.AppPreferences

/**
 * 应用级状态的提供点。
 *
 * 为什么用 CompositionLocal 而不是把 AppState 一路当参数传：
 * 四 Tab 与全部二级页都要读「虚拟机列表 / 运行态」，逐层传参会把每一层签名污染，
 * 而 AppState 在进程内只有一个实例（[AppState] 的生命周期挂在 MainActivity 上）。
 */
val LocalAppState = staticCompositionLocalOf<AppState> {
    error("AppState 未提供（应只在 VerseBoxApp 内部使用）")
}

@Composable
fun rememberAppPreferences(): AppPreferences {
    val context = LocalContext.current
    return remember(context) { AppPreferences.get(context) }
}
