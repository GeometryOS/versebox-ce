// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.settings.history

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.kanadex.geometryos.audit.model.AuditState
import io.a10vm.records.RecordKind
import io.a10vm.records.RecordStatus
import io.a10vm.records.VmRecord
import io.a10vm.ui.icons.AppMiuixIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.records.HistoryAction
import io.a10vm.ui.records.rememberHistoryViewModel
import io.a10vm.ui.page.miuix.widgets.MiuixBadge
import io.a10vm.ui.page.miuix.widgets.MiuixConfirmDialog
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.miuixCardInset
import io.a10vm.ui.page.miuix.widgets.miuixSection
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * 记录中心（Miuix 分支）。
 *
 * 只做两件事：把 HistoryState 画出来、把操作转成 HistoryAction。
 * 查询 / 筛选 / 删除都在 HistoryViewModel 里，与 Material 3 分支共用同一份实现。
 */
@Composable
fun MiuixHistoryPage(
    enableBlur: Boolean,
    title: String,
    outerPadding: PaddingValues,
) {
    val navigator = LocalNavigator.current
    val viewModel = rememberHistoryViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val devices = state.devices

    var filterPopup by remember { mutableStateOf(false) }
    var statusPopup by remember { mutableStateOf(false) }
    var kindPopup by remember { mutableStateOf(false) }
    var vmPopup by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var cleanupConfirm by remember { mutableStateOf(false) }

    val records = state.records
    // 定位用的列表状态必须由页面持有，才能「滚到某一行」（09/05 §2）。
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 定位：滚到该条（留一行上下文，不贴顶），然后由卡片自己闪两下。
    LaunchedEffect(state.focusRecordId, state.blinkToken) {
        val target = state.focusRecordId ?: return@LaunchedEffect
        val index = records.indexOfFirst { it.id == target }
        if (index < 0) return@LaunchedEffect
        listState.animateScrollToItem((index - 1).coerceAtLeast(0))
    }

    // 该条已被清理：回落到 tab 顶部 + 一条 Snackbar（09/05 §5）。
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.dispatch(HistoryAction.ConsumeMessage)
        snackbarHostState.showSnackbar(message)
    }

    MiuixSettingsScaffold(
        title = title,
        enableBlur = enableBlur,
        outerPadding = outerPadding,
        listState = listState,
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBarActions = {
            IconButton(enabled = records.isNotEmpty(), onClick = { clearConfirm = true }) {
                Icon(imageVector = AppMiuixIcons.Delete, contentDescription = "清空记录")
            }
            Box {
                IconButton(
                    onClick = { filterPopup = true },
                    holdDownState = filterPopup || statusPopup || kindPopup || vmPopup,
                ) {
                    Icon(imageVector = AppMiuixIcons.Tune, contentDescription = "筛选")
                }
                WindowListPopup(
                    show = filterPopup,
                    alignment = PopupPositionProvider.Align.End,
                    onDismissRequest = { filterPopup = false },
                ) {
                    ListPopupColumn {
                        MenuRow(
                            label = "结果",
                            summary = state.status?.label ?: "全部结果",
                            isFirst = true,
                            onClick = {
                                filterPopup = false
                                statusPopup = true
                            },
                        )
                        MenuRow(
                            label = "事件",
                            summary = state.kind?.label ?: "全部事件",
                            onClick = {
                                filterPopup = false
                                kindPopup = true
                            },
                        )
                        MenuRow(
                            label = "虚拟机",
                            summary = devices.firstOrNull { it.id == state.vmId }?.name ?: "全部虚拟机",
                            isLast = true,
                            onClick = {
                                filterPopup = false
                                vmPopup = true
                            },
                        )
                    }
                }
                WindowListPopup(
                    show = statusPopup,
                    alignment = PopupPositionProvider.Align.End,
                    onDismissRequest = { statusPopup = false },
                ) {
                    CheckedMenu(
                        buildList {
                            add(
                                MenuOption("全部结果", state.status == null) {
                                    viewModel.dispatch(HistoryAction.SetStatus(null))
                                    statusPopup = false
                                },
                            )
                            RecordStatus.entries.forEach { entry ->
                                add(
                                    MenuOption(entry.label, state.status == entry) {
                                        viewModel.dispatch(HistoryAction.SetStatus(entry))
                                        statusPopup = false
                                    },
                                )
                            }
                        },
                    )
                }
                WindowListPopup(
                    show = kindPopup,
                    alignment = PopupPositionProvider.Align.End,
                    onDismissRequest = { kindPopup = false },
                ) {
                    CheckedMenu(
                        buildList {
                            add(
                                MenuOption("全部事件", state.kind == null) {
                                    viewModel.dispatch(HistoryAction.SetKind(null))
                                    kindPopup = false
                                },
                            )
                            RecordKind.entries.forEach { entry ->
                                add(
                                    MenuOption(entry.label, state.kind == entry) {
                                        viewModel.dispatch(HistoryAction.SetKind(entry))
                                        kindPopup = false
                                    },
                                )
                            }
                        },
                    )
                }
                WindowListPopup(
                    show = vmPopup,
                    alignment = PopupPositionProvider.Align.End,
                    onDismissRequest = { vmPopup = false },
                ) {
                    CheckedMenu(
                        buildList {
                            add(
                                MenuOption("全部虚拟机", state.vmId == null) {
                                    viewModel.dispatch(HistoryAction.SetVm(null))
                                    vmPopup = false
                                },
                            )
                            devices.forEach { device ->
                                add(
                                    MenuOption(device.name, state.vmId == device.id) {
                                        viewModel.dispatch(HistoryAction.SetVm(device.id))
                                        vmPopup = false
                                    },
                                )
                            }
                        },
                    )
                }
            }
        },
    ) {
        // 容量视图（docs/log-audit/02 §5）：唯一真相是 AuditState，页面不 du、不算阈值。
        miuixSection("归档") {
            BasicComponent(
                title = "归档占用",
                summary = AuditState.formatMegabytes(state.audit.archiveBytes) +
                    " / 上限 " + AuditState.formatMegabytes(state.audit.policy.archiveHardCap) +
                    (if (state.audit.protectedCount > 0) " · 已保护 ${state.audit.protectedCount} 条失败记录" else ""),
            )
            if (state.audit.pendingCleanup) {
                BasicComponent(
                    title = "清理成功记录",
                    summary = state.audit.pendingLabel.orEmpty(),
                    onClick = { cleanupConfirm = true },
                )
            }
        }

        if (!state.isLoading && records.isEmpty()) {
            miuixSection {
                BasicComponent(
                    title = "这里还是空的",
                    summary = "启动一次虚拟机，成败与原因就会出现在这里",
                )
            }
        } else {
            items(records, key = { it.id }) { record ->
                HistoryRecordCard(
                    record = record,
                    highlight = state.focusRecordId == record.id,
                    onHighlightFinished = { viewModel.dispatch(HistoryAction.ConsumeFocus) },
                    onClick = { navigator.push(Route.RecordDetail(record.id)) },
                )
            }
        }
    }

    MiuixConfirmDialog(
        show = cleanupConfirm,
        title = "清理成功记录",
        message = "只删除成功的记录及其归档，失败与崩溃记录会保留。",
        confirmText = "清理",
        onConfirm = {
            cleanupConfirm = false
            viewModel.dispatch(HistoryAction.CleanupArchives(confirm = true))
        },
        onDismissRequest = { cleanupConfirm = false },
    )

    MiuixConfirmDialog(
        show = clearConfirm,
        title = "清空记录",
        message = "将删除全部 ${records.size} 条记录及其归档日志，无法恢复。",
        confirmText = "清空",
        onConfirm = {
            clearConfirm = false
            viewModel.dispatch(HistoryAction.Delete(records.map { it.id }))
        },
        onDismissRequest = { clearConfirm = false },
    )
}

