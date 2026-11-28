// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup.ui.miuix

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.miuix.widgets.MiuixConfirmDialog
import io.a10vm.ui.page.miuix.widgets.MiuixPageLoading
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.miuixCardInset
import io.a10vm.ui.page.miuix.widgets.miuixSection
import java.util.UUID
import me.kanadex.geometryos.backup.BackupAction
import me.kanadex.geometryos.backup.BackupLibrary
import me.kanadex.geometryos.backup.LocalBackup
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 备份列表、选择与详情（Miuix 分支）。
 *
 * 版式与 [io.a10vm.ui.page.miuix.vm.MiuixRomListPage] 逐项相同：分类标题用 [SmallTitle]，
 * 每行自己发一张 [Card] 并套 [miuixCardInset]，卡片里是标题、ROM 名、大小、分隔线和操作行。
 * 页面只渲染 [BackupLibrary.Row] 并发 Action，进度与阶段文案由 ViewModel 提供。
 */
@Composable
internal fun MiuixBackupPage(useBlur: Boolean, pick: Boolean, detailId: String? = null) {
    val navigator = LocalNavigator.current
    val viewModel = LocalBackup.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteId by remember { mutableStateOf<UUID?>(null) }
    var exportId by remember { mutableStateOf<UUID?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.dispatch(BackupAction.Import(it))
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        exportId?.let { viewModel.dispatch(BackupAction.Export(it, uri)) }
        exportId = null
    }
    LaunchedEffect(detailId) {
        if (detailId != null) viewModel.dispatch(BackupAction.ShowDetail(UUID.fromString(detailId)))
    }
    val title = when {
        detailId != null -> "备份详情"
        pick -> "选择备份"
        else -> "备份管理"
    }
    val importBackup: () -> Unit = { importer.launch(BACKUP_ZIP_TYPES) }

    MiuixSettingsScaffold(
        title = title,
        enableBlur = useBlur,
        showBackButton = true,
        bottomContentPadding = 24.dp,
        topBarActions = {
            if (detailId == null) {
                IconButton(onClick = importBackup) {
                    Icon(imageVector = AppIcons.Download, contentDescription = "从外部导入备份")
                }
            }
        },
    ) {
        if (detailId == null) {
            if (state.isLoading && state.rows.isEmpty()) {
                item(key = "loading") {
                    MiuixPageLoading("正在读取备份…", Modifier.fillParentMaxSize())
                }
            } else if (state.error != null && state.rows.isEmpty()) {
                miuixSection("备份") {
                    BasicComponent(title = "无法读取备份", summary = state.error.orEmpty())
                }
            } else if (state.isEmpty) {
                miuixSection {
                    BasicComponent(
                        title = if (pick) "没有可选择的备份" else "还没有备份",
                        summary = if (pick) {
                            "先回到虚拟机卡片，在菜单里备份一台虚拟机。"
                        } else {
                            "在虚拟机卡片的菜单里备份，或点右上角从外部导入一个备份包。"
                        },
                    )
                }
            } else {
                state.groups.forEach { (group, rows) ->
                    item(key = "group-" + group.name) {
                        SmallTitle(text = group.title, modifier = Modifier.animateItem())
                    }
                    items(rows, key = { it.id.toString() }) { row ->
                        MiuixBackupCard(
                            modifier = Modifier.animateItem(),
                            row = row,
                            pickMode = pick,
                            selected = pick && state.pickedBackup?.id == row.id,
                            summary = state.summaries.getValue(row.id),
                            onOpen = { navigator.push(Route.BackupList(pick, row.id.toString())) },
                            onDelete = { deleteId = row.id },
                            onExport = {
                                exportId = row.id
                                exporter.launch(row.name + ".zip")
                            },
                            onShare = { viewModel.dispatch(BackupAction.Share(row.id)) },
                            onPrimary = {
                                if (pick) {
                                    viewModel.dispatch(BackupAction.Pick(row))
                                    navigator.pop()
                                } else {
                                    viewModel.createRoute(row.id)?.let { navigator.push(it) }
                                }
                            },
                        )
                    }
                }
            }
        } else {
            val row = state.rows.firstOrNull { it.id.toString() == detailId }
            val manifest = state.detail?.takeIf { it.id.toString() == detailId }
            if (manifest == null) {
                if (state.isLoadingDetail || state.selected?.toString() != detailId) {
                    item(key = "detail-loading") {
                        MiuixPageLoading("正在读取备份详情…", Modifier.fillParentMaxSize())
                    }
                } else {
                    miuixSection("备份详情") {
                        BasicComponent(title = "备份不可用", summary = "备份文件可能已被删除或损坏。")
                    }
                }
            } else {
                miuixSection("虚拟机") {
                    BasicComponent(title = "名称", summary = manifest.settings.name)
                    BasicComponent(
                        title = "分辨率",
                        summary = manifest.settings.displayMode.ifBlank { "跟随设备" },
                    )
                    BasicComponent(title = "备份大小", summary = row?.sizeLabel.orEmpty())
                    BasicComponent(title = "备份创建日期", summary = state.detailDate)
                }
                miuixSection("ROM") {
                    if (manifest.romArchive != null && state.detailRomBytes > 0L) {
                        BasicComponent(
                            title = "ROM 包大小",
                            summary = me.kanadex.geometryos.rom.RomTexts.bytes(state.detailRomBytes),
                        )
                    }
                    if (manifest.romArchive != null) {
                        BasicComponent(
                            title = "ROM 详情",
                            summary = state.detailRomTitle ?: "无法读取 ROM 信息",
                            onClick = { navigator.push(Route.RomDetail("", backupId = manifest.id.toString())) },
                        )
                    } else {
                        BasicComponent(title = "ROM 详情", summary = "这个备份没有携带 ROM 原包")
                    }
                    BasicComponent(
                        title = "源客户端",
                        summary = manifest.client.versionName + " (" + manifest.client.versionCode + ")",
                    )
                }
            }
        }
    }

    deleteId?.let { id ->
        MiuixConfirmDialog(
            show = true,
            title = "删除备份？",
            message = "备份文件将从本机删除，已恢复的虚拟机不受影响。",
            confirmText = "删除",
            onConfirm = {
                deleteId = null
                viewModel.dispatch(BackupAction.Delete(id))
            },
            onDismissRequest = { deleteId = null },
        )
    }
}

