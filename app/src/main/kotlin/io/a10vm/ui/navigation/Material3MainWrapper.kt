// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.navigation

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import io.a10vm.domain.settings.model.preferences.ThemeState
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.page.main.settings.Material3MainCompactLayout
import io.a10vm.ui.page.main.settings.Material3MainWideScreenLayout
import io.a10vm.ui.settings.AppPreferences
import io.a10vm.ui.theme.LocalWindowLayoutInfo
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.verse.LocalAppState
import io.a10vm.ui.verse.rememberAppPreferences

/** Material 3 分支的 Tab 数据。字段与 InstallerX 的 NavigationTab 一致。 */
@Immutable
data class NavigationTab(val icon: ImageVector, val label: String)

@Composable
fun Material3MainPageWrapper(uiState: ThemeState) {
    val preferences: AppPreferences = rememberAppPreferences()
    val appState = LocalAppState.current
    // 只订阅**数量**：理由同 MiuixMainWrapper（2026-11-15 卡顿排查）。
    val vmCount by remember(appState) {
        appState.devices.map { it.size }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = appState.devices.value.size)
    // 角标开关（设置 → 外观）：只在这里读一次，布局层只收一个 Boolean。
    val showVmBadge by preferences.showVmBadge.collectAsStateWithLifecycle()
    val useBlur = uiState.useBlur
    val useFloatingBottomBar = uiState.useAppleFloatingBar
    val backdrop = rememberMaterial3BlurBackdrop(useBlur)

    val homeIcon = ImageVector.vectorResource(id = me.kanadex.versebox.R.drawable.ic_tile_icon)
    val tabs = remember(homeIcon) {
        listOf(
            NavigationTab(icon = homeIcon, label = "主页"),
            NavigationTab(icon = Icons.Rounded.Memory, label = "虚拟机"),
            NavigationTab(icon = AppIcons.History, label = "记录"),
            NavigationTab(icon = AppIcons.SettingsSuggest, label = "设置"),
        )
    }

    val pagerState = rememberPagerState(
        initialPage = preferences.mainPageIndex().coerceIn(0, tabs.size - 1),
        pageCount = { tabs.size },
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

    // 「查看运行记录」：切到记录 Tab（定位与筛选由 HistoryViewModel 消费 AppState.recordFocus）。
    LaunchedEffect(appState.recordsTabRequest) {
        if (appState.recordsTabRequest > 0) mainPagerState.animateToPage(2)
    }

    // 分辨率改完自动重启：回到「虚拟机」Tab（AppState.vmTabRequest 是唯一请求来源）。
    LaunchedEffect(appState.vmTabRequest) {
        if (appState.vmTabRequest > 0) mainPagerState.animateToPage(1)
    }

    val layoutInfo = LocalWindowLayoutInfo.current
    val showRail = layoutInfo.showNavigationRail
    val isMedium = layoutInfo.isMediumPortrait
    val navigateToVms = { mainPagerState.animateToPage(1) }

    if (showRail) {
        Material3MainWideScreenLayout(
            vmCount = vmCount,
            showVmBadge = showVmBadge,
            mainPagerState = mainPagerState,
            tabs = tabs,
            useBlur = useBlur,
            useFloatingBottomBar = useFloatingBottomBar,
            backdrop = backdrop,
            onNavigateToVms = navigateToVms,
        )
    } else {
        Material3MainCompactLayout(
            vmCount = vmCount,
            showVmBadge = showVmBadge,
            mainPagerState = mainPagerState,
            tabs = tabs,
            useBlur = useBlur,
            useFloatingBottomBar = useFloatingBottomBar,
            backdrop = backdrop,
            isMedium = isMedium,
            onNavigateToVms = navigateToVms,
        )
    }
}
