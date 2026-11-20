// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import me.kanadex.geometryos.display.VmDisplayTexts
import me.kanadex.geometryos.display.android.VmDisplayAction
import me.kanadex.geometryos.display.android.rememberVmDisplayViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分辨率（Miuix 分支）。
 *
 * 版式与「新建虚拟机 → 安卓版本」的选择页**逐项相同**：页面标题就是「分辨率」，
 * 一个 [miuixSection] 里若干 [BasicComponent]，选中项在 `endActions` 打一个库的 [Icon] 勾。
 * 没有额外说明文字、没有分组副标题、没有自己拼的行。
 *
 * 两个分支共用 [me.kanadex.geometryos.display.android.VmDisplayViewModel]：
 * 页面只 `collectAsStateWithLifecycle()` + `dispatch(...)`（docs/ui-rework/02 §3）。
 */
@Composable
fun MiuixVmDisplayPage(id: String, useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appState = LocalAppState.current
    val viewModel = rememberVmDisplayViewModel(id)
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 关机收尾完成：**先**退出两级页面并回到主页虚拟机 tab，**再**按标准入口启动。
    // 顺序不能反：先启动会把启动页盖在本页之上，用户按返回回到的是一张已经没用的设置页。
    // 也不在这里接管启动过程——启动页（FullscreenActivity）必须是用户看到的那一屏
    // （docs/display-settings/02 §3）。
    LaunchedEffect(state.restartCompleted) {
        if (!state.restartCompleted) return@LaunchedEffect
        viewModel.dispatch(VmDisplayAction.ConsumeRestartCompleted)
        navigator.popUntil { key -> key is Route.Main }
        appState.requestVmTab()
        val device = appState.device(id) ?: return@LaunchedEffect
        VmActions.launch(context, appState, device, appState.runtime(id))
    }

    MiuixSettingsScaffold(
        title = VmDisplayTexts.TITLE,
        enableBlur = useBlur,
        showBackButton = true,
    ) {
        miuixSection {
            state.options.forEach { option ->
                BasicComponent(
                    title = option.title,
                    summary = option.summary,
                    onClick = { viewModel.dispatch(VmDisplayAction.Select(option.mode)) },
                    endActions = {
                        if (option.selected) {
                            Icon(
                                imageVector = AppMiuixIcons.Ok,
                                contentDescription = "已选择",
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }

    // 「重启」：标题/正文/积极/消极按钮全部来自策略库，两个分支一字不差。
    MiuixConfirmDialog(
        show = state.restartPrompt,
        title = VmDisplayTexts.RESTART_TITLE,
        message = VmDisplayTexts.RESTART_MESSAGE,
        confirmText = VmDisplayTexts.RESTART_CONFIRM,
        dismissText = VmDisplayTexts.RESTART_LATER,
        onConfirm = { viewModel.dispatch(VmDisplayAction.ConfirmRestart) },
        onDismissRequest = { viewModel.dispatch(VmDisplayAction.CancelRestart) },
    )

    // 关机收尾的加载框：文案只说「正在进行中，请稍候...」，不冒充启动进度。
    MiuixBlockingLoadingDialog(visible = state.working, text = VmDisplayTexts.IN_PROGRESS)

    state.error?.let { message ->
        MiuixNoticeDialog(
            show = true,
            title = VmDisplayTexts.TITLE,
            message = message,
            confirmText = "好",
            onDismissRequest = { viewModel.dispatch(VmDisplayAction.DismissError) },
        )
    }
}
