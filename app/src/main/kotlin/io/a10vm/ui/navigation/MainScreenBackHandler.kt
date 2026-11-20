// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
// 结构与 InstallerX-Revived 一致（其来源为 weishu/KernelSU，GPL-3.0）。
package io.a10vm.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * 主页面（四 Tab）的返回语义，与 InstallerX 完全相同：
 * 只有「还在 Main 这一条路由上」且「不在主页 Tab」时，返回键先退回主页 Tab；
 * 已经在主页就把返回交给系统（退到桌面 / 交给虚拟机窗口）。
 */
@Composable
fun MainScreenBackHandler(mainPagerState: MainPagerState, navController: Navigator) {
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navController.current() is Route.Main &&
                navController.backStackSize() == 1 &&
                mainPagerState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    val onBackCompleted: () -> Unit = { mainPagerState.animateToPage(0) }

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = onBackCompleted,
    )
}
