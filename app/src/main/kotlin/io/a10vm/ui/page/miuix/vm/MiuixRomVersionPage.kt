// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.vm

import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppMiuixIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.miuix.widgets.MiuixBlockingLoadingDialog
import io.a10vm.ui.page.miuix.widgets.MiuixConfirmDialog
import io.a10vm.ui.page.miuix.widgets.MiuixNoticeDialog
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.MiuixStatusCard
import io.a10vm.ui.page.miuix.widgets.miuixCardInset
import io.a10vm.ui.page.miuix.widgets.miuixSection
import io.a10vm.ui.vm.VmActions
import io.a10vm.ui.verse.LocalAppState
import me.kanadex.geometryos.rom.RomListAction
import me.kanadex.geometryos.rom.rememberRomListViewModel
import top.yukonga.miuix.kmp.basic.SmallTitle
import me.kanadex.geometryos.rom.RomTexts
import me.kanadex.geometryos.rom.android.RomVersionAction
import me.kanadex.geometryos.rom.android.rememberRomVersionViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * ROM 版本管理（Miuix 分支）。版本行复用 ROM 管理卡片与 RomItem 状态；
 * 页面特有内容只有最新版本选择与缺失版本警告。
 */
@Composable
fun MiuixRomVersionPage(id: String, useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appState = LocalAppState.current
    val viewModel = rememberRomVersionViewModel(id)
    val romListViewModel = rememberRomListViewModel()
    val romListState by romListViewModel.state.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(romListState.rows) {
        viewModel.dispatch(RomVersionAction.Refresh)
    }

    // 关机收尾完成：**先**退出两级页面并回到主页虚拟机 tab，**再**按标准入口启动。
    // 顺序不能反（docs/display-settings/02 §3），也不在这里接管启动过程。
    LaunchedEffect(state.restartCompleted) {
        if (!state.restartCompleted) return@LaunchedEffect
        viewModel.dispatch(RomVersionAction.ConsumeRestartCompleted)
        navigator.popUntil { key -> key is Route.Main }
        appState.requestVmTab()
        val device = appState.device(id) ?: return@LaunchedEffect
        VmActions.launch(context, appState, device, appState.runtime(id))
    }

    // 选中的版本还不能用（没解包 / 解包体不全）：交给 ROM 列表的解包链。
    // 解完 ROM 列表会 requestVmRestart，用户回到的是**启动页**，不是这一页。
    LaunchedEffect(state.handoffKey) {
        val key = state.handoffKey ?: return@LaunchedEffect
        viewModel.dispatch(RomVersionAction.ConsumeHandoff)
        // 带 rowId 交给解包链：同版本可能有内置/本地两行，只给 romKey 会解错那一份。
        appState.focusRom(key, repairVmId = id, rowId = state.handoffRowId)
    }

    MiuixSettingsScaffold(
        title = RomTexts.VERSION_PAGE_TITLE,
        enableBlur = useBlur,
        showBackButton = true,
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
    ) {
        // 「为什么你会被带到这里」：当前版本已经不在了。这一行不可点，只说明事实与出路。
        if (state.selectedMissing) {
            item {
                MiuixStatusCard(
                    modifier = Modifier.miuixCardInset(),
                    title = RomTexts.MISSING_VERSION_TITLE,
                    description = RomTexts.missingVersionSummary(
                        state.currentText,
                        state.latest?.takeIf { state.latestNewer }?.title,
                    ),
                    containerColor = MiuixTheme.colorScheme.errorContainer,
                    contentColor = MiuixTheme.colorScheme.onErrorContainer,
                    iconTint = MiuixTheme.colorScheme.error,
                    actionText = "请选择一个仍然存在的 ROM 版本",
                    icon = AppMiuixIcons.Info,
                    onClick = {},
                )
            }
        }

        // 顶部单独一档：使用最新版本。
        state.latest?.let { latest ->
            miuixSection {
                BasicComponent(
                    title = latest.title,
                    summary = latest.summary,
                    enabled = latest.enabled,
                    onClick = { viewModel.dispatch(RomVersionAction.SelectLatest) },
                    endActions = {
                        if (latest.selected) {
                            Icon(
                                imageVector = AppMiuixIcons.Ok,
                                contentDescription = "正在使用",
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }

        if (state.isLoading || state.isEmpty) {
            miuixSection {
                BasicComponent(
                    title = if (state.isLoading) "正在读取版本…" else "没有可用的版本",
                    summary = if (state.isLoading) null else "先导入这个系列的 ROM 包",
                    enabled = !state.isLoading,
                    onClick = { navigator.push(Route.RomList()) },
                )
            }
        }
        state.romGroups.forEach { group ->
            item(key = "group-" + group.title) { SmallTitle(text = group.title) }
            items(group.items, key = { it.rowId }) { rom ->
                MiuixRomCard(
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

    MiuixRomOperationDialogs(romListState, romListViewModel::dispatch)
    LaunchedEffect(romListState.message) {
        val message = romListState.message ?: return@LaunchedEffect
        romListViewModel.dispatch(RomListAction.ConsumeMessage)
        snackbarHostState.showSnackbar(message)
    }

    // 同版本不同内容 / 降级：标准确认框（标题与正文来自 :vm-rom）。
    state.confirm?.let { confirm ->
        MiuixConfirmDialog(
            show = true,
            title = confirm.title,
            message = confirm.message,
            confirmText = confirm.confirmText,
            onConfirm = { viewModel.dispatch(RomVersionAction.ConfirmSwitch) },
            onDismissRequest = { viewModel.dispatch(RomVersionAction.CancelSwitch) },
        )
    }

    // 改完要重启才生效（运行中/开机中/关机中）。
    MiuixConfirmDialog(
        show = state.restartPrompt,
        title = RomTexts.RESTART_TITLE,
        message = RomTexts.RESTART_MESSAGE,
        confirmText = RomTexts.RESTART_CONFIRM,
        dismissText = RomTexts.RESTART_LATER,
        onConfirm = { viewModel.dispatch(RomVersionAction.ConfirmRestart) },
        onDismissRequest = { viewModel.dispatch(RomVersionAction.CancelRestart) },
    )

    // 解包 / 关机的加载框：文案固定，不冒充启动进度。
    MiuixBlockingLoadingDialog(visible = state.working, text = RomTexts.IN_PROGRESS)

    state.error?.let { message ->
        MiuixNoticeDialog(
            show = true,
            title = RomTexts.VERSION_PAGE_TITLE,
            message = message,
            confirmText = "好",
            onDismissRequest = { viewModel.dispatch(RomVersionAction.DismissError) },
        )
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.dispatch(RomVersionAction.ConsumeMessage)
        snackbarHostState.showSnackbar(message)
    }
}