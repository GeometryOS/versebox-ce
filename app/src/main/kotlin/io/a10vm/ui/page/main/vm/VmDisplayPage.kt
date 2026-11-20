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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import io.a10vm.ui.vm.VmActions
import io.a10vm.ui.verse.LocalAppState
import me.kanadex.geometryos.display.VmDisplayTexts
import me.kanadex.geometryos.display.android.VmDisplayAction
import me.kanadex.geometryos.display.android.rememberVmDisplayViewModel

/**
 * 分辨率（Material 3 Expressive 分支）。
 *
 * 单选列表用组件库的 [RadioButtonWidget]（`BaseWidget` + M3 `RadioButton`，语义 Role.RadioButton，
 * 对勾/圆点与底色都由库画）；页面不手写选择行、不自己画选中态。
 *
 * 与 Miuix 分支共用同一个 ViewModel：页面只渲染 + `dispatch(...)`。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VmDisplayPage(id: String, useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appState = LocalAppState.current
    val viewModel = rememberVmDisplayViewModel(id)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = rememberMaterial3BlurBackdrop(useBlur)

    // 时序与 Miuix 分支逐项相同：先退出两级页面 + 回到虚拟机 tab，再按标准入口启动。
    LaunchedEffect(state.restartCompleted) {
        if (!state.restartCompleted) return@LaunchedEffect
        viewModel.dispatch(VmDisplayAction.ConsumeRestartCompleted)
        navigator.popUntil { key -> key is Route.Main }
        appState.requestVmTab()
        val device = appState.device(id) ?: return@LaunchedEffect
        VmActions.launch(context, appState, device, appState.runtime(id))
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.verseBoxMaterial3BlurEffect(backdrop),
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(VmDisplayTexts.TITLE) },
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
            item {
                SegmentedColumn(title = VmDisplayTexts.TITLE) {
                    state.options.forEach { option ->
                        item(key = option.mode.token) {
                            RadioButtonWidget(
                                title = option.title,
                                description = option.summary,
                                selected = option.selected,
                                onClick = { viewModel.dispatch(VmDisplayAction.Select(option.mode)) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.restartPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dispatch(VmDisplayAction.CancelRestart) },
            title = { Text(VmDisplayTexts.RESTART_TITLE) },
            text = { Text(VmDisplayTexts.RESTART_MESSAGE) },
            confirmButton = {
                TextButton(onClick = { viewModel.dispatch(VmDisplayAction.ConfirmRestart) }) {
                    Text(VmDisplayTexts.RESTART_CONFIRM)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dispatch(VmDisplayAction.CancelRestart) }) {
                    Text(VmDisplayTexts.RESTART_LATER)
                }
            },
        )
    }

    BlockingLoadingIndicator(visible = state.working, text = VmDisplayTexts.IN_PROGRESS, backdrop = backdrop)

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dispatch(VmDisplayAction.DismissError) },
            title = { Text(VmDisplayTexts.TITLE) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dispatch(VmDisplayAction.DismissError) }) { Text("好") }
            },
        )
    }
}
