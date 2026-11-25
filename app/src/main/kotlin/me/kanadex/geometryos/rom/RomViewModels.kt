// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import io.a10vm.ui.verse.AppState
import io.a10vm.ui.verse.LocalAppState
import io.a10vm.ui.verse.RomFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ROM 列表页的状态、动作与 ViewModel。两个 UI 分支共用这一份，页面只读 State、只发 Action。
 * 扫描、导入、解包、删除、检查都在 RomRepository，这里负责把它们串成一条用户看得懂的流程。
 */

/**
 * 列表页的两种用途。Manage 用来管理，导入、删除、清理、检查都在这一页；
 * Pick 只给新建虚拟机选一个系统，选完回上一页，创建在那边做。
 */
enum class RomListMode { Manage, Pick }

/**
 * 列表页状态。页面只渲染它，判断、拼串、分组的活在这一层做完。
 */
data class RomListState(
    // 页面展示的原始行与分组项共享同一次仓库快照
    val rows: List<RomRow> = emptyList(),
    // 已经分好组的行，分组规则在 RomGrouping
    val groups: List<RomGroup> = emptyList(),
    /**
     * 首次扫描还没回来。正在读和真的空必须分开，
     * 否则页面会先画一次没有 ROM，再整屏替换。
     */
    val isLoading: Boolean = true,
    val busy: Boolean = false,
    val busyText: String = "",
    val message: String? = null,
    // 待确认的操作，标题和正文都由这一层算好
    val confirm: RomConfirm? = null,
    // 只有一个按钮的告知。检查出问题但原包不在时用它，不给用户一个假的取消
    val notice: RomNotice? = null,
    val createdVmId: String? = null,
    // 要定位并闪烁的那一行
    val highlightKey: String? = null,
    // 每次定位加一，同一个 key 连着定位两次也要再闪
    val blinkToken: Int = 0,
    // 本页用途，决定分组方式、右下角按钮和标题
    val mode: RomListMode = RomListMode.Manage,
    // 选择模式下已选的那一行，来源是 AppState.createVmRowId，认行不认 romKey
    val selectedRowId: String? = null,
    // 选完之后的回填值，页面据此回到上一页
    val pickedKey: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && groups.isEmpty()
    val isPick: Boolean get() = mode == RomListMode.Pick

    // AppBar 标题，两个分支共用
    val title: String get() = if (isPick) RomTexts.LIST_TITLE_PICK else RomTexts.LIST_TITLE_MANAGE
}

enum class RomConfirmKind { Delete, ClearCache, Repair }

// 只告知不询问的对话框内容，三个字段都由这一层给
data class RomNotice(
    val title: String,
    val message: String,
    val confirmText: String,
)

data class RomConfirm(
    // 确认后按 rowId 去删、去清、去重解，不能按 romKey
    val rowId: String,
    val kind: RomConfirmKind,
    val title: String,
    val message: String,
    val confirmText: String,
)

sealed interface RomListAction {
    data object Refresh : RomListAction
    data class Import(val uri: Uri) : RomListAction
    // 行内动作一律带 rowId，同 key 的内置行和本地行是两个对象
    data class RequestDelete(val rowId: String) : RomListAction
    data object DismissDelete : RomListAction
    data object ConfirmDelete : RomListAction
    data class RequestClearCache(val rowId: String) : RomListAction
    data object DismissClearCache : RomListAction
    data object ConfirmClearCache : RomListAction

    // 管理模式下新建一台虚拟机
    data class Create(val rowId: String) : RomListAction

    // 选择模式下回填选择并返回上一页，不创建
    data class Pick(val rowId: String) : RomListAction

    // 页面已经回来，清掉一次性事件
    data object ConsumePicked : RomListAction

    // 查一遍解包内容是否完整，只读
    data class CheckRom(val rowId: String) : RomListAction

    // 从原包重解。内置和本地同一条链，先确认再动手
    data class RequestRepair(val rowId: String) : RomListAction

    data object ConfirmRepair : RomListAction
    data object DismissRepair : RomListAction
    data object DismissNotice : RomListAction
    data object ConsumeMessage : RomListAction
    data object ConsumeCreated : RomListAction

    // 闪烁做完了，清掉高亮，免得下次进列表又闪
    data object ConsumeFocus : RomListAction
}

/**
 * 列表页的唯一逻辑。扫描、导入、删除、清理、检查、重解、创建与选择都在这里，两个 UI 分支共用。
 *
 * 进页面不卡靠三条。第一帧用 AppState 的内存快照直接分组，刷新时先发布快照再后台扫，
 * 中间不清空；扫描按 revision 缓存，一次进入只扫一遍。
 */
