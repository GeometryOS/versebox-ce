// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.vm

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.main.widget.menu.GroupedDropdownMenuPopup
import io.a10vm.ui.page.main.widget.setting.BlockingLoadingIndicator
import io.a10vm.ui.page.main.widget.setting.BaseWidget
import io.a10vm.ui.page.main.widget.setting.PageLoadingIndicator
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import me.kanadex.geometryos.rom.RomDetailAction
import me.kanadex.geometryos.rom.RomListMode
import me.kanadex.geometryos.rom.RomTexts
import me.kanadex.geometryos.rom.rememberRomDetailViewModel
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * ROM 详情（Material 3 分支）。展示 ROM ViewModel 提供的真实字段，只向共享详情 ViewModel 发送 Action。
 * 元数据由 ROM repository 快照生成；页面不读文件、不查数据库，也不执行解包操作。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RomDetailPage(rowId: String, mode: RomListMode, useBlur: Boolean, backupId: String? = null) {
    val navigator = LocalNavigator.current
    val viewModel = rememberRomDetailViewModel(rowId, mode, backupId)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = rememberMaterial3BlurBackdrop(useBlur)
    val item = state.item
    val actions = buildList {
        if (item?.canCheck == true) add(RomTexts.DETAIL_ACTION_CHECK to RomDetailAction.Check)
        if (item?.canRepair == true) add(RomTexts.DETAIL_ACTION_REPAIR to RomDetailAction.Repair)
        if (item?.canDelete == true) add(RomTexts.DETAIL_ACTION_DELETE to RomDetailAction.Delete)
        if (item?.canClearCache == true) add(RomTexts.DETAIL_ACTION_CLEAR to RomDetailAction.ClearCache)
    }

    LaunchedEffect(state.pickedKey) {
        if (state.pickedKey == null) return@LaunchedEffect
        viewModel.dispatch(RomDetailAction.ConsumePicked)
        navigator.pop()
    }
    LaunchedEffect(state.createdVmId) {
        val id = state.createdVmId ?: return@LaunchedEffect
        viewModel.dispatch(RomDetailAction.ConsumeCreated)
        navigator.push(Route.VmManage(id))
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.dispatch(RomDetailAction.ConsumeMessage)
        snackbarHostState.showSnackbar(message)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.verseBoxMaterial3BlurEffect(backdrop),
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(RomTexts.DETAIL_PAGE_TITLE) },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(AppIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(AppIcons.MoreVert, contentDescription = "ROM 操作")
                        }
                        GroupedDropdownMenuPopup(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            groupSizes = listOf(actions.size.coerceAtLeast(1)),
                        ) { _, index, shape, dismissItem ->
                            if (actions.isEmpty()) {
                                SelectableDropdownMenuItem(
                                    selected = false,
                                    enabled = false,
                                    onClick = {},
                                    text = { Text(RomTexts.DETAIL_ACTION_NONE) },
                                    shapes = shape,
                                )
                            } else {
                                val (label, action) = actions[index]
                                SelectableDropdownMenuItem(
                                    selected = false,
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.dispatch(action)
                                        dismissItem()
                                    },
                                    text = { Text(label) },
                                    shapes = shape,
                                )
                            }
                        }
                    }
                    IconButton(
                        enabled = item?.canUse == true && !state.busy,
                        onClick = {
                            viewModel.dispatch(RomDetailAction.Primary)
                        },
                    ) {
                        Icon(
                            if (state.mode == RomListMode.Manage) AppIcons.Add else AppIcons.Check,
                            contentDescription = if (state.mode == RomListMode.Manage) RomTexts.DETAIL_CREATE else RomTexts.DETAIL_PICK,
                        )
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
            modifier = Modifier.fillMaxSize().then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
        ) {
            when {
                state.isLoading && state.row == null -> item(key = "loading") {
                    PageLoadingIndicator(RomTexts.DETAIL_LOADING, Modifier.fillParentMaxSize())
                }
                state.row == null -> item {
                    SegmentedColumn(title = RomTexts.DETAIL_PAGE_TITLE) {
                        item { BaseWidget(title = RomTexts.DETAIL_MISSING) }
                    }
                }
                else -> state.sections.forEach { section ->
                    item(key = section.title) {
                        SegmentedColumn(title = section.title) {
                            section.facts.forEach { fact ->
                                item(key = section.title + fact.label) {
                                    BaseWidget(title = fact.label, description = fact.value)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    state.confirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { viewModel.dispatch(RomDetailAction.Dismiss) },
            title = { Text(confirm.title) },
            text = { Text(confirm.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dispatch(RomDetailAction.Confirm) }) { Text(confirm.confirmText) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dispatch(RomDetailAction.Dismiss) }) { Text("取消") }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
    state.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = { viewModel.dispatch(RomDetailAction.DismissNotice) },
            title = { Text(notice.title) },
            text = { Text(notice.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dispatch(RomDetailAction.DismissNotice) }) { Text(notice.confirmText) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
    BlockingLoadingIndicator(visible = state.busy, text = state.busyText, backdrop = backdrop)
}
