// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.vm

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.miuix.widgets.MiuixBlockingLoadingDialog
import io.a10vm.ui.page.miuix.widgets.MiuixConfirmDialog
import io.a10vm.ui.page.miuix.widgets.MiuixNoticeDialog
import io.a10vm.ui.page.miuix.widgets.MiuixPageLoading
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.miuixSection
import me.kanadex.geometryos.rom.RomDetailAction
import me.kanadex.geometryos.rom.RomListMode
import me.kanadex.geometryos.rom.RomTexts
import me.kanadex.geometryos.rom.rememberRomDetailViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * ROM 详情（Miuix 分支）。展示 ROM ViewModel 提供的真实字段，只向共享详情 ViewModel 发送 Action。
 * 元数据由 ROM repository 快照生成；页面不读文件、不查数据库，也不执行解包操作。
 */
@Composable
fun MiuixRomDetailPage(rowId: String, mode: RomListMode, useBlur: Boolean, backupId: String? = null) {
    val navigator = LocalNavigator.current
    val viewModel = rememberRomDetailViewModel(rowId, mode, backupId)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
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
    LaunchedEffect(state.createRoute) {
        val route = state.createRoute ?: return@LaunchedEffect
        viewModel.dispatch(RomDetailAction.ConsumeCreateRequest)
        navigator.push(route)
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.dispatch(RomDetailAction.ConsumeMessage)
        snackbarHostState.showSnackbar(message)
    }

    MiuixSettingsScaffold(
        title = RomTexts.DETAIL_PAGE_TITLE,
        enableBlur = useBlur,
        showBackButton = true,
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBarActions = {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    holdDownState = menuExpanded,
                ) {
                    Icon(imageVector = AppIcons.MoreVert, contentDescription = "ROM 操作")
                }
                WindowListPopup(
                    show = menuExpanded,
                    alignment = PopupPositionProvider.Align.End,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    ListPopupColumn {
                        if (actions.isEmpty()) {
                            BasicComponent(title = RomTexts.DETAIL_ACTION_NONE, enabled = false)
                        } else {
                            actions.forEach { (label, action) ->
                                BasicComponent(
                                    title = label,
                                    titleColor = BasicComponentDefaults.titleColor(
                                        color = if (action == RomDetailAction.Delete) MiuixTheme.colorScheme.error
                                        else MiuixTheme.colorScheme.onSurface,
                                    ),
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.dispatch(action)
                                    },
                                )
                            }
                        }
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
                    imageVector = if (state.mode == RomListMode.Manage) AppIcons.Add else AppIcons.Check,
                    contentDescription = if (state.mode == RomListMode.Manage) RomTexts.DETAIL_CREATE else RomTexts.DETAIL_PICK,
                )
            }
        },
    ) {
        when {
            state.isLoading && state.row == null -> item(key = "loading") {
                MiuixPageLoading(RomTexts.DETAIL_LOADING, Modifier.fillParentMaxSize())
            }
            state.row == null -> miuixSection(RomTexts.DETAIL_PAGE_TITLE) {
                BasicComponent(title = RomTexts.DETAIL_MISSING)
            }
            else -> state.sections.forEach { section ->
                miuixSection(section.title) {
                    section.facts.forEach { fact ->
                        BasicComponent(title = fact.label, summary = fact.value)
                    }
                }
            }
        }
    }

    state.confirm?.let { confirm ->
        MiuixConfirmDialog(
            show = true,
            title = confirm.title,
            message = confirm.message,
            confirmText = confirm.confirmText,
            onConfirm = { viewModel.dispatch(RomDetailAction.Confirm) },
            onDismissRequest = { viewModel.dispatch(RomDetailAction.Dismiss) },
        )
    }
    state.notice?.let { notice ->
        MiuixNoticeDialog(
            show = true,
            title = notice.title,
            message = notice.message,
            confirmText = notice.confirmText,
            onDismissRequest = { viewModel.dispatch(RomDetailAction.DismissNotice) },
        )
    }
    MiuixBlockingLoadingDialog(visible = state.busy, text = state.busyText)
}
