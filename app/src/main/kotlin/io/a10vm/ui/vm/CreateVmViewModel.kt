// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.a10vm.runtime.VmRegistry
import io.a10vm.ui.navigation.Route
import kotlinx.coroutines.flow.combine
import me.kanadex.geometryos.shared.VmInstanceNamePolicy
import io.a10vm.ui.verse.AppState
import io.a10vm.ui.verse.LocalAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kanadex.geometryos.backup.BackupAction
import me.kanadex.geometryos.backup.BackupViewModel
import me.kanadex.geometryos.backup.LocalBackup
import me.kanadex.geometryos.rom.RomRow
import me.kanadex.geometryos.rom.RomTexts

/** 新建虚拟机的视图状态（无 Compose 类型，docs/ui-rework/02 §1）。 */
data class CreateVmState(
    val romId: String? = null,
    /** 选中的**那一行**（来源:romKey@sha8）；同版本两份内容靠它分开。 */
    val rowId: String? = null,
    /**
     * 选中 ROM 的摘要：Android 15 V1.0.0 · 内置。
     *
     * 由逻辑层用 [RomTexts] 拼好——页面不许自己拼名称/版本/来源，
     * 也不许把这一行写成"按安卓版本选系统"（安卓版本是 ROM 的属性，不是它的身份）。
     */
    val romSummary: String = "",
    val name: String = "",
    val working: Boolean = false,
    val backupSummary: String = "未选择",
    val error: String? = null,
    /** 创建成功后由 UI 消费：拿到 id 就跳转管理页并清掉它。 */
    val createdId: String? = null,
)

/** 新建虚拟机页的全部动作。 */
sealed interface CreateVmAction {
    data class SetName(val name: String) : CreateVmAction
    data object Create : CreateVmAction
    data object ConsumeCreated : CreateVmAction
}

/*
 * Holds the form and submits its selected source only on confirmation. The picker shares
 * the exact ROM row identity with this form, so same-version siblings remain distinct.
 */
internal class CreateVmViewModel(
    private val appState: AppState,
    private val scope: CoroutineScope,
    private val backup: BackupViewModel,
    private val route: Route.CreateVm,
) {
    private val _state = MutableStateFlow(CreateVmState())
    val state: StateFlow<CreateVmState> = _state.asStateFlow()
    private var nameEdited = false
    private var initialSelectionPending = true

    init {
        scope.launch {
            combine(appState.romRows, appState.createVmRowId, backup.state, appState.devices) { rows, _, backups, devices ->
                if (initialSelectionPending) {
                    if (route.backupId != null && backups.isLoading) return@combine
                    if (route.romRowId != null && rows.isEmpty()) return@combine
                    initialSelectionPending = false
                    val selectedBackup = backups.rows.firstOrNull { it.id.toString() == route.backupId }
                    backup.dispatch(BackupAction.Pick(selectedBackup))
                    val initialRom = when {
                        route.backupId != null -> null
                        route.romRowId != null -> rows.firstOrNull { it.rowId == route.romRowId }
                        else -> rows.firstOrNull { it.isReady || (!it.placeholder && it.hasArchive) }
                    }
                    selectRom(initialRom)
                }
                var row = rows.firstOrNull { it.rowId == appState.createVmRowId.value }
                val selected = backup.state.value.pickedBackup.takeIf { row == null }
                if (selected == null && row == null && route.backupId == null && route.romRowId == null) {
                    row = rows.firstOrNull { it.isReady || (!it.placeholder && it.hasArchive) }
                    selectRom(row)
                }
                val requested = selected?.name ?: row?.let {
                    VmInstanceNamePolicy.defaultName(it.displayName, VmRegistry.MAX_NAME_LENGTH)
                }.orEmpty()
                val suggested = VmInstanceNamePolicy.correct(requested, devices.map { it.name }, VmRegistry.MAX_NAME_LENGTH)
                    ?.value ?: requested
                _state.update {
                    it.copy(
                        romId = row?.key,
                        rowId = row?.rowId,
                        romSummary = row?.let { target ->
                            RomTexts.titleWithVersion(target) + " / " + RomTexts.sourceLabel(target.source)
                        }.orEmpty(),
                        backupSummary = selected?.name ?: "未选择",
                        name = if (nameEdited) it.name else suggested,
                    )
                }
            }.collect {}
        }
    }

    private fun selectRom(row: RomRow?) {
        appState.createVmRomId.value = row?.key
        appState.createVmRowId.value = row?.rowId
    }

    fun dispatch(action: CreateVmAction) {
        when (action) {
            is CreateVmAction.SetName -> {
                nameEdited = true
                _state.update { it.copy(name = action.name, error = null) }
            }
            CreateVmAction.Create -> create()
            CreateVmAction.ConsumeCreated -> _state.update { it.copy(createdId = null) }
        }
    }

    private fun create() {
        val snapshot = _state.value
        if (initialSelectionPending || snapshot.working || snapshot.createdId != null) return
        backup.state.value.pickedBackup?.takeIf { appState.createVmRowId.value == null }?.let {
            backup.restoreFromCreate(it.id, snapshot.name)
            return
        }
        val row = appState.romRows.value.firstOrNull { it.rowId == snapshot.rowId }
        if (row == null || (!row.isReady && (row.placeholder || !row.hasArchive))) {
            _state.update { it.copy(error = "没有可用的 ROM") }
            return
        }
        if (snapshot.name.isNotBlank() && VmRegistry.normalize(snapshot.name) == null) {
            _state.update { it.copy(error = "虚拟机名称无效") }
            return
        }
        _state.update { it.copy(working = true, error = null) }
        scope.launch {
            try {
                val device = withContext(Dispatchers.IO) {
                    appState.create(row.key, row.payloadSha256, row.source, row.displayName, snapshot.name)
                }
                _state.update {
                    it.copy(createdId = device?.id, error = if (device == null) "创建失败，请检查存储空间" else null)
                }
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }
}

@Composable
internal fun rememberCreateVmViewModel(route: Route.CreateVm): CreateVmViewModel {
    val appState = LocalAppState.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val backup = LocalBackup.current
    return remember(appState, scope, backup, route) { CreateVmViewModel(appState, scope, backup, route) }
}
