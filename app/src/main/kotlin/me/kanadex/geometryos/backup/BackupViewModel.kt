// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import io.a10vm.ui.verse.AppState
import io.a10vm.ui.verse.LocalAppState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kanadex.geometryos.rom.RomRepository
import me.kanadex.geometryos.rom.toRow
import me.kanadex.geometryos.shared.BackupDateGrouping
import java.time.ZoneId

/*
 * This ViewModel coordinates the backup library and navigation continuations shared by both UIs.
 * ZIP, database and ROM work stays off the main thread. ROM installation returns through the
 * existing ROM list ViewModel before a VM restore is committed.
 */
internal val LocalBackup = staticCompositionLocalOf<BackupViewModel> { error("Backup navigation host missing") }

internal data class BackupState(
    val rows: List<BackupLibrary.Row> = emptyList(),
    val groups: List<Pair<BackupDateGrouping.Group, List<BackupLibrary.Row>>> = emptyList(),
    val summaries: Map<UUID, String> = emptyMap(),
    val detailDate: String = "未知",
    val detailRomTitle: String? = null,
    val detailRomBytes: Long = 0L,
    val selected: UUID? = null,
    val detail: BackupManifest? = null,
    val name: String = "",
    val isLoading: Boolean = true,
    val isLoadingDetail: Boolean = false,
    val progress: BackupArchive.Progress? = null,
    val stage: String = "",
    val working: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val pendingRomUri: Uri? = null,
    val createdVmId: String? = null,
    val createdBackup: UUID? = null,
    val pickedBackup: BackupLibrary.Row? = null,
) {
    val isEmpty get() = !isLoading && rows.isEmpty() && error == null
    val backupCountSummary: String get() = if (isLoading) "" else "${rows.size} 个备份"

    /**
     * 进度弹窗的唯一文案来源：归档阶段按 phase 取名并带条目计数，
     * 其余阶段用调用方给的一句话。切换阶段时主线程只做一次字符串拼接。
     */
    val progressText: String get() {
        val current = progress
        if (current != null) {
            val label = when (current.phase) {
                BackupArchive.Phase.Write -> "正在写入备份包"
                BackupArchive.Phase.WriteRom -> "正在写入 ROM 原包"
                BackupArchive.Phase.Verify -> "正在校验备份包"
                BackupArchive.Phase.Restore -> "正在恢复虚拟机文件"
                BackupArchive.Phase.ReadRom -> "正在读取 ROM 原包"
            }
            return when {
                current.totalBytes > 0L -> label + " " + me.kanadex.geometryos.rom.RomTexts.bytes(current.bytes) +
                    " / " + me.kanadex.geometryos.rom.RomTexts.bytes(current.totalBytes)
                current.totalFiles > 0 -> label + " " + current.files + "/" + current.totalFiles
                else -> label
            }
        }
        return stage.ifEmpty { "正在处理" }
    }
}

internal sealed interface BackupAction {
    data object Refresh : BackupAction
    data class Capture(val vmId: String, val includeRom: Boolean = true) : BackupAction
    data object ConsumeCaptured : BackupAction
    data class Pick(val row: BackupLibrary.Row?) : BackupAction
    data class Delete(val id: UUID) : BackupAction
    data class Share(val id: UUID) : BackupAction
    data object DismissError : BackupAction
    data class ShowDetail(val id: UUID?) : BackupAction
    data class SetName(val value: String) : BackupAction
    data class Export(val id: UUID, val uri: Uri?) : BackupAction
    data class Import(val uri: Uri?) : BackupAction
    data class RomImported(val result: Result<RomRepository.Outcome>) : BackupAction
    data object Cancel : BackupAction
    data object ConsumeMessage : BackupAction
    data object ConsumeCreated : BackupAction
}