/** 一行备份，组件与间距和 [io.a10vm.ui.page.miuix.vm.MiuixRomCard] 一致。 */
@Composable
private fun MiuixBackupCard(
    modifier: Modifier = Modifier,
    row: BackupLibrary.Row,
    pickMode: Boolean,
    selected: Boolean,
    summary: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onPrimary: () -> Unit,
) {
    Card(
        modifier = modifier.miuixCardInset(),
        onClick = onOpen,
        showIndication = true,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.name,
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Icon(io.a10vm.ui.icons.AppMiuixIcons.Ok, contentDescription = "已选中", tint = MiuixTheme.colorScheme.primary)
                }
            }
            Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.sizeLabel,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = AppIcons.Delete,
                    contentDescription = "删除这个备份",
                    tint = MiuixTheme.colorScheme.error,
                )
            }
            IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = AppIcons.Save,
                    contentDescription = "导出这个备份",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = AppIcons.Share,
                    contentDescription = "分享这个备份",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                text = if (pickMode) {
                    if (selected) me.kanadex.geometryos.rom.RomTexts.PICKED_ACTION else me.kanadex.geometryos.rom.RomTexts.PICK_ACTION
                } else "恢复",
                enabled = !selected,
                onClick = onPrimary,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** SAF 类型过滤：与 ROM 导入一致，zip 为主，octet-stream 兜底。 */
private val BACKUP_ZIP_TYPES = arrayOf("application/zip", "application/octet-stream")
