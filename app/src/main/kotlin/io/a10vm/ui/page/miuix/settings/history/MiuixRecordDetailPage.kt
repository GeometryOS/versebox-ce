// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.settings.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.records.VmRecord
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.icons.AppMiuixIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.miuix.widgets.MiuixBackButton
import io.a10vm.ui.page.miuix.widgets.MiuixBadge
import io.a10vm.ui.records.RecordDetailAction
import io.a10vm.ui.records.rememberRecordDetailViewModel
import io.a10vm.ui.page.miuix.widgets.MiuixConfirmDialog
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.miuixCardInset
import io.a10vm.ui.theme.getMiuixAppBarColor
import io.a10vm.ui.theme.rememberMiuixBlurBackdrop
import io.a10vm.ui.theme.verseBoxMiuixBlurEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 记录详情（Miuix 分支）。**不是**把日志原文糊一屏：
 *
 * - 上半是概览：哪台虚拟机、什么事件、成败、一句话原因、ROM / Android、软件版本、时间与耗时；
 * - 中间是「关键节点」：只挑 BOOT / PREPARE / ART / GPU / READY 这类里程碑；
 * - 操作：导出到 Download/VerseBox/records、分享这个 zip、看日志原文（分文件）、删除这一条。
 *
 * 数据与导出都在 RecordDetailViewModel 里，
 * 页面自己不拼路径、不读文件 —— 这是「日志代码与 UI 解耦」的落点。
 */
@Composable
fun MiuixRecordDetailPage(id: Long, useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val viewModel = rememberRecordDetailViewModel(id)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val record: VmRecord? = state.record
    val milestones = state.milestones
    val message = state.message

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberMiuixBlurBackdrop(useBlur)
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()

    var deleteConfirm by remember { mutableStateOf(false) }

    // 导出交给系统文件选择器；选完把 uri 还给 ViewModel 落盘。
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> viewModel.dispatch(RecordDetailAction.Export(uri)) }

    MiuixSettingsScaffold(
        title = "记录详情",
        enableBlur = useBlur,
        showBackButton = true,
        topBarActions = {
            IconButton(onClick = { deleteConfirm = true }) {
                Icon(imageVector = AppMiuixIcons.Delete, contentDescription = "删除")
            }
            // 分享直接开系统面板，系统面板本身就是确认
            IconButton(
                enabled = state.entries.isNotEmpty(),
                onClick = { viewModel.dispatch(RecordDetailAction.Share) },
            ) {
                Icon(imageVector = AppIcons.Share, contentDescription = "分享")
            }
            IconButton(
                enabled = state.entries.isNotEmpty(),
                onClick = { viewModel.exportName()?.let(exportLauncher::launch) },
            ) {
                Icon(imageVector = AppIcons.Download, contentDescription = "导出")
            }
        },
    ) {
        if (record == null) {
            // 读盘在 IO 上，首帧 record 还是 null：不要画「记录不存在」，也不要往下走（下面会解引用 record）。
            if (!state.isLoading) {
                item {
                    Card(modifier = Modifier.miuixCardInset()) {
                        BasicComponent(title = "记录不存在", summary = "可能已经被删除或清理")
                    }
                }
            }
            return@MiuixSettingsScaffold
        }

        // 结论卡片：字段与 M3 版完全对齐，不再使用 headline 作为标题。
        item {
            Card(modifier = Modifier.miuixCardInset()) {
                BasicComponent(title = "结论", summary = record.status.label + record.kind.label)
                BasicComponent(title = "设备", summary = record.vmName)
                BasicComponent(
                    title = "ROM",
                    summary = (record.romName ?: record.romId) + " · API " + record.apiLevel,
                )
                BasicComponent(
                    title = "概览",
                    summary = formatTime(record.startedAt) + "，耗时 " + record.durationText +
                            "，" + record.eventCount + " 条事件",
                )
                BasicComponent(
                    title = "宿主机",
                    summary = record.appVersion + "/" + record.hostModel + "/" + record.hostRelease,
                )
            }
        }

        // 证据完整性（docs/log-audit/03 §5）：徽章 + 一行说明 + 缺什么。
        // 数据全部来自 manifest.json，页面只渲染 RecordEvidence 的结论。
        item {
            val tone = when (state.evidence.complete) {
                true -> MiuixTheme.colorScheme.primary
                false -> MiuixTheme.colorScheme.error
                null -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Card(
                modifier = Modifier.miuixCardInset(),
                insideMargin = BasicComponentDefaults.InsideMargin,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiuixBadge(
                        text = state.evidence.label,
                        textColor = tone,
                        containerColor = tone.copy(alpha = 0.2f),
                    )
                    Text(
                        modifier = Modifier.weight(1f),
                        text = state.evidence.summary,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        if (state.evidence.missing.isNotEmpty()) {
            item { SmallTitle("缺失的证据") }
            item {
                Card(modifier = Modifier.miuixCardInset()) {
                    state.evidence.missing.forEach { missing ->
                        BasicComponent(
                            title = missing.path,
                            summary = state.evidence.reasonText(missing.reason),
                        )
                    }
                }
            }
        }

        if (milestones.isNotEmpty()) {
            item { SmallTitle("关键节点") }
            item {
                Card(modifier = Modifier.miuixCardInset()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        milestones.forEach { line ->
                            Text(
                                text = line,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        item { SmallTitle("日志原文") }
        item {
            Card(modifier = Modifier.miuixCardInset()) {
                val entries = state.entries
                if (entries.isEmpty()) {
                    BasicComponent(title = "没有归档", summary = "这条记录没有留下日志文件")
                } else {
                    entries.forEach { entry ->
                        BasicComponent(
                            title = entry.path,
                            summary = state.evidence.entrySummary(entry.bytes, entry.originalBytes, entry.truncated),
                            onClick = { navigator.push(Route.RecordLog(record.id, entry.path)) },
                        )
                    }
                }
            }
        }

        message?.let { text ->
            item {
                Card(modifier = Modifier.miuixCardInset()) {
                    BasicComponent(title = text, summary = "")
                }
            }
        }
    }

    MiuixConfirmDialog(
        show = deleteConfirm,
        title = "删除记录",
        message = "这条记录及其归档日志会被删除，无法恢复。",
        confirmText = "删除",
        onConfirm = {
            deleteConfirm = false
            viewModel.dispatch(RecordDetailAction.Delete)
            navigator.pop()
        },
        onDismissRequest = { deleteConfirm = false },
    )
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))