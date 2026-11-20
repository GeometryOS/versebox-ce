// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailColors
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.library.FloatingBottomBar
import io.a10vm.ui.library.FloatingBottomBarDefaults
import io.a10vm.ui.library.FloatingBottomBarMode
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.MainPagerState
import io.a10vm.ui.navigation.NavigationTab
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.main.settings.history.HistoryPage
import io.a10vm.ui.page.main.settings.home.HomePage
import io.a10vm.ui.page.main.settings.preferred.PreferredPage
import io.a10vm.ui.page.main.vm.VmListPage
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * Material 3 Expressive 风格的主壳。
 *
 * 与 Miuix 分支**各自独立**：这里全部是 androidx.compose.material3 的原生组件
 * （ShortNavigationBar / WideNavigationRail / Scaffold / FloatingActionButton），
 * 不借用任何 Miuix 组件 —— InstallerX 也是这么分的。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Material3MainCompactLayout(
    vmCount: Int,
    showVmBadge: Boolean,
    mainPagerState: MainPagerState,
    tabs: List<NavigationTab>,
    useBlur: Boolean,
    useFloatingBottomBar: Boolean,
    backdrop: LayerBackdrop?,
    isMedium: Boolean,
    onNavigateToVms: () -> Unit,
) {
    val navigator = LocalNavigator.current
    val navigationWindowInsets = WindowInsets.safeDrawing.only(
        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(),
        bottomBar = {
            if (useFloatingBottomBar) {
                Material3FloatingBottomBar(
                    mainPagerState = mainPagerState,
                    tabs = tabs,
                    vmCount = vmCount,
                    showVmBadge = showVmBadge,
                    backdrop = backdrop,
                )
            } else {
                RowNavigation(
                    modifier = Modifier.verseBoxMaterial3BlurEffect(backdrop),
                    windowInsets = navigationWindowInsets,
                    tabs = tabs,
                    currentPage = mainPagerState.selectedPage,
                    onPageChanged = { mainPagerState.animateToPage(it) },
                    vmCount = vmCount,
                    showVmBadge = showVmBadge,
                    containerColor = if (useBlur) Color.Transparent else BottomAppBarDefaults.containerColor,
                    isMedium = isMedium,
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = mainPagerState.selectedPage == 1,
                enter = scaleIn(),
                exit = scaleOut(),
            ) {
                FloatingActionButton(
                    modifier = Modifier.padding(end = 16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = { navigator.push(Route.CreateVm) },
                ) {
                    Icon(imageVector = AppIcons.Add, contentDescription = "新建虚拟机")
                }
            }
        },
    ) { paddingValues ->
        Material3MainPagerContent(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues)
                .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
            vmCount = vmCount,
            mainPagerState = mainPagerState,
            tabs = tabs,
            useBlur = useBlur,
            outerPadding = paddingValues,
            onNavigateToVms = onNavigateToVms,
            sharedBackdrop = null,
        )
    }
}

@Composable
fun Material3MainWideScreenLayout(
    vmCount: Int,
    showVmBadge: Boolean,
    mainPagerState: MainPagerState,
    tabs: List<NavigationTab>,
    useBlur: Boolean,
    useFloatingBottomBar: Boolean,
    backdrop: LayerBackdrop?,
    onNavigateToVms: () -> Unit,
) {
    val navigationWindowInsets = WindowInsets.safeDrawing.only(
        WindowInsetsSides.Vertical + WindowInsetsSides.Start,
    )

    if (useFloatingBottomBar) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(),
            bottomBar = {
                Material3FloatingBottomBar(
                    mainPagerState = mainPagerState,
                    tabs = tabs,
                    vmCount = vmCount,
                    showVmBadge = showVmBadge,
                    backdrop = backdrop,
                )
            },
        ) { paddingValues ->
            Material3MainPagerContent(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(paddingValues)
                    .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
                vmCount = vmCount,
                mainPagerState = mainPagerState,
                tabs = tabs,
                useBlur = useBlur,
                outerPadding = paddingValues,
                onNavigateToVms = onNavigateToVms,
            )
        }
    } else {
        Row(modifier = Modifier.fillMaxSize()) {
            ColumnNavigation(
                windowInsets = navigationWindowInsets,
                tabs = tabs,
                currentPage = mainPagerState.selectedPage,
                onPageChanged = { mainPagerState.animateToPage(it) },
                vmCount = vmCount,
                showVmBadge = showVmBadge,
            )

            Material3MainPagerContent(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .consumeWindowInsets(navigationWindowInsets.only(WindowInsetsSides.Start))
                    .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
                vmCount = vmCount,
                mainPagerState = mainPagerState,
                tabs = tabs,
                useBlur = useBlur,
                outerPadding = PaddingValues(0.dp),
                onNavigateToVms = onNavigateToVms,
            )
        }
    }
}

