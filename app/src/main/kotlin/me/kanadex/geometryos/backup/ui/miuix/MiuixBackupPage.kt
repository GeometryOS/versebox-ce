// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup.ui.miuix

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import me.kanadex.geometryos.backup.BackupAction
import me.kanadex.geometryos.backup.LocalBackup
import me.kanadex.geometryos.rom.RomTexts
import java.util.UUID
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.miuixSection
import io.a10vm.ui.page.miuix.widgets.miuixCardInset
import io.a10vm.ui.page.miuix.widgets.MiuixConfirmDialog
import top.yukonga.miuix.kmp.basic.*

/*
 * Renders the backup library and detail routes in this theme.
 * The shared ViewModel owns archive operations and the restore continuation.
 */
@Composable
internal fun MiuixBackupPage(useBlur: Boolean, pick: Boolean, detailId: String? = null) {
    val navigator = LocalNavigator.current
    val viewModel = LocalBackup.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteId by remember { mutableStateOf<UUID?>(null) }
    var exportId by remember { mutableStateOf<UUID?>(null) }
    var restoreId by remember { mutableStateOf<UUID?>(null) }
    var restoreName by remember { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.dispatch(BackupAction.Import(it))
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        exportId?.let { viewModel.dispatch(BackupAction.Export(it, uri)) }
        exportId = null
    }
    LaunchedEffect(detailId) { if (detailId != null) viewModel.dispatch(BackupAction.ShowDetail(UUID.fromString(detailId))) }
    val title = when { detailId != null -> "备份详情"; pick -> "选择备份"; else -> "备份管理" }
    val importBackup: () -> Unit = { importer.launch(arrayOf("application/zip", "application/octet-stream")) }
    MiuixSettingsScaffold(title = title, enableBlur = useBlur, showBackButton = true, topBarActions = {
        if (detailId == null) IconButton(onClick = importBackup) { Icon(AppIcons.Download, contentDescription = "导入备份") }
    }) {
        if (detailId == null) {
            if (state.isLoading || state.rows.isEmpty()) {
                miuixSection { BasicComponent(title = if (state.isLoading) "正在读取备份" else "暂无备份") }
            }
            items(state.rows, key = { it.id.toString() }) { row ->
                Card(modifier = Modifier.miuixCardInset(), onClick = { navigator.push(Route.BackupList(pick, row.id.toString())) }) {
                    BasicComponent(title = row.name, summary = row.romName)
                    Column {
                        Text(row.sizeLabel, style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2)
                        Row {
                            TextButton(text = "删除", onClick = { deleteId = row.id })
                            TextButton(text = "导出", onClick = { exportId = row.id; exporter.launch(row.id.toString() + ".zip") })
                            TextButton(text = "分享", onClick = { viewModel.dispatch(BackupAction.Share(row.id)) })
                            val primary = if (pick) "选择" else "恢复"
                            val onPrimary: () -> Unit = {
                                if (pick) {
                                    viewModel.dispatch(BackupAction.Pick(row))
                                    navigator.pop()
                                } else {
                                    restoreId = row.id
                                    restoreName = row.name
                                }
                            }
                            TextButton(text = primary, onClick = onPrimary)
                        }
                    }
                }
            }
        } else {
            state.detail?.takeIf { it.id.toString() == detailId }?.let { manifest ->
                miuixSection("虚拟机") {
                    BasicComponent(title = "名称", summary = manifest.settings.name)
                    BasicComponent(title = "分辨率", summary = manifest.settings.displayMode.ifBlank { "跟随设备" })
                    BasicComponent(title = "备份大小", summary = state.rows.firstOrNull { it.id == manifest.id }?.sizeLabel)
                }
                miuixSection("ROM") {
                    BasicComponent(title = manifest.settings.rom.name, summary = manifest.settings.rom.key)
                    BasicComponent(title = "ROM 详情", enabled = manifest.romArchive != null, onClick = { navigator.push(Route.RomDetail("", backupId = manifest.id.toString())) })
                    BasicComponent(title = "源客户端", summary = manifest.client.versionName)
                }
            }
        }
    }
    
    deleteId?.let { id ->
        MiuixConfirmDialog(show = true, title = "删除备份？", message = "备份文件将被删除。", confirmText = "删除",
            onConfirm = { deleteId = null; viewModel.dispatch(BackupAction.Delete(id)) }, onDismissRequest = { deleteId = null })
    }
    restoreId?.let { id ->
        top.yukonga.miuix.kmp.window.WindowDialog(show = true, title = "恢复虚拟机", onDismissRequest = { restoreId = null }) {
            TextField(value = restoreName, onValueChange = { restoreName = it }, label = "名称", singleLine = true)
            TextButton(text = "恢复", onClick = { restoreId = null; viewModel.dispatch(BackupAction.Restore(id, restoreName)) })
        }
    }
}
