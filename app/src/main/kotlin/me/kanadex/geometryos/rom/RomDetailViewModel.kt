// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.a10vm.ui.verse.AppState
import io.a10vm.ui.verse.LocalAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ROM 详情的状态与操作适配器。它从 RomListViewModel 取得同一份仓库快照，所有写操作也转发到该唯一操作链。
 * 两个 UI 分支只读取 State 并发送 Action，文件访问、数据库操作和确认文案仍由 ROM 业务层负责。
 */
data class RomDetailState(
    val row: RomRow? = null,
    val sections: List<RomDetailSection> = emptyList(),
    val item: RomItem? = null,
    val mode: RomListMode = RomListMode.Manage,
    val isLoading: Boolean = true,
    val busy: Boolean = false,
    val busyText: String = "",
    val confirm: RomConfirm? = null,
    val notice: RomNotice? = null,
    val message: String? = null,
    val createdVmId: String? = null,
    val pickedKey: String? = null,
)

sealed interface RomDetailAction {
    data object Refresh : RomDetailAction
    data object Check : RomDetailAction
    data object Repair : RomDetailAction
    data object Delete : RomDetailAction
    data object ClearCache : RomDetailAction
    data object Confirm : RomDetailAction
    data object Dismiss : RomDetailAction
    data object Primary : RomDetailAction
    data object ConsumeMessage : RomDetailAction
    data object ConsumeCreated : RomDetailAction
    data object ConsumePicked : RomDetailAction
    data object DismissNotice : RomDetailAction
}

class RomDetailViewModel(
    context: Context,
    appState: AppState,
    scope: CoroutineScope,
    private val rowId: String,
    private val mode: RomListMode,
) {
    private val operations = RomListViewModel(context, appState, scope, mode)
    private val _state = MutableStateFlow(RomDetailState())
    val state: StateFlow<RomDetailState> = _state.asStateFlow()

    init {
        scope.launch {
            operations.state.collect { list ->
                val row = list.rows.firstOrNull { it.rowId == rowId }
                val item = list.groups.asSequence().flatMap { it.items.asSequence() }
                    .firstOrNull { it.rowId == rowId }
                _state.value = RomDetailState(
                    row = row,
                    sections = row?.let(RomTexts::detailSections).orEmpty(),
                    item = item,
                    mode = mode,
                    isLoading = list.isLoading,
                    busy = list.busy,
                    busyText = list.busyText,
                    confirm = list.confirm,
                    notice = list.notice,
                    message = list.message,
                    createdVmId = list.createdVmId,
                    pickedKey = list.pickedKey,
                )
            }
        }
    }

    fun dispatch(action: RomDetailAction) {
        when (action) {
            RomDetailAction.Refresh -> operations.dispatch(RomListAction.Refresh)
            RomDetailAction.Check -> operations.dispatch(RomListAction.CheckRom(rowId))
            RomDetailAction.Repair -> operations.dispatch(RomListAction.RequestRepair(rowId))
            RomDetailAction.Delete -> operations.dispatch(RomListAction.RequestDelete(rowId))
            RomDetailAction.ClearCache -> operations.dispatch(RomListAction.RequestClearCache(rowId))
            RomDetailAction.Confirm -> when (_state.value.confirm?.kind) {
                RomConfirmKind.Delete -> operations.dispatch(RomListAction.ConfirmDelete)
                RomConfirmKind.ClearCache -> operations.dispatch(RomListAction.ConfirmClearCache)
                RomConfirmKind.Repair -> operations.dispatch(RomListAction.ConfirmRepair)
                null -> Unit
            }
            RomDetailAction.Dismiss -> operations.dispatch(RomListAction.DismissDelete)
            RomDetailAction.Primary -> operations.dispatch(
                if (mode == RomListMode.Manage) RomListAction.Create(rowId) else RomListAction.Pick(rowId),
            )
            RomDetailAction.ConsumeMessage -> operations.dispatch(RomListAction.ConsumeMessage)
            RomDetailAction.ConsumeCreated -> operations.dispatch(RomListAction.ConsumeCreated)
            RomDetailAction.ConsumePicked -> operations.dispatch(RomListAction.ConsumePicked)
            RomDetailAction.DismissNotice -> operations.dispatch(RomListAction.DismissNotice)
        }
    }
}



@Composable
fun rememberRomDetailViewModel(rowId: String, mode: RomListMode): RomDetailViewModel {
    val context = LocalContext.current
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()
    return remember(context, appState, scope, rowId, mode) {
        RomDetailViewModel(context, appState, scope, rowId, mode)
    }
}
