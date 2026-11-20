// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.settings.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.records.VmRecord
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.records.RecordDetailAction
import io.a10vm.ui.records.rememberRecordDetailViewModel
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.main.widget.chip.CapsuleTag
import io.a10vm.ui.page.main.widget.setting.NavigationItemWidget
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.blur.layerBackdrop

/** 记录详情（Material 3 分支）：概览 + 关键节点 + 导出分享 + 原文预览 + 删除。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RecordDetailPage(id: Long, useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val viewModel = rememberRecordDetailViewModel(id)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val record: VmRecord? = state.record
    val milestones = state.milestones
    val message = state.message

    var deleteConfirm by remember { mutableStateOf(false) }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> viewModel.dispatch(RecordDetailAction.Export(uri)) }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = rememberMaterial3BlurBackdrop(useBlur)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.verseBoxMaterial3BlurEffect(backdrop),
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text("记录详情") },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(AppIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { deleteConfirm = true }) {
                        Icon(AppIcons.Delete, contentDescription = "删除")
                    }
                    IconButton(
                        enabled = state.entries.isNotEmpty(),
                        onClick = { viewModel.dispatch(RecordDetailAction.Share) },
                    ) {
                        Icon(AppIcons.Share, contentDescription = "分享")
                    }
                    IconButton(
                        enabled = state.entries.isNotEmpty(),
                        onClick = { viewModel.exportName()?.let(exportLauncher::launch) },
                    ) {
                        Icon(AppIcons.Download, contentDescription = "导出")
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
            modifier = Modifier
                .fillMaxSize()
                .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
        ) {
            if (record == null) {
                // 读盘在 IO 上，首帧 record 还是 null：不要画「记录不存在」，也不要往下走（下面会解引用 record）。
                if (!state.isLoading) {
                    item {
                        Text(
                            "记录不存在（可能已经被删除）",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                return@LazyColumn
            }
            item {
                SegmentedColumn() {
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Info,
                           // title = record.headline,
                            title = "结论",
                            description = record.status.label + record.kind.label,
                            onClick = {},
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Launcher,
                            title = "设备",
                            description = record.vmName,
                            onClick = {},
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Android,
                            title = "ROM",
                            description = (record.romName ?: record.romId) + " · API " + record.apiLevel,
                            onClick = {},
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.StopWatch,
                            title = "概览",
                            description = formatTime(record.startedAt) + "，耗时 " + record.durationText +
                                "，" + record.eventCount + " 条事件",
                            onClick = {},
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Info,
                            title = "宿主机",
                            description = record.appVersion + "/" + record.hostModel + "/" + record.hostRelease,
                            onClick = {},
                        )
                    }
                }
            }
            // 证据完整性（docs/log-audit/03 §5）：徽章 + 一行说明，缺什么单独一组。
            // 结论全部来自 manifest.json，页面只渲染 RecordEvidence。
            item {
                val tone = when (state.evidence.complete) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CapsuleTag(
                            text = state.evidence.label,
                            containerColor = tone.copy(alpha = 0.2f),
                            contentColor = tone,
                        )
                        Text(
                            modifier = Modifier.weight(1f),
                            text = state.evidence.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.evidence.missing.isNotEmpty()) {
                item {
                    SegmentedColumn(title = "缺失的证据") {
                        state.evidence.missing.forEach { missing ->
                            item {
                                NavigationItemWidget(
                                    icon = AppIcons.Info,
                                    title = missing.path,
                                    description = state.evidence.reasonText(missing.reason),
                                    onClick = {},
                                )
                            }
                        }
                    }
                }
            }

            if (milestones.isNotEmpty()) {
                item {
                    SegmentedColumn(title = "关键节点") {
                        milestones.forEach { line ->
                            item {
                                NavigationItemWidget(
                                    icon = AppIcons.Check,
                                    title = line,
                                    description = "",
                                    onClick = {},
                                )
                            }
                        }
                    }
                }
            }
            item {
                val entries = state.entries
                SegmentedColumn(title = "日志原文") {
                    if (entries.isEmpty()) {
                        item {
                            NavigationItemWidget(
                                icon = AppIcons.Terminal,
                                title = "没有归档",
                                description = "这条记录没有留下日志文件",
                                onClick = {},
                            )
                        }
                    } else {
                        entries.forEach { entry ->
                            item {
                                NavigationItemWidget(
                                    icon = AppIcons.Terminal,
                                    title = entry.path,
                                    description = state.evidence.entrySummary(entry.bytes, entry.originalBytes, entry.truncated),
                                    onClick = { navigator.push(Route.RecordLog(record.id, entry.path)) },
                                )
                            }
                        }
                    }
                }
            }
            message?.let { text ->
                item {
                    Text(
                        text = text,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    // M3 的 AlertDialog 没有 show 参数：必须自己守住开关，否则一进页面就弹。
    if (record != null && deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("删除记录") },
            text = { Text("这条记录及其归档日志会被删除，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirm = false
                        viewModel.dispatch(RecordDetailAction.Delete)
                        navigator.pop()
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } },
        )
    }

}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
