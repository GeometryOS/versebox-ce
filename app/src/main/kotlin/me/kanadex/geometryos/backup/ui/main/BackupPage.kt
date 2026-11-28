// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.main.widget.setting.BaseItemContainer
import io.a10vm.ui.page.main.widget.setting.BaseWidget
import io.a10vm.ui.page.main.widget.setting.NavigationItemWidget
import io.a10vm.ui.page.main.widget.setting.PageLoadingIndicator
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import java.util.UUID
import me.kanadex.geometryos.backup.BackupAction
import me.kanadex.geometryos.backup.BackupLibrary
import me.kanadex.geometryos.backup.LocalBackup

/**
 * 备份列表、选择与详情（Material 3 分支）。
 *
 * 版式与 [io.a10vm.ui.page.main.vm.RomListPage] 逐项相同：一个 [SegmentedColumn] 承载全部行，
 * 每行是 [BaseItemContainer] 加标题、ROM 名、大小三行文字，分隔线之后左侧是图标操作，
 * 右侧是唯一的主动作按钮。页面不读文件、不查库、不拼进度文案，状态全部来自 [LocalBackup]。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BackupPage(useBlur: Boolean, pick: Boolean, detailId: String? = null) {
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
                title = { Text(title) },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(AppIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (detailId == null) {
                        IconButton(onClick = importBackup) {
                            Icon(AppIcons.Download, contentDescription = "从外部导入备份")
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
        ) {
            if (detailId == null) {
                if (state.isLoading && state.rows.isEmpty()) {
                    item(key = "loading") {
                        PageLoadingIndicator("正在读取备份…", Modifier.fillParentMaxSize())
                    }
                } else if (state.error != null && state.rows.isEmpty()) {
                    item(key = "failure") {
                        SegmentedColumn(title = "备份") {
                            item(key = "failure-row") {
                                BaseWidget(title = "无法读取备份", description = state.error.orEmpty())
                            }
                        }
                    }
                } else if (state.isEmpty) {
                    item(key = "empty") {
                        SegmentedColumn(title = "备份") {
                            item(key = "empty-row") {
                                NavigationItemWidget(
                                    icon = AppIcons.Save,
                                    title = if (pick) "没有可选择的备份" else "还没有备份",
                                    description = if (pick) {
                                        "先回到虚拟机卡片，在菜单里备份一台虚拟机。"
                                    } else {
                                        "在虚拟机卡片的菜单里备份，或点右上角从外部导入一个备份包。"
                                    },
                                    onClick = if (pick) ({}) else importBackup,
                                )
                            }
                        }
                    }
                } else {
                    state.groups.forEach { (group, rows) ->
                        item(key = "group-" + group.name) {
                            SegmentedColumn(modifier = Modifier.animateItem(), title = group.title) {
                                rows.forEach { row ->
                                    item(key = row.id.toString()) {
                                        BackupRow(
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
                        }
                    }
                }
            } else {
                val row = state.rows.firstOrNull { it.id.toString() == detailId }
                val manifest = state.detail?.takeIf { it.id.toString() == detailId }
                if (manifest == null) {
                    if (state.isLoadingDetail || state.selected?.toString() != detailId) {
                        item(key = "detail-loading") {
                            PageLoadingIndicator("正在读取备份详情…", Modifier.fillParentMaxSize())
                        }
                    } else {
                        item(key = "detail-missing") {
                            SegmentedColumn(title = "备份详情") {
                                item {
                                    BaseWidget(
                                        title = "备份不可用",
                                        description = "备份文件可能已被删除或损坏。",
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item(key = "detail-vm") {
                        SegmentedColumn(title = "虚拟机") {
                            item(key = "name") { BaseWidget(title = "名称", description = manifest.settings.name) }
                            item(key = "display") {
                                BaseWidget(
                                    title = "分辨率",
                                    description = manifest.settings.displayMode.ifBlank { "跟随设备" },
                                )
                            }
                            item(key = "size") {
                                BaseWidget(title = "备份大小", description = row?.sizeLabel.orEmpty())
                            }
                            item(key = "date") { BaseWidget(title = "备份创建日期", description = state.detailDate) }
                        }
                    }
                    item(key = "detail-rom") {
                        SegmentedColumn(title = "ROM") {
                            if (manifest.romArchive != null && state.detailRomBytes > 0L) {
                                item(key = "rom-size") {
                                    BaseWidget(
                                        title = "ROM 包大小",
                                        description = me.kanadex.geometryos.rom.RomTexts.bytes(state.detailRomBytes),
                                    )
                                }
                            }
                            item(key = "rom-detail") {
                                if (manifest.romArchive != null) {
                                    NavigationItemWidget(
                                        icon = AppIcons.Android,
                                        title = "ROM 详情",
                                        description = state.detailRomTitle ?: "无法读取 ROM 信息",
                                        onClick = { navigator.push(Route.RomDetail("", backupId = manifest.id.toString())) },
                                    )
                                } else {
                                    BaseWidget(title = "ROM 详情", description = "这个备份没有携带 ROM 原包")
                                }
                            }
                            item(key = "client") {
                                BaseWidget(
                                    title = "源客户端",
                                    description = manifest.client.versionName + " (" + manifest.client.versionCode + ")",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("删除备份？") },
            text = { Text("备份文件将从本机删除，已恢复的虚拟机不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteId = null
                    viewModel.dispatch(BackupAction.Delete(id))
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

/**
 * 一行备份：标题是虚拟机名，第二行是 ROM 名，第三行是占用与是否含 ROM。
 * 操作行与 ROM 行一致，左侧三个图标操作，右侧一个主动作按钮。
 */
@Composable
private fun BackupRow(
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
    BaseItemContainer(onClick = onOpen) {
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
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Icon(AppIcons.Check, contentDescription = "已选中", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.sizeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onDelete) {
                Icon(AppIcons.Delete, contentDescription = "删除这个备份", tint = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onExport) {
                Icon(AppIcons.Save, contentDescription = "导出这个备份")
            }
            IconButton(onClick = onShare) {
                Icon(AppIcons.Share, contentDescription = "分享这个备份")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onPrimary, enabled = !selected) {
                Text(if (pickMode) {
                    if (selected) me.kanadex.geometryos.rom.RomTexts.PICKED_ACTION else me.kanadex.geometryos.rom.RomTexts.PICK_ACTION
                } else "恢复")
            }
        }
    }
}

/** SAF 类型过滤：与 ROM 导入一致，zip 为主，octet-stream 兜底。 */
private val BACKUP_ZIP_TYPES = arrayOf("application/zip", "application/octet-stream")
