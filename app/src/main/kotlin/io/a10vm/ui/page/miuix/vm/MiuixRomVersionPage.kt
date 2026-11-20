// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.vm

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
import io.a10vm.ui.page.miuix.widgets.miuixSection
import io.a10vm.ui.vm.VmActions
import io.a10vm.ui.verse.LocalAppState
import me.kanadex.geometryos.rom.RomTexts
import me.kanadex.geometryos.rom.android.RomVersionAction
import me.kanadex.geometryos.rom.android.rememberRomVersionViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * ROM 版本管理（Miuix 分支）。
 *
 * 版式与「分辨率」页**逐项相同**（`docs/rom-package/14-版本管理/01` §2）：
 *  - 顶部单独一档「使用最新版本」；
 *  - 下面一档是同一个 ROM 系列的全部版本（标题带版本号：`Android 15 V1.0.1`）；
 *  - 选中项在 `endActions` 打一个库的 [Icon] 勾；没有自造行、没有自造选中态。
 *
 * 页面只渲染 + `dispatch(...)`：切哪个版本、要不要警告、要不要重启全部由
 * [me.kanadex.geometryos.rom.android.RomVersionViewModel] 与 `:vm-rom` 的策略决定。
 */
@Composable
fun MiuixRomVersionPage(id: String, useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appState = LocalAppState.current
    val viewModel = rememberRomVersionViewModel(id)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
            miuixSection {
                BasicComponent(
                    title = RomTexts.MISSING_VERSION_TITLE,
                    summary = RomTexts.missingVersionSummary(
                        state.currentText,
                        state.latest?.takeIf { state.latestNewer }?.title,
                    ),
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

        // 下面一档：同系列的全部版本。
        miuixSection(RomTexts.versionGroupTitle(state.romName)) {
            when {
                state.isLoading -> BasicComponent(
                    title = "正在读取版本…",
                    summary = state.currentText,
                )

                state.isEmpty -> BasicComponent(
                    title = "没有可用的版本",
                    summary = "先导入这个系列的 ROM 包",
                    // 直接叠一层 ROM 列表：返回还能回到版本管理页（不是被扔回主页）。
                    onClick = { navigator.push(Route.RomList()) },
                )

                else -> state.options.forEach { option ->
                    BasicComponent(
                        title = option.title,
                        summary = option.summary,
                        enabled = option.enabled || option.selected,
                        onClick = { viewModel.dispatch(RomVersionAction.Select(option.rowId)) },
                        endActions = {
                            if (option.selected) {
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
        }
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