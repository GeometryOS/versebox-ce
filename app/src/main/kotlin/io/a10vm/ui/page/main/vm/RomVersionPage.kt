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
import io.a10vm.ui.page.main.widget.setting.BlockingLoadingIndicator
import io.a10vm.ui.page.main.widget.setting.RadioButtonWidget
import io.a10vm.ui.page.main.widget.setting.NavigationItemWidget
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import io.a10vm.ui.vm.VmActions
import io.a10vm.ui.verse.LocalAppState
import me.kanadex.geometryos.rom.RomTexts
import me.kanadex.geometryos.rom.android.RomVersionAction
import me.kanadex.geometryos.rom.android.rememberRomVersionViewModel

/**
 * ROM 版本管理（Material 3 Expressive 分支）。
 *
 * 与 Miuix 分支共用同一个 ViewModel 与同一份文案；
 * 单选列表用组件库的 [RadioButtonWidget]（BaseWidget + M3 RadioButton，语义与选中态都由库画），
 * 页面不手写选择行、不自己画对勾（`docs/ui-rework/01` §5.7）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RomVersionPage(id: String, useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appState = LocalAppState.current
    val viewModel = rememberRomVersionViewModel(id)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = rememberMaterial3BlurBackdrop(useBlur)

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
                    SegmentedColumn(title = RomTexts.VERSION_PAGE_TITLE) {
                        item {
                            NavigationItemWidget(
                                icon = AppIcons.Android,
                                title = RomTexts.MISSING_VERSION_TITLE,
                                description = RomTexts.missingVersionSummary(
                                    state.currentText,
                                    state.latest?.takeIf { state.latestNewer }?.title,
                                ),
                                onClick = {},
                            )
                        }
                    }
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

            item(key = "versions") {
                SegmentedColumn(title = RomTexts.versionGroupTitle(state.romName)) {
                    when {
                        state.isLoading -> item(key = "loading") {
                            RadioButtonWidget(
                                title = "正在读取版本…",
                                description = state.currentText,
                                selected = false,
                                enabled = false,
                                onClick = {},
                            )
                        }

                        state.isEmpty -> item(key = "empty") {
                            RadioButtonWidget(
                                title = "没有可用的版本",
                                description = "先导入这个系列的 ROM 包",
                                selected = false,
                                onClick = {
                                    navigator.popUntil { key -> key is Route.Main }
                                    navigator.push(Route.RomList())
                                },
                            )
                        }

                        else -> state.options.forEach { option ->
                            item(key = option.uiKey) {
                                RadioButtonWidget(
                                    title = option.title,
                                    description = option.summary,
                                    selected = option.selected,
                                    onClick = { viewModel.dispatch(RomVersionAction.Select(option.rowId)) },
                                )
                            }
                        }
                    }
                }
            }
        }
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