@Composable
private fun Material3FloatingBottomBar(
    mainPagerState: MainPagerState,
    tabs: List<NavigationTab>,
    vmCount: Int,
    showVmBadge: Boolean,
    backdrop: LayerBackdrop?,
) {
    val fallbackBackdrop = rememberLayerBackdrop()
    val floatingBackdrop = backdrop ?: fallbackBackdrop

    Box(modifier = Modifier.fillMaxWidth()) {
        FloatingBottomBar(
            items = tabs,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
            selectedIndex = { mainPagerState.selectedPage },
            onSelected = { index -> mainPagerState.animateToPage(index) },
            backdrop = floatingBackdrop,
            mode = if (backdrop != null) FloatingBottomBarMode.Blur else FloatingBottomBarMode.None,
            colors = FloatingBottomBarDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                indicatorColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            iconContent = { tab, index ->
                BadgedBox(
                    badge = {
                        VmCountBadge(
                            showBadge = showVmBadge && index == 1 && vmCount > 0,
                            vmCount = vmCount,
                        )
                    },
                ) {
                    Icon(imageVector = tab.icon, contentDescription = null)
                }
            },
            labelContent = { tab, _ ->
                Text(
                    text = tab.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            },
        )
    }
}

@Composable
private fun Material3MainPagerContent(
    modifier: Modifier = Modifier,
    sharedBackdrop: LayerBackdrop? = null,
    vmCount: Int,
    mainPagerState: MainPagerState,
    tabs: List<NavigationTab>,
    useBlur: Boolean,
    outerPadding: PaddingValues,
    onNavigateToVms: () -> Unit,
) {
    HorizontalPager(
        state = mainPagerState.pagerState,
        userScrollEnabled = true,
        // 与 Miuix 分支一致：预组合相邻页，跨 tab 跳转时目标页不用等动画开始才组合。
        beyondViewportPageCount = 1,
        modifier = modifier,
    ) { page ->
        when (page) {
            0 -> HomePage(
                useBlur = useBlur,
                title = tabs[page].label,
                outerPadding = outerPadding,
                vmCount = vmCount,
                onNavigateToVms = onNavigateToVms,
            )

            1 -> VmListPage(
                useBlur = useBlur,
                title = tabs[page].label,
                outerPadding = outerPadding,
                sharedBackdrop = sharedBackdrop,
            )

            2 -> HistoryPage(
                useBlur = useBlur,
                title = tabs[page].label,
                outerPadding = outerPadding,
                sharedBackdrop = sharedBackdrop,
            )

            3 -> PreferredPage(
                useBlur = useBlur,
                title = tabs[page].label,
                outerPadding = outerPadding,
                sharedBackdrop = sharedBackdrop,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowNavigation(
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets,
    tabs: List<NavigationTab>,
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
    vmCount: Int,
    showVmBadge: Boolean,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    isMedium: Boolean = false,
) {
    ShortNavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentSize(),
        windowInsets = windowInsets,
        containerColor = containerColor,
        arrangement = if (isMedium) ShortNavigationBarArrangement.Centered else ShortNavigationBarArrangement.EqualWeight,
    ) {
        tabs.forEachIndexed { index, navigationData ->
            ShortNavigationBarItem(
                selected = currentPage == index,
                onClick = { onPageChanged(index) },
                iconPosition = if (isMedium) NavigationItemIconPosition.Start else NavigationItemIconPosition.Top,
                icon = {
                    BadgedBox(
                        badge = {
                            VmCountBadge(
                                showBadge = showVmBadge && index == 1 && vmCount > 0,
                                vmCount = vmCount,
                            )
                        },
                    ) {
                        Icon(imageVector = navigationData.icon, contentDescription = navigationData.label)
                    }
                },
                label = { Text(text = navigationData.label) },
            )
        }
    }
}

@Composable
private fun ColumnNavigation(
    windowInsets: WindowInsets,
    tabs: List<NavigationTab>,
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
    vmCount: Int,
    showVmBadge: Boolean,
) {
    val state = rememberWideNavigationRailState()
    val scope = rememberCoroutineScope()

    WideNavigationRail(
        state = state,
        windowInsets = windowInsets,
        colors = WideNavigationRailColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modalContainerColor = WideNavigationRailDefaults.colors().modalContainerColor,
            modalScrimColor = WideNavigationRailDefaults.colors().modalScrimColor,
            modalContentColor = WideNavigationRailDefaults.colors().modalContentColor,
        ),
        header = {
            IconButton(
                modifier = Modifier
                    .padding(start = 24.dp)
                    .semantics {
                        stateDescription =
                            if (state.currentValue == WideNavigationRailValue.Expanded) "已展开" else "已收起"
                    },
                onClick = {
                    scope.launch {
                        if (state.targetValue == WideNavigationRailValue.Expanded) {
                            state.collapse()
                        } else {
                            state.expand()
                        }
                    }
                },
            ) {
                if (state.targetValue == WideNavigationRailValue.Expanded) {
                    Icon(AppIcons.MenuOpen, "收起导航栏")
                } else {
                    Icon(AppIcons.Menu, "展开导航栏")
                }
            }
        },
    ) {
        tabs.forEachIndexed { index, navigationTab ->
            WideNavigationRailItem(
                railExpanded = state.targetValue == WideNavigationRailValue.Expanded,
                selected = currentPage == index,
                onClick = { onPageChanged(index) },
                icon = {
                    BadgedBox(
                        badge = {
                            VmCountBadge(
                                showBadge = showVmBadge && index == 1 && vmCount > 0,
                                vmCount = vmCount,
                            )
                        },
                    ) {
                        Icon(imageVector = navigationTab.icon, contentDescription = navigationTab.label)
                    }
                },
                label = { Text(text = navigationTab.label) },
            )
        }
    }
}

@Composable
private fun VmCountBadge(showBadge: Boolean, vmCount: Int) {
    AnimatedVisibility(
        visible = showBadge,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        label = "badge",
    ) {
        Badge(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ) {
            Text(vmCount.toString())
        }
    }
}
