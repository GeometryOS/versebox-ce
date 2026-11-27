// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup.ui.main

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import io.a10vm.ui.page.main.widget.setting.NavigationItemWidget

/*
 * Renders the backup library and detail routes in this theme.
 * The shared ViewModel owns archive operations and the restore continuation.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BackupPage(useBlur: Boolean, pick: Boolean, detailId: String? = null) {
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
    Scaffold(topBar = {
        LargeFlexibleTopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = { navigator.pop() }) { Icon(AppIcons.ArrowBack, contentDescription = "返回") }
        }, actions = {
            if (detailId == null) IconButton(onClick = importBackup) { Icon(AppIcons.Download, contentDescription = "导入备份") }
        })
    }) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        if (detailId == null) {
            if (state.isLoading || state.rows.isEmpty()) {
                item { SegmentedColumn { item { NavigationItemWidget(icon = AppIcons.Save, title = if (state.isLoading) "正在读取备份" else "暂无备份", description = "", onClick = importBackup) } } }
            }
            items(state.rows, key = { it.id.toString() }) { row ->
                Card(onClick = { navigator.push(Route.BackupList(pick, row.id.toString())) }) {
                    ListItem(headlineContent = { Text(row.name) }, supportingContent = { Text(row.romName) })
                    Column {
                        Text(row.sizeLabel, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { deleteId = row.id }) { Text("删除") }
                            TextButton(onClick = { exportId = row.id; exporter.launch(row.id.toString() + ".zip") }) { Text("导出") }
                            TextButton(onClick = { viewModel.dispatch(BackupAction.Share(row.id)) }) { Text("分享") }
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
                            TextButton(onClick = onPrimary) { Text(primary) }
                        }
                    }
                }
            }
        } else {
            state.detail?.takeIf { it.id.toString() == detailId }?.let { manifest ->
                item { SegmentedColumn(title = "虚拟机") {
                    item { ListItem(headlineContent = { Text(manifest.settings.name) }, supportingContent = { Text(state.rows.firstOrNull { it.id == manifest.id }?.sizeLabel.orEmpty()) }) }
                    item { ListItem(headlineContent = { Text("分辨率") }, supportingContent = { Text(manifest.settings.displayMode.ifBlank { "跟随设备" }) }) }
                    item { ListItem(headlineContent = { Text(manifest.settings.rom.name) }, supportingContent = { Text(manifest.settings.rom.key) }) }
                    item { TextButton(enabled = manifest.romArchive != null, onClick = { navigator.push(Route.RomDetail("", backupId = manifest.id.toString())) }) { Text("ROM 详情") } }
                    item { ListItem(headlineContent = { Text("源客户端") }, supportingContent = { Text(manifest.client.versionName) }) }
                } }
            }
        }
    }
    }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除备份？") }, text = { Text("备份文件将被删除。") },
            confirmButton = { TextButton(onClick = { deleteId = null; viewModel.dispatch(BackupAction.Delete(id)) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } })
    }
    restoreId?.let { id ->
        AlertDialog(onDismissRequest = { restoreId = null }, title = { Text("恢复虚拟机") }, text = {
            OutlinedTextField(value = restoreName, onValueChange = { restoreName = it }, label = { Text("名称") }, singleLine = true)
        }, confirmButton = { TextButton(onClick = { restoreId = null; viewModel.dispatch(BackupAction.Restore(id, restoreName)) }) { Text("恢复") } },
            dismissButton = { TextButton(onClick = { restoreId = null }) { Text("取消") } })
    }
}
