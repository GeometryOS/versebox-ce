// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.records

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import me.kanadex.geometryos.audit.model.AuditState
import io.a10vm.records.RecordFilter
import io.a10vm.records.RecordKind
import io.a10vm.records.RecordStatus
import io.a10vm.records.RecordStore
import io.a10vm.records.VmRecord
import io.a10vm.runtime.VmDevice
import io.a10vm.ui.verse.AppState
import io.a10vm.ui.verse.LocalAppState
import io.a10vm.ui.verse.RecordFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记录列表的视图状态：只有数据，没有 Compose 类型。 */
data class HistoryState(
    val status: RecordStatus? = null,
    val kind: RecordKind? = null,
    val vmId: String? = null,
    val records: List<VmRecord> = emptyList(),
    /** 筛选菜单要用的虚拟机清单，同样由 ViewModel 提供，页面自己不去读 AppState。 */
    val devices: List<VmDevice> = emptyList(),
    /** 首帧还没查完（查询在 IO 上），页面据此不要显示「空」态，避免闪一下。 */
    val isLoading: Boolean = true,
    /** 归档容量视图（docs/log-audit/02 §5）：页面只渲染，不自己算字节与阈值。 */
    val audit: AuditState = AuditState(),
    /**
     * 要定位到的那条记录（`docs/ui-rework/09/05` §2）。
     *
     * 页面只做两件事：滚到这个 id、闪两下，然后发 [HistoryAction.ConsumeFocus]。
     * 算 index、判断是否已被清理都在 ViewModel 里，页面不碰。
     */
    val focusRecordId: Long? = null,
    /** 每次定位 +1：同一个 id 连续定位两次也要再闪一次。 */
    val blinkToken: Int = 0,
    /** 一次性提示（例如「这条记录已被清理」），页面用 Snackbar 显示。 */
    val message: String? = null,
)

/** 记录列表能发生的全部动作。 */
sealed interface HistoryAction {
    data class SetStatus(val status: RecordStatus?) : HistoryAction
    data class SetKind(val kind: RecordKind?) : HistoryAction
    data class SetVm(val vmId: String?) : HistoryAction
    data class Delete(val ids: List<Long>) : HistoryAction
    data object Refresh : HistoryAction

    /** 归档触硬上限后的显式清理；`confirm = false` 只重算状态，不删任何东西。 */
    data class CleanupArchives(val confirm: Boolean) : HistoryAction

    /** 定位已完成（滚到 + 闪过），清掉焦点，避免回来又闪一遍。 */
    data object ConsumeFocus : HistoryAction

    data object ConsumeMessage : HistoryAction
}

/**
 * 记录列表的唯一逻辑实现，Miuix 与 Material 3 两个页面共用。
 *
 * 查询、删除、监听数据变化、给筛选菜单准备虚拟机清单都在这里；
 * 页面只渲染 [HistoryState]、把用户操作转成 [HistoryAction]。
 */
class HistoryViewModel(
    context: Context,
    private val appState: AppState,
    private val scope: CoroutineScope,
) {
    private val store = RecordStore.get(context)

    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    /** 当前正在跑的查询；筛选连续变化时先取消上一次，避免旧结果盖新结果。 */
    private var loadJob: Job? = null

    /** 等待落地的定位请求；查询回来后才算得出 index。 */
    private var pendingFocus: RecordFocus? = null

    init {
        refresh()
        scope.launch { store.changes.collect { refresh() } }
        scope.launch {
            appState.devices.collect { list -> _state.update { it.copy(devices = list) } }
        }
        scope.launch {
            appState.auditState.collect { audit -> _state.update { it.copy(audit = audit) } }
        }
        // 定位请求来自别处（第一屏 / 异常弹窗 / 虚拟机管理页），所以监听而不是由页面传参。
        scope.launch {
            snapshotFlow { appState.recordFocus }.collect { focus ->
                if (focus != null) applyFocus(focus)
            }
        }
    }

    fun dispatch(action: HistoryAction) {
        when (action) {
            is HistoryAction.SetStatus -> {
                _state.update { it.copy(status = action.status) }
                refresh()
            }

            is HistoryAction.SetKind -> {
                _state.update { it.copy(kind = action.kind) }
                refresh()
            }

            is HistoryAction.SetVm -> {
                _state.update { it.copy(vmId = action.vmId) }
                refresh()
            }

            is HistoryAction.Delete -> {
                appState.deleteRecords(store, action.ids)
                refresh()
            }

            HistoryAction.Refresh -> refresh()

            is HistoryAction.CleanupArchives -> appState.cleanupAuditArchives(action.confirm)

            HistoryAction.ConsumeFocus -> _state.update { it.copy(focusRecordId = null) }

            HistoryAction.ConsumeMessage -> _state.update { it.copy(message = null) }
        }
    }

    /** 收到定位请求：先按它带过来的虚拟机收紧筛选，再重查一次列表。 */
    private fun applyFocus(focus: RecordFocus) {
        pendingFocus = focus
        if (focus.vmId != null && _state.value.vmId != focus.vmId) {
            _state.update { it.copy(vmId = focus.vmId) }
        }
        refresh()
    }

    /**
     * 查询回来之后把定位落地。
     *
     * 「该条已被清理」必须回落到 tab 顶部 + 一条提示，而不是停在一个永远不闪的空页面上（09/05 §5）。
     */
    private fun resolveFocus(records: List<VmRecord>) {
        val focus = pendingFocus ?: return
        pendingFocus = null
        appState.consumeRecordFocus()
        val target = focus.recordId ?: return
        if (records.none { it.id == target }) {
            _state.update { it.copy(message = "这条记录已被清理") }
            return
        }
        _state.update { it.copy(focusRecordId = target, blinkToken = it.blinkToken + 1) }
    }

    /**
     * 查询放到 IO：SQLite 查询绝不能出现在组合线程上（切 tab 时会组合本页，
     * 同步查询就是那次卡顿的来源）。结果回主线程写 StateFlow。
     */
    private fun refresh() {
        val snapshot = _state.value
        loadJob?.cancel()
        loadJob = scope.launch {
            val records = withContext(Dispatchers.IO) {
                runCatching {
                    store.query(
                        RecordFilter(
                            vmId = snapshot.vmId,
                            kind = snapshot.kind,
                            status = snapshot.status,
                        ),
                    )
                }.getOrElse { emptyList() }
            }
            _state.update { it.copy(records = records, isLoading = false) }
            resolveFocus(records)
        }
    }
}

@Composable
fun rememberHistoryViewModel(): HistoryViewModel {
    val context = LocalContext.current
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()
    return remember(context, appState, scope) { HistoryViewModel(context, appState, scope) }
}
