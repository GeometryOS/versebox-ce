// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.settings.history

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDropdownMenuItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.kanadex.geometryos.audit.model.AuditState
import io.a10vm.records.RecordKind
import io.a10vm.records.RecordStatus
import io.a10vm.records.VmRecord
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.records.HistoryAction
import io.a10vm.ui.records.rememberHistoryViewModel
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.main.widget.chip.CapsuleTag
import io.a10vm.ui.page.main.widget.menu.GroupedDropdownMenuPopup
import io.a10vm.ui.page.main.widget.setting.NavigationItemWidget
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import io.a10vm.ui.theme.bottomShape
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.middleShape
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.singleShape
import io.a10vm.ui.theme.topShape
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * 记录中心（Material 3 Expressive 分支）。
 *
 * 右上角是删除与筛选；筛选菜单挂在图标自己的 Box 上。
 * 每条记录一张卡，相邻卡片用圆角拼接：第一行「虚拟机名 + 操作类型标签 … 结果」，
 * 第二行「一行结论 … 时间」，名字与结论都单行省略。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HistoryPage(
    useBlur: Boolean,
    sharedBackdrop: LayerBackdrop? = null,
    title: String,
    outerPadding: PaddingValues,
) {
    val navigator = LocalNavigator.current
    val viewModel = rememberHistoryViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status = state.status
    val kind = state.kind
    val vmId = state.vmId
    val devices = state.devices

    var filterMenu by remember { mutableStateOf(false) }
    var subMenu by remember { mutableStateOf<String?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var cleanupConfirm by remember { mutableStateOf(false) }

    val records = state.records
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 定位：滚到该条（留一行上下文），再由卡片自己闪两下（09/05 §2/§3）。
    LaunchedEffect(state.focusRecordId, state.blinkToken) {
        val target = state.focusRecordId ?: return@LaunchedEffect
        val index = records.indexOfFirst { it.id == target }
        if (index < 0) return@LaunchedEffect
        listState.animateScrollToItem((index - 1).coerceAtLeast(0))
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.dispatch(HistoryAction.ConsumeMessage)
        snackbarHostState.showSnackbar(message)
    }

    // 二级菜单的候选项：三个维度共用一套渲染，选中态交给 SelectableDropdownMenuItem 画对勾。
    val subOptions: List<FilterOption> = when (subMenu) {
        "status" -> buildList {
            add(FilterOption("全部结果", status == null) { viewModel.dispatch(HistoryAction.SetStatus(null)) })
            RecordStatus.entries.forEach { entry ->
                add(FilterOption(entry.label, status == entry) { viewModel.dispatch(HistoryAction.SetStatus(entry)) })
            }
        }

        "kind" -> buildList {
            add(FilterOption("全部事件", kind == null) { viewModel.dispatch(HistoryAction.SetKind(null)) })
            RecordKind.entries.forEach { entry ->
                add(FilterOption(entry.label, kind == entry) { viewModel.dispatch(HistoryAction.SetKind(entry)) })
            }
        }

        "vm" -> buildList {
            add(FilterOption("全部虚拟机", vmId == null) { viewModel.dispatch(HistoryAction.SetVm(null)) })
            devices.forEach { device ->
                add(FilterOption(device.name, vmId == device.id) { viewModel.dispatch(HistoryAction.SetVm(device.id)) })
            }
        }

        else -> emptyList()
    }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = sharedBackdrop ?: rememberMaterial3BlurBackdrop(useBlur)
    val layoutDirection = LocalLayoutDirection.current

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
                title = { Text(text = title) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        enabled = records.isNotEmpty(),
                        onClick = { clearConfirm = true },
                    ) {
                        Icon(AppIcons.Delete, contentDescription = "清空记录")
                    }
                    Box {
                        IconButton(onClick = { filterMenu = true }) {
                            Icon(AppIcons.Filter, contentDescription = "筛选")
                        }
                        GroupedDropdownMenuPopup(
                            expanded = filterMenu,
                            onDismissRequest = { filterMenu = false },
                            groupSizes = listOf(3),
                        ) { _, index, shape, dismissItem ->
                            val label = when (index) {
                                0 -> "结果：" + (status?.label ?: "全部")
                                1 -> "事件：" + (kind?.label ?: "全部")
                                else -> "虚拟机：" + (devices.firstOrNull { it.id == vmId }?.name ?: "全部")
                            }
                            SelectableDropdownMenuItem(
                                selected = false,
                                onClick = {
                                    dismissItem()
                                    subMenu = when (index) {
                                        0 -> "status"
                                        1 -> "kind"
                                        else -> "vm"
                                    }
                                },
                                text = { Text(label) },
                                shapes = shape,
                            )
                        }
                        GroupedDropdownMenuPopup(
                            expanded = subMenu != null,
                            onDismissRequest = { subMenu = null },
                            groupSizes = listOf(subOptions.size),
                        ) { _, index, shape, dismissItem ->
                            val option = subOptions[index]
                            SelectableDropdownMenuItem(
                                selected = option.checked,
                                onClick = {
                                    option.onClick()
                                    dismissItem()
                                    subMenu = null
                                },
                                text = { Text(option.label) },
                                shapes = shape,
                            )
                        }
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
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
            contentPadding = PaddingValues(
                // 移除全局的 +16.dp 左右和上边距，交由各个 Item 自行控制，避免双重 Padding
                start = paddingValues.calculateStartPadding(layoutDirection) +
                        outerPadding.calculateStartPadding(layoutDirection),
                top = paddingValues.calculateTopPadding(),
                end = paddingValues.calculateEndPadding(layoutDirection) +
                        outerPadding.calculateEndPadding(layoutDirection),
                bottom = outerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 容量视图（docs/log-audit/02 §5）：唯一真相是 AuditState。
            item {
                SegmentedColumn(title = "归档") {
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Info,
                            title = "归档占用",
                            description = AuditState.formatMegabytes(state.audit.archiveBytes) +
                                    " / 上限 " + AuditState.formatMegabytes(state.audit.policy.archiveHardCap) +
                                    (if (state.audit.protectedCount > 0) {
                                        " · 已保护 ${state.audit.protectedCount} 条失败记录"
                                    } else {
                                        ""
                                    }),
                            onClick = {},
                        )
                    }
                    if (state.audit.pendingCleanup) {
                        item {
                            NavigationItemWidget(
                                icon = AppIcons.Delete,
                                title = "清理成功记录",
                                description = state.audit.pendingLabel.orEmpty(),
                                onClick = { cleanupConfirm = true },
                            )
                        }
                    }
                }
            }

            if (!state.isLoading && records.isEmpty()) {
                item {
                    // 边距对齐标准卡片
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("这里还是空的", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.padding(top = 8.dp))
                        Text(
                            "启动一次虚拟机，成败与原因就会出现在这里",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = records,
                    key = { _, record -> record.id },
                    contentType = { _, _ -> "record" },
                ) { index, record ->
                    val shape = when {
                        records.size == 1 -> singleShape
                        index == 0 -> topShape
                        index == records.lastIndex -> bottomShape
                        else -> middleShape
                    }
                    HistoryRecordCard(
                        record = record,
                        shape = shape,
                        highlight = state.focusRecordId == record.id,
                        onHighlightFinished = { viewModel.dispatch(HistoryAction.ConsumeFocus) },
                        // 卡片自身接管水平边距
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onClick = { navigator.push(Route.RecordDetail(record.id)) },
                    )
                }
            }
        }
    }

    if (cleanupConfirm) {
        AlertDialog(
            onDismissRequest = { cleanupConfirm = false },
            title = { Text("清理成功记录") },
            text = { Text("只删除成功的记录及其归档，失败与崩溃记录会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        cleanupConfirm = false
                        viewModel.dispatch(HistoryAction.CleanupArchives(confirm = true))
                    },
                ) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { cleanupConfirm = false }) { Text("取消") } },
        )
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("清空记录") },
            text = { Text("将删除全部 ${records.size} 条记录及其归档日志，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearConfirm = false
                        viewModel.dispatch(HistoryAction.Delete(records.map { it.id }))
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 二级菜单里的一项；选中态由 SelectableDropdownMenuItem 自己画对勾。 */
private class FilterOption(
    val label: String,
    val checked: Boolean,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HistoryRecordCard(
    record: VmRecord,
    shape: Shape,
    modifier: Modifier = Modifier, // 接收外部传入的 modifier
    highlight: Boolean = false,
    onHighlightFinished: () -> Unit = {},
    onClick: () -> Unit
) {
    val statusColor = when (record.status) {
        RecordStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        RecordStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 定位反馈：2 × 400 ms 的 alpha 脉冲（09/05 §3）。行本身仍是库的 Card，只有 alpha 由动画驱动。
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
        onClick = onClick,
        modifier = modifier.fillMaxWidth().graphicsLayer { alpha = pulse.value }, // 应用 modifier
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        shape = shape,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    CapsuleTag(
                        text = record.kind.label,
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    modifier = Modifier.padding(start = 12.dp),
                    text = record.status.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = record.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    modifier = Modifier.padding(start = 12.dp),
                    text = formatHistoryTime(record.startedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatHistoryTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))