internal class BackupViewModel(
    private val context: Context,
    private val appState: AppState,
    private val scope: CoroutineScope,
) {
    private val library = BackupLibrary.get(context)
    private val restoration = BackupRestore(context)
    private val capture = BackupCapture(context)
    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()
    @Volatile private var generation = 0
    private var prepared: BackupRestore.Prepared? = null

    init {
        scope.launch {
            library.revision.collect { refresh() }
        }
    }

    fun createRoute(id: UUID): io.a10vm.ui.navigation.Route.CreateVm? =
        _state.value.rows.firstOrNull { it.id == id }?.let {
            io.a10vm.ui.navigation.Route.CreateVm(backupId = it.id.toString())
        }

    fun dispatch(action: BackupAction) {
        when (action) {
            BackupAction.Refresh -> refresh()
            is BackupAction.Capture -> capture(action)
            BackupAction.ConsumeCaptured -> _state.update { it.copy(createdBackup = null) }
            is BackupAction.Pick -> {
                if (action.row != null) {
                    appState.createVmRomId.value = null
                    appState.createVmRowId.value = null
                }
                _state.update { it.copy(pickedBackup = action.row) }
            }
            is BackupAction.Delete -> manage("正在删除备份") { library.delete(action.id) }
            is BackupAction.Share -> manage("正在分享备份") { library.share(action.id) }
            BackupAction.DismissError -> _state.update { it.copy(error = null) }
            is BackupAction.ShowDetail -> showDetail(action.id)
            is BackupAction.SetName -> _state.update { it.copy(name = action.value) }
            is BackupAction.Export -> export(action.id, action.uri)
            is BackupAction.Import -> import(action.uri)
            is BackupAction.RomImported -> continueRestore(action)
            BackupAction.Cancel -> cancel()
            BackupAction.ConsumeMessage -> _state.update { it.copy(message = null) }
            BackupAction.ConsumeCreated -> _state.update { it.copy(createdVmId = null, pickedBackup = null) }
        }
    }

    private fun capture(action: BackupAction.Capture) {
        if (_state.value.working || prepared != null) return
        _state.update { it.copy(working = true, stage = "正在读取虚拟机设置", progress = null, error = null) }
        scope.launch {
            try {
                val id = capture.create(action.vmId, action.includeRom,
                    { value -> _state.update { it.copy(progress = value) } },
                    { text -> _state.update { it.copy(stage = text) } },
                )
                _state.update { it.copy(working = false, progress = null, createdBackup = id) }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                _state.update { it.copy(working = false, progress = null, error = failure.message ?: "备份失败") }
            }
        }
    }

    private fun refresh() {
        scope.launch(Dispatchers.IO) {
            val rows = runCatching { library.rows() }.getOrElse {
                _state.update { state -> state.copy(error = it.message ?: "无法读取备份列表") }
                emptyList()
            }
            val groups = BackupDateGrouping.groupAndSort(rows, System.currentTimeMillis()) { it.createdAt }
            val zone = ZoneId.systemDefault()
            _state.update { it.copy(rows = rows, groups = groups, summaries = rows.associate { row -> row.id to row.summary(zone) },
                pickedBackup = it.pickedBackup?.let { picked -> rows.firstOrNull { row -> row.id == picked.id } }, isLoading = false) }
        }
    }

    private fun showDetail(id: UUID?) {
        _state.update { it.copy(selected = id, detail = null, isLoadingDetail = id != null) }
        if (id == null) return
        scope.launch(Dispatchers.IO) {
            val detail = runCatching { BackupArchive.readHeader(library.archive(id)) }.getOrNull()
            val rom = detail?.romArchive?.let {
                runCatching { library.readEmbeddedRom(id) { ensureActive() } }.getOrNull()
            }
            val date = BackupDateGrouping.format(detail?.createdAtMillis, ZoneId.systemDefault()).ifEmpty { "未知" }
            val title = rom?.properties?.let { me.kanadex.geometryos.rom.RomTexts.titleWithVersion(
                it.toRow(me.kanadex.geometryos.rom.RomSource.Imported, me.kanadex.geometryos.rom.RomState.Pending,
                    archiveBytes = rom.bytes),
            ) }
            _state.update { if (it.selected == id) it.copy(detail = detail, isLoadingDetail = false,
                detailDate = date, detailRomTitle = title, detailRomBytes = rom?.bytes ?: 0L) else it }
        }
    }

    private fun manage(stage: String, action: () -> Unit) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, stage = stage, progress = null, error = null) }
        scope.launch(Dispatchers.IO) {
            try {
                action()
            } catch (failure: Exception) {
                fail(failure.message ?: "操作失败")
            } finally {
                _state.update { it.copy(working = false, progress = null) }
            }
        }
    }

    private fun export(id: UUID, uri: Uri?) {
        if (uri == null || _state.value.working) return
        _state.update { it.copy(working = true, stage = "正在导出备份", progress = null, error = null) }
        scope.launch(Dispatchers.IO) {
            runCatching { library.export(id, uri) }
                .onSuccess { _state.update { it.copy(message = "备份已导出") } }
                .onFailure { _state.update { it.copy(error = it.message ?: "导出失败") } }
            _state.update { it.copy(working = false, stage = "") }
        }
    }

    private fun import(uri: Uri?) {
        if (uri == null || _state.value.working) return
        val token = ++generation
        _state.update { it.copy(working = true, stage = "正在复制备份文件", progress = null, error = null) }
        scope.launch(Dispatchers.IO) {
            runCatching {
                library.import(
                    uri,
                    { value -> _state.update { it.copy(progress = value) } },
                    { check(token == generation) },
                    { text -> _state.update { it.copy(stage = text) } },
                )
            }
                .onSuccess { _state.update { it.copy(working = false, message = "备份已导入") }; refresh() }
                .onFailure { _state.update { it.copy(working = false, error = it.message ?: "导入备份失败") } }
        }
    }

    internal fun restoreFromCreate(id: UUID, name: String?) {
        if (_state.value.working || prepared != null) return
        val token = ++generation
        _state.update { it.copy(working = true, stage = "正在读取备份清单", progress = null, error = null, selected = id) }
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) {
                    val task = kotlinx.coroutines.currentCoroutineContext()
                    restoration.prepare(
                        id,
                        { progress -> _state.update { it.copy(progress = progress) } },
                        {
                            task.ensureActive()
                            if (token != generation) throw kotlinx.coroutines.CancellationException("用户已取消")
                        },
                        { text -> _state.update { it.copy(stage = text) } },
                    )
                }
                prepared = value
                _state.update { it.copy(working = false, pendingRomUri = value.romUri,
                    name = name ?: value.manifest.settings.name, stage = "正在导入备份中的 ROM") }
                if (value.romUri == null) commitPrepared(value, null)
            } catch (failure: Exception) {
                _state.update { it.copy(working = false, progress = null, error = failure.message ?: "恢复备份失败") }
            }
        }
    }

    private fun continueRestore(action: BackupAction.RomImported) {
        val operation = prepared ?: return fail("恢复任务已结束")
        action.result.fold(
            onSuccess = { commitPrepared(operation, it) },
            onFailure = { failure ->
                prepared = null
                _state.update { it.copy(pendingRomUri = null, error = failure.message ?: "ROM 导入失败") }
                scope.launch(Dispatchers.IO) {
                    restoration.abort(operation, IllegalStateException("ROM 导入失败", failure))
                }
            },
        )
    }

    private fun commitPrepared(operation: BackupRestore.Prepared, imported: RomRepository.Outcome?) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, pendingRomUri = null, stage = "正在恢复虚拟机文件") }
        scope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    val task = kotlinx.coroutines.currentCoroutineContext()
                    restoration.commit(
                        operation,
                        _state.value.name,
                        imported,
                        { value -> _state.update { it.copy(progress = value) } },
                        { task.ensureActive() },
                        { text -> _state.update { it.copy(stage = text) } },
                    )
                }
                prepared = null
                appState.refresh()
                _state.update { it.copy(working = false, progress = null, createdVmId = restored.id, message = "虚拟机已恢复") }
            } catch (failure: Exception) {
                prepared = null
                _state.update { it.copy(working = false, progress = null, error = failure.message ?: "恢复虚拟机失败") }
            }
        }
    }

    private fun cancel() {
        generation++
        val value = prepared
        prepared = null
        if (value != null) scope.launch(Dispatchers.IO) { restoration.abort(value, kotlinx.coroutines.CancellationException("用户取消恢复")) }
        _state.update { it.copy(working = false, progress = null, pendingRomUri = null, message = null) }
    }

    private fun fail(message: String) { _state.update { it.copy(error = message) } }

    companion object {
        @Composable fun rememberViewModel(): BackupViewModel {
            val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
            val appState = LocalAppState.current
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            return remember(context, appState, scope) { BackupViewModel(context, appState, scope) }
        }
    }
}