class RomListViewModel(
    private val context: Context,
    private val appState: AppState,
    private val scope: CoroutineScope,
    private val mode: RomListMode = RomListMode.Manage,
) {
    private val repository = RomRepository.get(context)

    private val _state = MutableStateFlow(
        RomListState(
            mode = mode,
            selectedRowId = if (mode == RomListMode.Pick) appState.createVmRowId.value else null,
            // 第一帧直接用内存里已经扫过的行画出来，不读盘，也不闪空态
            rows = appState.romRows.value,
            groups = groupsOf(appState.romRows.value, selectionOf(null)),
            isLoading = appState.romRows.value.isEmpty(),
        ),
    )
    val state: StateFlow<RomListState> = _state.asStateFlow()
    private var loadJob: Job? = null

    // 等着落地的定位请求，来自第一屏的修复链
    private var pendingFocus: RomFocus? = null

    // 当前页里某个 key 的原始行。确认框文案要用 usedByNames，RomItem 里没有
    private var lastRows: List<RomRow> = appState.romRows.value

    init {
        // init 只登记任务，不读盘
        refresh()
        scope.launch {
            appState.romRows.collect { rows ->
                if (rows.isNotEmpty() || !_state.value.isLoading) {
                    lastRows = rows
                    publish(rows)
                }
            }
        }
        // 定位请求来自第一屏那个 Activity，只能监听 AppState，页面传参收不到
        scope.launch {
            snapshotFlow { appState.romFocus }.collect { focus ->
                if (focus != null) applyFocus(focus)
            }
        }
        // 选择模式只有这一个来源，新建页和选择页共用 createVmRowId
        if (mode == RomListMode.Pick) {
            scope.launch {
                appState.createVmRowId.collect { rowId ->
                    _state.update { it.copy(selectedRowId = rowId, groups = groupsOf(lastRows, rowId)) }
                }
            }
        }
    }

    fun dispatch(action: RomListAction) {
        when (action) {
            RomListAction.Refresh -> refresh()
            is RomListAction.Import -> import(action.uri)
            is RomListAction.RequestDelete -> {
                val row = row(action.rowId) ?: return
                _state.update {
                    it.copy(
                        confirm = RomConfirm(
                            rowId = row.rowId,
                            kind = RomConfirmKind.Delete,
                            title = "删除 " + RomTexts.titleWithVersion(row) + "？",
                            message = RomTexts.deleteMessage(row),
                            confirmText = "删除",
                        ),
                    )
                }
            }

            RomListAction.DismissDelete, RomListAction.DismissClearCache, RomListAction.DismissRepair ->
                _state.update { it.copy(confirm = null) }

            RomListAction.DismissNotice -> _state.update { it.copy(notice = null) }

            RomListAction.ConfirmDelete -> when (_state.value.confirm?.kind) {
                RomConfirmKind.Delete -> confirmDelete()
                else -> Unit
            }

            is RomListAction.CheckRom -> check(action.rowId)

            is RomListAction.RequestRepair -> {
                val row = row(action.rowId) ?: return
                _state.update {
                    it.copy(
                        confirm = RomConfirm(
                            rowId = row.rowId,
                            kind = RomConfirmKind.Repair,
                            title = RomTexts.repairTitle(),
                            message = RomTexts.repairPromptMessage(row),
                            confirmText = "重新解包",
                        ),
                    )
                }
            }

            RomListAction.ConfirmRepair -> when (_state.value.confirm?.kind) {
                RomConfirmKind.Repair -> confirmRepair()
                else -> Unit
            }

            is RomListAction.RequestClearCache -> {
                val row = row(action.rowId) ?: return
                _state.update {
                    it.copy(
                        confirm = RomConfirm(
                            rowId = row.rowId,
                            kind = RomConfirmKind.ClearCache,
                            title = "清理解包缓存？",
                            message = RomTexts.clearCacheMessage(row),
                            confirmText = "清理",
                        ),
                    )
                }
            }

            RomListAction.ConfirmClearCache -> when (_state.value.confirm?.kind) {
                RomConfirmKind.ClearCache -> confirmClearCache()
                else -> Unit
            }

            is RomListAction.Create -> create(action.rowId)

            is RomListAction.Pick -> pick(action.rowId)

            RomListAction.ConsumePicked -> _state.update { it.copy(pickedKey = null) }
            RomListAction.ConsumeMessage -> _state.update { it.copy(message = null) }
            RomListAction.ConsumeCreated -> _state.update { it.copy(createdVmId = null) }
            RomListAction.ConsumeFocus -> _state.update { it.copy(highlightKey = null) }
        }
    }

    // 选择模式只回填选择，不创建。创建要名字和错误处理，属于新建页
    private fun pick(rowId: String) {
        if (mode != RomListMode.Pick) return
        val row = row(rowId) ?: return
        val key = row.key
        // 已经在用就什么都不做，不弹提示也不 pop，免得用户以为没反应
        if (_state.value.selectedRowId == rowId) return
        val usable = row.isReady || (!row.placeholder && row.hasArchive)
        if (!usable) {
            _state.update { it.copy(message = RomTexts.pickUnavailable(RomTexts.item(row))) }
            return
        }
        appState.createVmRomId.value = key
        appState.createVmRowId.value = rowId
        _state.update { it.copy(selectedRowId = rowId, pickedKey = key) }
    }

    // 收到定位请求先重扫一次，落地时再算位置并触发自动修复
    private fun applyFocus(focus: RomFocus) {
        pendingFocus = focus
        refresh()
    }

    // 定位并自动修复。0.5 秒是从列表首帧起算的规格，用来避开进入动画和第一次测量
    private fun resolveFocus(rows: List<RomRow>) {
        val focus = pendingFocus ?: return
        pendingFocus = null
        appState.consumeRomFocus()
        // 先按 rowId 定位。同版本可能有内置与本地两行，只按 romKey 取第一条会修错那一份
        val row = focus.rowId?.let { id -> rows.firstOrNull { it.rowId == id } }
            ?: rows.firstOrNull { it.key == focus.key }
        if (row == null) {
            _state.update { it.copy(message = "这条 ROM 已经不在列表里了") }
            return
        }
        scope.launch {
            delay(FOCUS_DELAY_MILLIS)
            _state.update { it.copy(highlightKey = row.key, blinkToken = it.blinkToken + 1) }
            /**
             * 定位不等于修复。解包是 GB 级、几十秒起的动作，只有启动链发来的请求才真解；
             * 只定位的请求到这里就结束，不能因为有人发过一次定位就替他解一份 ROM
             */
            if (focus.repairVmId == null) return@launch
            // 原包不在就只闪一下。弹一句他此刻什么都做不了的缺失提示，比不提示更糟
            if (!row.hasArchive || row.placeholder) return@launch
            if (!row.hasArchive || row.placeholder) return@launch
            _state.update { it.copy(message = "已为你自动发起修复：" + RomTexts.titleWithVersion(row)) }
            repair(row.rowId, focus.repairVmId)
        }
    }

    private fun refresh() {
        // 先发布内存快照再扫盘，列表内容不会先被清空再填回来
        val snapshot = appState.romRows.value
        if (snapshot.isNotEmpty()) {
            lastRows = snapshot
            publish(snapshot)
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching { repository.rows() }.getOrElse { emptyList() }
            }
            lastRows = rows
            publish(rows)
            resolveFocus(rows)
        }
    }

    private fun publish(rows: List<RomRow>) {
        val selected = selectionOf(_state.value.selectedRowId)
        _state.update {
            it.copy(rows = rows, groups = groupsOf(rows, selected), isLoading = false)
        }
    }

    private fun selectionOf(current: String?): String? =
        if (mode == RomListMode.Pick) current ?: appState.createVmRowId.value else null

    // 分组和排序只在 RomGrouping 里做。管理页和选择页共用一套分组，维度只有来源
    private fun groupsOf(rows: List<RomRow>, selectedRowId: String?): List<RomGroup> =
        RomGrouping.groups(rows, selectedRowId)

    private fun import(uri: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, busyText = "正在读取 ROM 包…") }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repository.importFromUri(uri) { progress ->
                        _state.update { current -> current.copy(busyText = RomTexts.progress(progress)) }
                    }
                }
            }
            _state.update { current ->
                current.copy(
                    busy = false,
                    busyText = "",
                    message = result.fold(
                        onSuccess = { outcome ->
                            if (outcome.duplicate) RomTexts.duplicate(outcome.row) else RomTexts.imported(outcome.row)
                        },
                        onFailure = { failure -> failure.message ?: "导入失败，请重试" },
                    ),
                )
            }
            refresh()
            appState.refreshRoms()
        }
    }

    private fun row(rowId: String): RomRow? = lastRows.firstOrNull { it.rowId == rowId }

    private fun confirmDelete() {
        val target = _state.value.confirm ?: return
        val row = row(target.rowId)
        val title = row?.let { RomTexts.title(it) } ?: target.rowId
        _state.update { it.copy(confirm = null, busy = true, busyText = "正在删除 " + title + "…") }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // 按 rowId 删。内置行与本地行、同来源两份内容，删除的对象必须是用户点的那一行
                runCatching { repository.delete(target.rowId) }
            }
            _state.update { current ->
                current.copy(
                    busy = false,
                    busyText = "",
                    message = result.fold(
                        onSuccess = { row -> RomTexts.deleted(row) },
                        onFailure = { failure -> failure.message ?: "删除失败" },
                    ),
                )
            }
            refresh()
            appState.refreshRoms()
        }
    }

    private fun confirmClearCache() {
        val target = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null, busy = true, busyText = "正在清理缓存…") }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.clearCache(target.rowId) }
            }
            _state.update { current ->
                current.copy(
                    busy = false,
                    busyText = "",
                    message = result.fold(
                        onSuccess = { row -> RomTexts.cacheCleared(row) },
                        onFailure = { failure -> failure.message ?: "清理失败" },
                    ),
                )
            }
            refresh()
            appState.refreshRoms()
        }
    }

    // 检查只读清单和派生层是否一致，与包从哪来无关
    private fun check(rowId: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, busyText = RomTexts.checking(), notice = null) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.checkRom(rowId) }
            }
            _state.update { current ->
                result.fold(
                    onSuccess = { report ->
                        when {
                            report.healthy -> current.copy(
                                busy = false,
                                busyText = "",
                                message = RomTexts.checkHealthy(report),
                            )

                            report.canRepair -> current.copy(
                                busy = false,
                                busyText = "",
                                message = RomTexts.checkProblems(report),
                                confirm = RomConfirm(
                                    rowId = rowId,
                                    kind = RomConfirmKind.Repair,
                                    title = RomTexts.repairTitle(),
                                    message = RomTexts.repairMessage(report),
                                    confirmText = "重新解包",
                                ),
                            )

                            else -> current.copy(
                                busy = false,
                                busyText = "",
                                notice = RomNotice(
                                    title = RomTexts.checkTitle(row(rowId)),
                                    message = RomTexts.checkNoArchive(),
                                    confirmText = "知道了",
                                ),
                            )
                        }
                    },
                    onFailure = { failure ->
                        current.copy(
                            busy = false,
                            busyText = "",
                            message = failure.message ?: "检查失败",
                        )
                    },
                )
            }
        }
    }

    // 确认重新解包。复用 RomRepository.ensureUsable，从原包重解并逐条校验
    private fun confirmRepair() {
        val target = _state.value.confirm ?: return
        repair(target.rowId, null)
    }

    // 重新解包。手动确认和启动链自愈走同一条实现，区别只是后者修完要请求那台虚拟机重启
    private fun repair(rowId: String, repairVmId: String?) {
        if (_state.value.busy) return
        val target = row(rowId)
        val key = target?.key ?: rowId
        // 带上内容指纹。同 key 有两份内容时，重解要解的是用户点的那一份
        val payload = target?.payloadSha256.orEmpty()
        /**
         * 首次解包与修坏包是同一条链，但说法不同。第一次用内置包不能说正在重新解包，
         * 用户会以为系统在修一个他没见过的东西
         */
        val firstTime = target?.isReady != true
        val busyHead = if (firstTime) RomTexts.extracting() else RomTexts.repairing()
        _state.update { it.copy(confirm = null, busy = true, busyText = busyHead) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repository.ensureUsable(
                        key = key,
                        payloadSha256 = payload,
                        progress = { progress ->
                            _state.update { current ->
                                current.copy(busyText = busyHead + RomTexts.progress(progress))
                            }
                        },
                    )
                }
            }
            val repairedRow = row(rowId)
            _state.update { current ->
                current.copy(
                    busy = false,
                    busyText = "",
                    message = result.fold(
                        onSuccess = {
                            if (firstTime) RomTexts.extractedNow(repairedRow) else RomTexts.repaired(repairedRow)
                        },
                        onFailure = { failure -> failure.message ?: "解包失败" },
                    ),
                )
            }
            refresh()
            appState.refreshRoms()
            if (result.isSuccess && repairVmId != null) appState.requestVmRestart(repairVmId)
        }
    }

    private companion object {
        // 列表首帧后的固定等待，给进入动画和第一次测量留时间，不要改成 0
        const val FOCUS_DELAY_MILLIS = 500L
    }

    private fun create(rowId: String) {
        if (_state.value.busy) return
        val key = row(rowId)?.key ?: rowId
        _state.update { it.copy(busy = true, busyText = "正在准备虚拟机…") }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 创建不解包。只有行内重新解包和启动时发现缺解包体，才会去解
                    val row = lastRows.firstOrNull { it.rowId == rowId }
                    // 建实例时把用的是哪一份一起写进去
                    appState.create(key, row?.payloadSha256.orEmpty(), row?.source)
                }
            }
            _state.update {
                it.copy(
                    busy = false,
                    busyText = "",
                    createdVmId = result.getOrNull()?.id,
                    message = result.exceptionOrNull()?.message
                        ?: if (result.getOrNull() == null) "创建失败，请检查存储空间" else null,
                )
            }
            appState.refresh()
            appState.refreshRoms()
        }
    }
}

@Composable
fun rememberRomListViewModel(mode: RomListMode = RomListMode.Manage): RomListViewModel {
    val context = LocalContext.current
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()
    return remember(context, appState, scope, mode) {
        RomListViewModel(context, appState, scope, mode)
    }
}