private class MenuOption(val label: String, val checked: Boolean, val onClick: () -> Unit)

@Composable
private fun CheckedMenu(options: List<MenuOption>) {
    ListPopupColumn {
        options.forEachIndexed { index, option ->
            MenuRowShell(
                isFirst = index == 0,
                isLast = index == options.lastIndex,
                checked = option.checked,
                onClick = option.onClick,
            ) {
                Text(
                    modifier = Modifier.widthIn(max = 216.dp),
                    text = option.label,
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = if (option.checked) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceContainer
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CheckIcon(visible = option.checked)
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    summary: String,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onClick: () -> Unit,
) {
    MenuRowShell(isFirst = isFirst, isLast = isLast, checked = false, onClick = onClick) {
        Column(modifier = Modifier.widthIn(max = 216.dp)) {
            Text(
                text = label,
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
            Text(
                text = summary,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        CheckIcon(visible = false)
    }
}

@Composable
private fun CheckIcon(visible: Boolean) {
    Icon(
        modifier = Modifier
            .padding(start = 12.dp)
            .size(20.dp),
        imageVector = AppMiuixIcons.Ok,
        contentDescription = null,
        tint = if (visible) MiuixTheme.colorScheme.primary else Color.Transparent,
    )
}

/** 菜单行外壳：选中底色、可选中语义、20dp 行内边距、首末行 20dp 上下内边距。 */
@Composable
private fun MenuRowShell(
    isFirst: Boolean,
    isLast: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (checked) MiuixTheme.colorScheme.surfaceContainer else Color.Transparent)
            .selectable(selected = checked, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp)
            .padding(
                top = if (isFirst) 20.dp else 12.dp,
                bottom = if (isLast) 20.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content,
    )
}

/**
 * 一条记录卡片。
 *
 * [highlight] 为 true 时按 `docs/ui-rework/09/05` §3 做「2 × 400 ms 的正弦 alpha 脉冲」：
 * 行本身仍是库的 [Card]，只有 alpha 由 [Animatable] 驱动——这属于动画，不是自造控件。
 * 系统动画关闭时 Compose 会让 animateTo 立即到位，于是「只滚不闪」，不需要另写分支。
 */
@Composable
private fun HistoryRecordCard(
    record: VmRecord,
    highlight: Boolean = false,
    onHighlightFinished: () -> Unit = {},
    onClick: () -> Unit,
) {
    val statusColor = when (record.status) {
        RecordStatus.SUCCESS -> MiuixTheme.colorScheme.primary
        RecordStatus.FAILED -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(highlight) {
        if (!highlight) return@LaunchedEffect
        repeat(2) {
            pulse.animateTo(0.35f, animationSpec = tween(durationMillis = 400))
            pulse.animateTo(1f, animationSpec = tween(durationMillis = 400))
        }
        onHighlightFinished()
    }

    Card(
        modifier = Modifier
            .miuixCardInset()
            .graphicsLayer { alpha = pulse.value },
        insideMargin = PaddingValues(16.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f, fill = false),
                    text = record.vmName,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                MiuixBadge(
                    text = record.kind.label,
                    textColor = MiuixTheme.colorScheme.primary,
                    containerColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.2f),
                )
            }
            Text(
                modifier = Modifier.padding(start = 12.dp),
                text = record.status.label,
                style = MiuixTheme.textStyles.subtitle,
                color = statusColor,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.size(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = record.headline,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                modifier = Modifier.padding(start = 12.dp),
                text = formatHistoryTime(record.startedAt),
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

private fun formatHistoryTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
