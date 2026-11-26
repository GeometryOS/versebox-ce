// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.vm

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.main.widget.card.StatusCard
import io.a10vm.ui.page.main.widget.setting.BlockingLoadingIndicator
import io.a10vm.ui.page.main.widget.setting.RadioButtonWidget
import io.a10vm.ui.page.main.widget.setting.NavigationItemWidget
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import io.a10vm.ui.vm.VmActions
import io.a10vm.ui.verse.LocalAppState
import me.kanadex.geometryos.rom.RomConfirmKind
import me.kanadex.geometryos.rom.RomListAction
import me.kanadex.geometryos.rom.RomListMode
import me.kanadex.geometryos.rom.RomTexts
import me.kanadex.geometryos.rom.rememberRomListViewModel
import me.kanadex.geometryos.rom.android.RomVersionAction
import me.kanadex.geometryos.rom.android.rememberRomVersionViewModel

/**
 * ROM 版本管理（Material 3 Expressive 分支）。版本行直接复用 ROM 管理卡片与 RomItem 状态；
 * 最新版本仍使用单选组件，缺失提示沿用主页状态卡。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RomVersionPage(id: String, useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appState = LocalAppState.current
    val viewModel = rememberRomVersionViewModel(id)
    val romListViewModel = rememberRomListViewModel(RomListMode.Manage)
    val romListState by romListViewModel.state.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = rememberMaterial3BlurBackdrop(useBlur)

    LaunchedEffect(romListState.rows) {
        viewModel.dispatch(RomVersionAction.Refresh)
    }

    // 时序与 Miuix 分支逐项相同：先退出两级页面 + 回到虚拟机 tab，再按标准入口启动。
    LaunchedEffect(state.restartCompleted) {
        if (!state.restartCompleted) return@LaunchedEffect
        viewModel.dispatch(RomVersionAction.ConsumeRestartCompleted)
        navigator.popUntil { key -> key is Route.Main }
        appState.requestVmTab()
        val device = appState.device(id) ?: return@LaunchedEffect
        VmActions.launch(context, appState, device, appState.runtime(id))
    }

    // 选中的版本还不能用（没解包 / 解包体不全）：交给 ROM 列表的解包链。
    LaunchedEffect(state.handoffKey) {
        val key = state.handoffKey ?: return@LaunchedEffect
        viewModel.dispatch(RomVersionAction.ConsumeHandoff)
        // 带 rowId 交给解包链：同版本可能有内置/本地两行，只给 romKey 会解错那一份。
        appState.focusRom(key, repairVmId = id, rowId = state.handoffRowId)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.verseBoxMaterial3BlurEffect(backdrop),
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(RomTexts.VERSION_PAGE_TITLE) },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(AppIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backdrop.getMaterial3AppBarColor(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    scrolledContainerColor = backdrop.getMaterial3AppBarColor(),
                ),
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
        ) {
            // 「为什么你会被带到这里」：当前版本已经不在了。
            if (state.selectedMissing) {
                item(key = "missing") {
                    StatusCard(
                        title = RomTexts.MISSING_VERSION_TITLE,
                        description = RomTexts.missingVersionSummary(
                            state.currentText,
                            state.latest?.takeIf { state.latestNewer }?.title,
                        ),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = {},
                        actionText = "请选择一个仍然存在的 ROM 版本",
                        icon = AppIcons.Android,
                    )
                }
            }
            state.latest?.let { latest ->
                item(key = "latest") {
                    SegmentedColumn(title = RomTexts.VERSION_LATEST_TITLE) {
                        item(key = "latest-row") {
                            RadioButtonWidget(
                                title = latest.title,
                                description = latest.summary,
                                selected = latest.selected,
                                onClick = { viewModel.dispatch(RomVersionAction.SelectLatest) },
                            )
                        }
                    }
                }
            }

            if (state.isLoading || state.isEmpty) {
                item(key = "versions-empty") {
                    SegmentedColumn(title = RomTexts.versionGroupTitle(state.romName)) {
                        item {
                            NavigationItemWidget(
                                icon = AppIcons.Android,
                                title = if (state.isLoading) "正在读取版本…" else "没有可用的版本",
                                description = if (state.isLoading) "正在读取本地和内置 ROM" else "先导入这个系列的 ROM 包",
                                onClick = { if (!state.isLoading) navigator.push(Route.RomList()) },
                            )
                        }
                    }
                }
            }
            state.romGroups.forEach { group ->
                item(key = "group-" + group.title) {
                    SegmentedColumn(title = group.title) {
                        group.items.forEach { rom ->
                            item(key = rom.rowId) { _ ->
                                RomRow(
                                    rom = rom,
                                    pickMode = false,
                                    versionMode = true,
                                    onSelectVersion = { viewModel.dispatch(RomVersionAction.Select(rom.rowId)) },
                                    onOpen = { navigator.push(Route.RomDetail(rom.rowId, false)) },
                                    onDelete = { romListViewModel.dispatch(RomListAction.RequestDelete(rom.rowId)) },
                                    onCheck = { romListViewModel.dispatch(RomListAction.CheckRom(rom.rowId)) },
                                    onRepair = { romListViewModel.dispatch(RomListAction.RequestRepair(rom.rowId)) },
                                    onClearCache = { romListViewModel.dispatch(RomListAction.RequestClearCache(rom.rowId)) },
                                    onCreate = {},
                                    onPick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    RomOperationDialogs(romListState, romListViewModel::dispatch, backdrop)
    LaunchedEffect(romListState.message) {
        val message = romListState.message ?: return@LaunchedEffect
        romListViewModel.dispatch(RomListAction.ConsumeMessage)
        snackbarHostState.showSnackbar(message)
    }

    state.confirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { viewModel.dispatch(RomVersionAction.CancelSwitch) },
            title = { Text(confirm.title) },
            text = { Text(confirm.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dispatch(RomVersionAction.ConfirmSwitch) }) {
                    Text(confirm.confirmText)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dispatch(RomVersionAction.CancelSwitch) }) { Text("取消") }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    if (state.restartPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dispatch(RomVersionAction.CancelRestart) },
            title = { Text(RomTexts.RESTART_TITLE) },
            text = { Text(RomTexts.RESTART_MESSAGE) },
            confirmButton = {
                TextButton(onClick = { viewModel.dispatch(RomVersionAction.ConfirmRestart) }) {
                    Text(RomTexts.RESTART_CONFIRM)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dispatch(RomVersionAction.CancelRestart) }) {
                    Text(RomTexts.RESTART_LATER)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    BlockingLoadingIndicator(visible = state.working, text = RomTexts.IN_PROGRESS, backdrop = backdrop)

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dispatch(RomVersionAction.DismissError) },
            title = { Text(RomTexts.VERSION_PAGE_TITLE) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dispatch(RomVersionAction.DismissError) }) { Text("好") }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.dispatch(RomVersionAction.ConsumeMessage)
        snackbarHostState.showSnackbar(message)
    }
}