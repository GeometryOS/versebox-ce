// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.navigation

import android.os.Build
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import io.a10vm.domain.settings.model.preferences.ThemeState
import io.a10vm.ui.library.FloatingBottomBarMode
import io.a10vm.ui.page.miuix.settings.MiuixMainCompactLayout
import io.a10vm.ui.page.miuix.settings.MiuixMainWideScreenLayout
import io.a10vm.ui.settings.AppPreferences
import io.a10vm.ui.theme.LocalWindowLayoutInfo
import io.a10vm.ui.theme.WindowLayoutType
import io.a10vm.ui.theme.rememberMiuixBlurBackdrop
import io.a10vm.ui.verse.LocalAppState
import io.a10vm.ui.verse.rememberAppPreferences
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * Miuix 风格主页面：Tab 定义、Pager、返回语义、底栏形态都在这里决定，
 * 与 InstallerX 的 MiuixMainPageWrapper 同构。
 */
@Composable
fun MiuixMainPageWrapper(uiState: ThemeState) {
    val layoutInfo = LocalWindowLayoutInfo.current
    val preferences: AppPreferences = rememberAppPreferences()
    val appState = LocalAppState.current
    // 只订阅**数量**：整个 devices 列表一变（含每 250ms 的运行态推送）就会重组整个主壳
    // （Pager + 底栏 + 所有布局）。这里只用来画角标 / 传给布局一个 Int（2026-11-15 卡顿排查）。
    val vmCount by remember(appState) {
        appState.devices.map { it.size }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = appState.devices.value.size)
    // 角标开关（设置 → 外观）：只在这里读一次，布局层只收一个 Boolean。
    val showVmBadge by preferences.showVmBadge.collectAsStateWithLifecycle()

    val useBlur = uiState.useBlur
    val useFloatingBottomBar = uiState.useAppleFloatingBar
    val floatingBottomBarMode = when {
        useBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> FloatingBottomBarMode.LiquidGlass
        useBlur -> FloatingBottomBarMode.Blur
        else -> FloatingBottomBarMode.None
    }

    val homeIcon = ImageVector.vectorResource(id = me.kanadex.versebox.R.drawable.ic_tile_icon)
    val navigationItems = remember(homeIcon) {
        listOf(
            NavigationItem(label = "主页", icon = homeIcon),
            NavigationItem(label = "虚拟机", icon = Icons.Rounded.Memory),
            NavigationItem(label = "记录", icon = Icons.Rounded.ReceiptLong),
            NavigationItem(label = "设置", icon = Icons.Rounded.Settings),
        )
    }

    val pagerState = rememberPagerState(
        initialPage = preferences.mainPageIndex().coerceIn(0, navigationItems.size - 1),
        pageCount = { navigationItems.size },
    )
    val mainPagerState = rememberMainPagerState(pagerState)
    val currentPage = mainPagerState.pagerState.currentPage
    val settledPage = mainPagerState.pagerState.settledPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }
    LaunchedEffect(settledPage) {
        if (preferences.mainPageIndex() != settledPage) preferences.setMainPageIndex(settledPage)
    }
    MainScreenBackHandler(mainPagerState = mainPagerState, navController = LocalNavigator.current)

    // 「查看运行记录」/「查看这台虚拟机的记录」：切到记录 Tab。
    // 筛选与定位由 HistoryViewModel 按 AppState.recordFocus 自己做（09/05 §2），主壳只负责切 tab。
    LaunchedEffect(appState.recordsTabRequest) {
        if (appState.recordsTabRequest > 0) mainPagerState.animateToPage(2)
    }

    // 分辨率改完自动重启：回到「虚拟机」Tab（AppState.vmTabRequest 是唯一请求来源）。
    LaunchedEffect(appState.vmTabRequest) {
        if (appState.vmTabRequest > 0) mainPagerState.animateToPage(1)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val floatingBackdrop = rememberLayerBackdrop()
    val miuixBackdrop = rememberMiuixBlurBackdrop(useBlur)
    val navigateToVms = { mainPagerState.animateToPage(1) }

    if (layoutInfo.type == WindowLayoutType.EXPANDED || layoutInfo.showNavigationRail) {
        MiuixMainWideScreenLayout(
            vmCount = vmCount,
            showVmBadge = showVmBadge,
            mainPagerState = mainPagerState,
            navigationItems = navigationItems,
            snackbarHostState = snackbarHostState,
            useFloatingBottomBar = useFloatingBottomBar,
            floatingBottomBarMode = floatingBottomBarMode,
            floatingBackdrop = floatingBackdrop,
            miuixBackdrop = miuixBackdrop,
            onNavigateToVms = navigateToVms,
        )
    } else {
        MiuixMainCompactLayout(
            vmCount = vmCount,
            showVmBadge = showVmBadge,
            mainPagerState = mainPagerState,
            navigationItems = navigationItems,
            snackbarHostState = snackbarHostState,
            useFloatingBottomBar = useFloatingBottomBar,
            floatingBottomBarMode = floatingBottomBarMode,
            floatingBackdrop = floatingBackdrop,
            miuixBackdrop = miuixBackdrop,
            onNavigateToVms = navigateToVms,
        )
    }
}
