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

/*
 * This ViewModel coordinates the backup library and navigation continuations shared by both UIs.
 * ZIP, database and ROM work stays off the main thread. ROM installation returns through the
 * existing ROM list ViewModel before a VM restore is committed.
 */
internal val LocalBackup = staticCompositionLocalOf<BackupViewModel> { error("Backup navigation host missing") }

internal data class BackupState(
    val rows: List<BackupLibrary.Row> = emptyList(),
    val selected: UUID? = null,
    val detail: BackupManifest? = null,
    val name: String = "",
    val isLoading: Boolean = true,
    val isLoadingDetail: Boolean = false,
    val progress: BackupArchive.Progress? = null,
    val workLabel: String = "",
    val working: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val pendingRomUri: Uri? = null,
    val createdVmId: String? = null,
    val createdBackup: UUID? = null,
    val pickedBackup: BackupLibrary.Row? = null,
) {
    val countLabel get() = if (isLoading) null else rows.size.toString() + " 个备份"
    val progressText get() = progress?.let { workLabel + " " + it.files + "/" + it.totalFiles } ?: workLabel
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
    data class Restore(val id: UUID, val name: String? = null) : BackupAction
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

    fun dispatch(action: BackupAction) {
        when (action) {
            BackupAction.Refresh -> refresh()
            is BackupAction.Capture -> capture(action)
            BackupAction.ConsumeCaptured -> _state.update { it.copy(createdBackup = null) }
            is BackupAction.Pick -> _state.update { it.copy(pickedBackup = action.row) }
            is BackupAction.Delete -> manage { library.delete(action.id) }
            is BackupAction.Share -> manage { library.share(action.id) }
            BackupAction.DismissError -> _state.update { it.copy(error = null) }
            is BackupAction.ShowDetail -> showDetail(action.id)
            is BackupAction.SetName -> _state.update { it.copy(name = action.value) }
            is BackupAction.Export -> export(action.id, action.uri)
            is BackupAction.Import -> import(action.uri)
            is BackupAction.Restore -> restore(action.id, action.name)
            is BackupAction.RomImported -> continueRestore(action)
            BackupAction.Cancel -> cancel()
            BackupAction.ConsumeMessage -> _state.update { it.copy(message = null) }
            BackupAction.ConsumeCreated -> _state.update { it.copy(createdVmId = null, pickedBackup = null) }
        }
    }

    private fun capture(action: BackupAction.Capture) {
        if (_state.value.working || prepared != null) return
        _state.update { it.copy(working = true, workLabel = "正在备份虚拟机", progress = null, error = null) }
        scope.launch {
            try {
                val id = capture.create(action.vmId, action.includeRom) { value ->
                    _state.update { it.copy(progress = value) }
                }
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
            _state.update { it.copy(rows = rows, isLoading = false) }
        }
    }

    private fun showDetail(id: UUID?) {
        _state.update { it.copy(selected = id, detail = null, isLoadingDetail = id != null) }
        if (id == null) return
        scope.launch(Dispatchers.IO) {
            val detail = runCatching { BackupArchive.readManifest(library.archive(id)) }.getOrNull()
            _state.update { if (it.selected == id) it.copy(detail = detail, isLoadingDetail = false) else it }
        }
    }

    private fun manage(action: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try { action() } catch (failure: Exception) { fail(failure.message ?: "操作失败") }
        }
    }

    private fun export(id: UUID, uri: Uri?) {
        if (uri == null) return
        scope.launch(Dispatchers.IO) {
            runCatching { library.export(id, uri) }
                .onSuccess { _state.update { it.copy(message = "备份已导出") } }
                .onFailure { _state.update { it.copy(error = it.message ?: "导出失败") } }
        }
    }

    private fun import(uri: Uri?) {
        if (uri == null || _state.value.working) return
        val token = ++generation
        _state.update { it.copy(working = true, workLabel = "正在导入备份", progress = null, error = null) }
        scope.launch(Dispatchers.IO) {
            runCatching { library.import(uri, { value -> _state.update { it.copy(progress = value) } }) { check(token == generation) } }
                .onSuccess { id -> _state.update { it.copy(working = false, selected = id, message = "备份已导入") }; refresh() }
                .onFailure { _state.update { it.copy(working = false, error = it.message ?: "导入备份失败") } }
        }
    }

    private fun restore(id: UUID, name: String?) {
        if (_state.value.working || prepared != null) return
        val token = ++generation
        _state.update { it.copy(working = true, workLabel = "正在检查备份", progress = null, error = null, selected = id) }
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) {
                    val task = kotlinx.coroutines.currentCoroutineContext()
                    restoration.prepare(id, { progress ->
                        _state.update { it.copy(progress = progress) }
                    }) {
                        task.ensureActive()
                        if (token != generation) throw kotlinx.coroutines.CancellationException("用户已取消")
                    }
                }
                prepared = value
                _state.update { it.copy(working = false, pendingRomUri = value.romUri,
                    name = name ?: value.manifest.settings.name, workLabel = "正在导入备份中的 ROM") }
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
        _state.update { it.copy(working = true, pendingRomUri = null, workLabel = "正在写入虚拟机文件") }
        scope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    val task = kotlinx.coroutines.currentCoroutineContext()
                    restoration.commit(operation, _state.value.name, imported, { value ->
                        _state.update { it.copy(progress = value) }
                    }) { task.ensureActive() }
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

