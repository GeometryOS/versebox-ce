// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.library.FloatingBottomBar
import io.a10vm.ui.library.FloatingBottomBarMode
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.MainPagerState
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.miuix.vm.MiuixVmListPage
import io.a10vm.ui.page.miuix.settings.home.MiuixHomePage
import io.a10vm.ui.page.miuix.settings.history.MiuixHistoryPage
import io.a10vm.ui.page.miuix.settings.preferred.MiuixPreferredPage
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.BadgedBox
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop as MiuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop as miuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun MainFloatingBottomBar(
    mainPagerState: MainPagerState,
    navigationItems: List<NavigationItem>,
    vmCount: Int,
    showVmBadge: Boolean,
    floatingBottomBarMode: FloatingBottomBarMode,
    floatingBackdrop: MiuixLayerBackdrop,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        FloatingBottomBar(
            items = navigationItems,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            selectedIndex = { mainPagerState.selectedPage },
            onSelected = { index -> mainPagerState.animateToPage(index) },
            backdrop = floatingBackdrop,
            mode = floatingBottomBarMode,
            iconContent = { item, index ->
                // 虚拟机 Tab 的数量角标：位置由 miuix 的 BadgedBox 负责，这里不算偏移、不画圆点。
                if (showVmBadge && index == 1 && vmCount > 0) {
                    BadgedBox(badge = { MiuixVmCountBadge(vmCount) }) {
                        Icon(imageVector = item.icon, contentDescription = null)
                    }
                } else {
                    Icon(imageVector = item.icon, contentDescription = null)
                }
            },
            labelContent = { item, _ ->
                Text(
                    text = item.label,
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

/** 紧凑布局（手机竖屏）。 */
@Composable
fun MiuixMainCompactLayout(
    vmCount: Int,
    showVmBadge: Boolean,
    mainPagerState: MainPagerState,
    navigationItems: List<NavigationItem>,
    snackbarHostState: SnackbarHostState,
    useFloatingBottomBar: Boolean,
    floatingBottomBarMode: FloatingBottomBarMode,
    floatingBackdrop: MiuixLayerBackdrop?,
    miuixBackdrop: MiuixLayerBackdrop?,
    onNavigateToVms: () -> Unit,
) {
    val navigator = LocalNavigator.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (useFloatingBottomBar && floatingBackdrop != null) {
                MainFloatingBottomBar(
                    mainPagerState = mainPagerState,
                    navigationItems = navigationItems,
                    vmCount = vmCount,
                    showVmBadge = showVmBadge,
                    floatingBottomBarMode = floatingBottomBarMode,
                    floatingBackdrop = floatingBackdrop,
                )
            } else if (!useFloatingBottomBar) {
                val blurActive = miuixBackdrop != null
                val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
                Box(
                    modifier = Modifier
                        .then(
                            if (blurActive) {
                                Modifier.textureBlur(
                                    backdrop = miuixBackdrop,
                                    shape = RectangleShape,
                                    blurRadius = 25f,
                                    colors = BlurColors(
                                        blendColors = listOf(
                                            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)),
                                        ),
                                    ),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .background(barColor),
                ) {
                    NavigationBar(color = barColor) {
                        navigationItems.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = mainPagerState.selectedPage == index,
                                onClick = { mainPagerState.animateToPage(index) },
                                icon = item.icon,
                                label = item.label,
                                badge = vmBadgeSlot(showVmBadge, index, vmCount),
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = mainPagerState.selectedPage == 1,
                enter = scaleIn(),
                exit = scaleOut(),
            ) {
                FloatingActionButton(
                    modifier = Modifier.padding(end = 16.dp),
                    containerColor = if (MiuixTheme.isDynamicColor) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.background
                    },
                    shadowElevation = 2.dp,
                    onClick = { navigator.push(Route.CreateVm()) },
                ) {
                    Icon(
                        imageVector = AppIcons.Add,
                        modifier = Modifier.size(40.dp),
                        contentDescription = "新建虚拟机",
                        tint = if (MiuixTheme.isDynamicColor) {
                            MiuixTheme.colorScheme.onPrimary
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                    )
                }
            }
        },
    ) { paddingValues ->
        MiuixMainPagerContent(
            vmCount = vmCount,
            mainPagerState = mainPagerState,
            navigationItems = navigationItems,
            snackbarHostState = snackbarHostState,
            modifier = Modifier.fillMaxSize(),
            outerPadding = paddingValues,
            useFloatingBottomBar = useFloatingBottomBar,
            floatingBackdrop = floatingBackdrop,
            miuixBackdrop = miuixBackdrop,
            onNavigateToVms = onNavigateToVms,
        )
    }
}

/** 宽屏布局（平板 / 横屏）：导航换成左侧 Rail，与 InstallerX 一致。 */
@Composable
fun MiuixMainWideScreenLayout(
    vmCount: Int,
    showVmBadge: Boolean,
    mainPagerState: MainPagerState,
    navigationItems: List<NavigationItem>,
    snackbarHostState: SnackbarHostState,
    useFloatingBottomBar: Boolean,
    floatingBottomBarMode: FloatingBottomBarMode,
    floatingBackdrop: MiuixLayerBackdrop?,
    miuixBackdrop: MiuixLayerBackdrop?,
    onNavigateToVms: () -> Unit,
) {
    if (useFloatingBottomBar) {
        MiuixMainWideContent(
            vmCount = vmCount,
            showVmBadge = showVmBadge,
            mainPagerState = mainPagerState,
            navigationItems = navigationItems,
            snackbarHostState = snackbarHostState,
            useFloatingBottomBar = true,
            floatingBottomBarMode = floatingBottomBarMode,
            floatingBackdrop = floatingBackdrop,
            miuixBackdrop = miuixBackdrop,
            onNavigateToVms = onNavigateToVms,
        )
    } else {
        val expandRail = shouldExpandNavigationRail()
        val railState = rememberNavigationRailState(
            initialValue = if (expandRail) NavigationRailValue.Expanded else NavigationRailValue.Collapsed,
        )
        val contentWindowInsets = WindowInsets.systemBars.union(
            WindowInsets.displayCutout.exclude(WindowInsets.displayCutout.only(WindowInsetsSides.Start)),
        )
        val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start)

        LaunchedEffect(expandRail) {
            if (expandRail) railState.expand() else railState.collapse()
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface),
        ) {
            NavigationRail(state = railState) {
                navigationItems.forEachIndexed { index, item ->
                    NavigationRailItem(
                        selected = mainPagerState.selectedPage == index,
                        onClick = { mainPagerState.animateToPage(index) },
                        icon = item.icon,
                        label = item.label,
                        badge = vmBadgeSlot(showVmBadge, index, vmCount),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .consumeWindowInsets(startInsets),
            ) {
                MiuixMainWideContent(
                    vmCount = vmCount,
                    showVmBadge = showVmBadge,
                    mainPagerState = mainPagerState,
                    navigationItems = navigationItems,
                    snackbarHostState = snackbarHostState,
                    useFloatingBottomBar = false,
                    floatingBottomBarMode = FloatingBottomBarMode.None,
                    floatingBackdrop = floatingBackdrop,
                    miuixBackdrop = miuixBackdrop,
                    onNavigateToVms = onNavigateToVms,
                    contentWindowInsets = contentWindowInsets,
                )
            }
        }
    }
}

@Composable
private fun MiuixMainWideContent(
    vmCount: Int,
    showVmBadge: Boolean,
    mainPagerState: MainPagerState,
    navigationItems: List<NavigationItem>,
    snackbarHostState: SnackbarHostState,
    useFloatingBottomBar: Boolean,
    floatingBottomBarMode: FloatingBottomBarMode,
    floatingBackdrop: MiuixLayerBackdrop?,
    miuixBackdrop: MiuixLayerBackdrop?,
    onNavigateToVms: () -> Unit,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
) {
    val navigator = LocalNavigator.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = contentWindowInsets,
        bottomBar = {
            if (useFloatingBottomBar && floatingBackdrop != null) {
                MainFloatingBottomBar(
                    mainPagerState = mainPagerState,
                    navigationItems = navigationItems,
                    vmCount = vmCount,
                    showVmBadge = showVmBadge,
                    floatingBottomBarMode = floatingBottomBarMode,
                    floatingBackdrop = floatingBackdrop,
                )
            }
        },
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = mainPagerState.selectedPage == 1,
                enter = scaleIn(),
                exit = scaleOut(),
            ) {
                FloatingActionButton(
                    modifier = Modifier.padding(end = 16.dp),
                    containerColor = if (MiuixTheme.isDynamicColor) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.background
                    },
                    shadowElevation = 2.dp,
                    onClick = { navigator.push(Route.CreateVm()) },
                ) {
                    Icon(
                        imageVector = AppIcons.Add,
                        modifier = Modifier.size(40.dp),
                        contentDescription = "新建虚拟机",
                        tint = if (MiuixTheme.isDynamicColor) {
                            MiuixTheme.colorScheme.onPrimary
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                    )
                }
            }
        },
    ) { paddingValues ->
        MiuixMainPagerContent(
            vmCount = vmCount,
            mainPagerState = mainPagerState,
            navigationItems = navigationItems,
            snackbarHostState = snackbarHostState,
            modifier = Modifier.fillMaxSize(),
            outerPadding = paddingValues,
            useFloatingBottomBar = useFloatingBottomBar,
            floatingBackdrop = floatingBackdrop,
            miuixBackdrop = miuixBackdrop,
            onNavigateToVms = onNavigateToVms,
        )
    }
}

@Composable
private fun shouldExpandNavigationRail(): Boolean {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    return with(density) { windowInfo.containerSize.width.toDp() >= 1200.dp }
}

@Composable
private fun MiuixMainPagerContent(
    vmCount: Int,
    modifier: Modifier = Modifier,
    mainPagerState: MainPagerState,
    navigationItems: List<NavigationItem>,
    snackbarHostState: SnackbarHostState,
    outerPadding: PaddingValues,
    useFloatingBottomBar: Boolean,
    floatingBackdrop: MiuixLayerBackdrop?,
    miuixBackdrop: MiuixLayerBackdrop?,
    onNavigateToVms: () -> Unit,
) {
    HorizontalPager(
        state = mainPagerState.pagerState,
        userScrollEnabled = true,
        overscrollEffect = null,
        beyondViewportPageCount = 1,
        modifier = modifier
            .then(
                if (useFloatingBottomBar && floatingBackdrop != null) {
                    Modifier.miuixLayerBackdrop(floatingBackdrop)
                } else {
                    Modifier
                },
            )
            .then(if (!useFloatingBottomBar && miuixBackdrop != null) Modifier.miuixLayerBackdrop(miuixBackdrop) else Modifier),
    ) { page ->
        val useBlur = floatingBackdrop != null && miuixBackdrop != null
        when (page) {
            0 -> MiuixHomePage(
                enableBlur = useBlur,
                title = navigationItems[page].label,
                vmCount = vmCount,
                outerPadding = outerPadding,
                snackbarHostState = snackbarHostState,
                onNavigateToVms = onNavigateToVms,
            )

            1 -> MiuixVmListPage(
                enableBlur = useBlur,
                title = navigationItems[page].label,
                outerPadding = outerPadding,
                snackbarHostState = snackbarHostState,
            )

            2 -> MiuixHistoryPage(
                enableBlur = useBlur,
                title = navigationItems[page].label,
                outerPadding = outerPadding,
            )

            3 -> MiuixPreferredPage(
                enableBlur = useBlur,
                title = navigationItems[page].label,
                outerPadding = outerPadding,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}
/**
 * 虚拟机 Tab 的数量角标内容。
 *
 * 只提供内容：位置、外扩与裁剪都由 miuix 的 [BadgedBox] / 导航项的 badge 槽位负责，
 * 这里不算偏移、不画圆点、不改颜色（默认就是 miuix 的角标配色）。
 */
@Composable
private fun MiuixVmCountBadge(count: Int) {
    Badge { Text(text = count.toString()) }
}

/**
 * badge 槽位：只有「开关打开 + 是虚拟机 Tab（第 1 个）+ 数量 > 0」才给内容，否则 null。
 * 传 null 时 miuix 的导航项不会追加 badgeBounds，也就没有额外布局开销。
 */
private fun vmBadgeSlot(show: Boolean, index: Int, count: Int): (@Composable () -> Unit)? =
    if (show && index == 1 && count > 0) {
        { MiuixVmCountBadge(count) }
    } else {
        null
    }
