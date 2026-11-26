// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.verse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.a10vm.ui.MainActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.navigation.Material3MainPageWrapper
import io.a10vm.ui.navigation.MiuixMainPageWrapper
import io.a10vm.ui.navigation.VerseBoxNavContainer
import io.a10vm.ui.settings.AppPreferences
import io.a10vm.ui.theme.LocalWindowLayoutInfo
import io.a10vm.ui.theme.VerseBoxTheme
import io.a10vm.ui.theme.rememberWindowLayoutInfo
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用根。结构照 InstallerX 的 SettingsActivity：
 *
 * 主题（Miuix / Material 3 Expressive 二选一）→ 窗口布局信息 → 导航容器（NavDisplay）
 * → Main 路由下才是四 Tab 主壳。
 *
 * 主题切换时 [VerseBoxTheme] 用 movableContentOf 保留内容节点身份，
 * 所以换 UI 引擎不会重建 Pager、不会掉虚拟机连接。
 */
@Composable
fun VerseBoxApp(preferences: AppPreferences, state: AppState) {
    val theme by preferences.theme.collectAsStateWithLifecycle()
    VerseBoxTheme(theme) {
        val view = LocalView.current
        val dark = VerseBoxTheme.isDark
        val window = (view.context as? MainActivity)?.window
        if (window != null && !view.isInEditMode) {
            SideEffect {
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
            }
        }
        val layoutInfo = rememberWindowLayoutInfo()
        CompositionLocalProvider(
            LocalAppState provides state,
            LocalWindowLayoutInfo provides layoutInfo,
        ) {
            val background = if (theme.useMiuix) {
                MiuixTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background),
            ) {
                VerseBoxNavContainer(uiState = theme) {
                    if (theme.useMiuix) {
                        MiuixMainPageWrapper(theme)
                    } else {
                        Material3MainPageWrapper(theme)
                    }
                }
            }
        }
    }
}
