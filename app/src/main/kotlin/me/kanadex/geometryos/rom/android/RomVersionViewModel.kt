// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.a10vm.ui.verse.AppState
import io.a10vm.ui.verse.LocalAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kanadex.geometryos.rom.RomRepository
import me.kanadex.geometryos.rom.RomKeys
import me.kanadex.geometryos.rom.RomSwitch
import me.kanadex.geometryos.rom.RomTexts
import me.kanadex.geometryos.rom.RomVersionEntry
import me.kanadex.geometryos.rom.RomVersionPolicy

/**
 * 版本管理页的状态、动作与 ViewModel。两个 UI 分支共用这一份。
 * 判定在 RomVersionPolicy，写库与关机只走 AppState，这里负责把选版本这件事
 * 串成一条用户看得懂的流程。
 */

// 版本列表里的一项，文案已经由这一层拼好
data class RomVersionOption(
    // 目标 romKey，写库时就是 rom_id
    val key: String,
    /**
     * 这一项的身份，由来源、romKey 和内容指纹拼成。动作认它不认 uiKey，
     * 同版本的两份靠它分开。它同时是列表 key，重复会直接崩。
     */
    val rowId: String,
    // 列表渲染用的稳定 key，和 rowId 同源。使用最新版本那一行另加前缀
    val uiKey: String,
    val title: String,
    val summary: String,
    val selected: Boolean,
    val enabled: Boolean,
)

// 需要确认的切换，标题和正文都来自 RomTexts
data class RomVersionConfirm(
    // 目标那一行，不是 romKey
    val targetRowId: String,
    val title: String,
    val message: String,
    val confirmText: String,
)

/**
 * 版本管理页状态，不含 Compose 类型。页面按 [options] 画单选、按 [restartPrompt] 弹重启、
 * 按 [working] 盖加载框。
 */
data class RomVersionState(
    // ROM 的展示名，分组标题用
    val romName: String = "",
    // 当前使用的版本，缺失时写不可用
    val currentText: String = "",
    // 顶部使用最新版本那一行，没有可用版本就是 null
    val latest: RomVersionOption? = null,
    val options: List<RomVersionOption> = emptyList(),
    val isLoading: Boolean = true,
    val confirm: RomVersionConfirm? = null,
    val restartPrompt: Boolean = false,
    val working: Boolean = false,
    val restartCompleted: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    // 这台虚拟机还没绑定任何 ROM
    val unavailable: Boolean = false,
    // 当前 rom_id 指向的那一版已经不在了
    val selectedMissing: Boolean = false,
    // 同系列里有比当前更新的版本
    val latestNewer: Boolean = false,
    // 选中的那一版还没解包。页面把它交给 ROM 列表的解包链，解包要进度也能中断
    val handoffKey: String? = null,
    // 同上，交给解包链的是那一行，同版本两份内容时不能只给 key
    val handoffRowId: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && options.isEmpty()
}

sealed interface RomVersionAction {
    data object Refresh : RomVersionAction

    // 点列表里的某一版，带的是那一行的 rowId
    data class Select(val rowId: String) : RomVersionAction

    // 点顶部使用最新版本
    data object SelectLatest : RomVersionAction

    data object ConfirmSwitch : RomVersionAction
    data object CancelSwitch : RomVersionAction
    data object ConfirmRestart : RomVersionAction
    data object CancelRestart : RomVersionAction
    data object ConsumeRestartCompleted : RomVersionAction
    data object ConsumeMessage : RomVersionAction
    data object DismissError : RomVersionAction

    // 交给解包链之后清掉，免得回来又触发一次
    data object ConsumeHandoff : RomVersionAction
}

/**
 * 版本管理的唯一逻辑，两个 UI 分支共用。判定在 [RomVersionPolicy]，
 * 写库只走 AppState.setRomVersion，关机只走 AppState.stop；
 * 启动由页面在重启完成后按标准入口发起，和分辨率页是同一套时序。
 */
class RomVersionViewModel(
    private val context: Context,
    private val appState: AppState,
    private val vmId: String,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val STOP_TIMEOUT_MILLIS = 90_000L
        const val STOP_POLL_MILLIS = 200L
    }

    private val repository = RomRepository.get(context)
    private val _state = MutableStateFlow(RomVersionState())
    val state: StateFlow<RomVersionState> = _state.asStateFlow()

    private var entries: List<RomVersionEntry> = emptyList()
    private var deviceRomId: String = ""

    // 这台虚拟机绑定的是哪一份。空 key 表示还没选
    private var currentRef: me.kanadex.geometryos.rom.RomRef = me.kanadex.geometryos.rom.RomRef("")

    init {
        // 只订阅已经加载好的注册表，不在构造里读库
        scope.launch {
            appState.devices.collect { devices ->
                val device = devices.firstOrNull { it.id == vmId } ?: return@collect
                deviceRomId = device.romId
                currentRef = me.kanadex.geometryos.rom.RomRef(
                    key = device.romId,
                    source = me.kanadex.geometryos.rom.RomSource.values()
                        .firstOrNull { it.name == device.romSource },
                    payloadSha256 = device.romPayload,
                )
                refresh()
            }
        }
    }

    fun dispatch(action: RomVersionAction) {
        when (action) {
            RomVersionAction.Refresh -> refresh()
            is RomVersionAction.Select -> select(action.rowId)
            RomVersionAction.SelectLatest -> selectLatest()
            RomVersionAction.ConfirmSwitch -> confirmSwitch()
            RomVersionAction.CancelSwitch -> _state.update { it.copy(confirm = null) }
            RomVersionAction.ConfirmRestart -> confirmRestart()
            RomVersionAction.CancelRestart -> _state.update { it.copy(restartPrompt = false) }
            RomVersionAction.ConsumeRestartCompleted -> _state.update { it.copy(restartCompleted = false) }
            RomVersionAction.ConsumeMessage -> _state.update { it.copy(message = null) }
            RomVersionAction.DismissError -> _state.update { it.copy(error = null) }
            RomVersionAction.ConsumeHandoff -> _state.update { it.copy(handoffKey = null, handoffRowId = null) }
        }
    }

    private fun refresh() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { appState.romVersions(deviceRomId) }.getOrElse { emptyList() }
            }
            entries = loaded
            publish()
        }
    }

    // 把版本条目和当前身份算成两段列表，上面是最新版本，下面是同系列版本。两处都按身份比
    private fun publish() {
        val id = me.kanadex.geometryos.rom.RomKeys.idOf(deviceRomId)
        val current = RomVersionPolicy.match(entries, currentRef)
        val latestAvailable = RomVersionPolicy.latestAvailable(entries, id)
        val latest = latestAvailable?.let { entry ->
            val same = current != null && RomVersionPolicy.sameIdentity(current, entry)
            RomVersionOption(
                key = entry.key,
                rowId = entry.rowId,
                uiKey = "latest#" + entry.rowId,
                title = RomTexts.VERSION_LATEST_TITLE,
                summary = if (same) RomTexts.VERSION_LATEST_ALREADY
                else RomTexts.versionLatestSwitchTo(entry),
                selected = same,
                enabled = !same && RomVersionPolicy.canSwitch(deviceRomId, entry.key),
            )
        }
        // 行摘要只写这一行的真实值，不评论同版本另一份内容是否相同
        val options = entries.map { entry ->
            val same = current != null && RomVersionPolicy.sameIdentity(current, entry)
            RomVersionOption(
                key = entry.key,
                rowId = entry.rowId,
                uiKey = entry.rowId,
                title = entry.nameWithVersion,
                summary = when {
                    entry.placeholder -> RomTexts.VERSION_PLACEHOLDER
                    !entry.available -> RomTexts.versionNeedsExtract(entry)
                    else -> RomTexts.versionReady(entry)
                },
                selected = same,
                // 同系列才能选，占位包永远不能选；正在使用的那一份禁用，同版本的另一份仍可选
                enabled = !entry.placeholder &&
                    !same &&
                    RomVersionPolicy.canSwitch(deviceRomId, entry.key),
            )
        }
        val currentVersion = RomKeys.versionOf(deviceRomId)
        val latestNewer = latestAvailable?.let { candidate ->
            currentVersion.isNotEmpty() && RomKeys.compareVersions(candidate.version, currentVersion) > 0
        } ?: false
        _state.update {
            it.copy(
                romName = current?.displayName
                    ?: entries.firstOrNull()?.displayName
                    ?: id,
                currentText = current?.nameWithVersion ?: (deviceRomId.ifEmpty { "—" } + "（不可用）"),
                selectedMissing = current == null && entries.isNotEmpty(),
                latestNewer = latestNewer,
                latest = latest,
                options = options,
                isLoading = false,
                unavailable = entries.isEmpty(),
            )
        }
    }

    private fun selectLatest() {
        val id = me.kanadex.geometryos.rom.RomKeys.idOf(deviceRomId)
        val target = RomVersionPolicy.latestAvailable(entries, id)
        if (target == null) {
            _state.update { it.copy(message = RomTexts.VERSION_LATEST_UNAVAILABLE) }
            return
        }
        select(target.rowId)
    }

    // 选一个版本。同一份就给一句话，降级或同版本另一份内容要确认，跨系列直接拒绝
    private fun select(rowId: String) {
        val from = RomVersionPolicy.match(entries, currentRef)
        val to = entries.firstOrNull { it.rowId == rowId }
        if (to == null) {
            _state.update { it.copy(message = RomTexts.VERSION_LATEST_UNAVAILABLE) }
            return
        }
        if (to.placeholder) {
            _state.update { it.copy(message = RomTexts.VERSION_PLACEHOLDER) }
            return
        }
        // 要问的只有降级和同版本另一份内容。来源不同但内容一样不问，问了也只有一个答案
        val kind = RomVersionPolicy.switchKind(from, to)
        when {
            kind == RomSwitch.Same -> {
                _state.update { it.copy(message = RomTexts.switched(to)) }
            }

            kind == RomSwitch.DifferentSeries -> {
                _state.update { it.copy(error = RomTexts.crossSeriesMessage(from, to)) }
            }

            RomVersionPolicy.requiresWarning(kind) -> {
                val downgrade = kind == RomSwitch.Downgrade
                _state.update {
                    it.copy(
                        confirm = RomVersionConfirm(
                            targetRowId = rowId,
                            title = if (downgrade) RomTexts.DOWNGRADE_TITLE else RomTexts.SWITCH_SAME_VERSION_TITLE,
                            message = when {
                                from == null -> RomTexts.versionLatestSwitchTo(to)
                                downgrade -> RomTexts.downgradeMessage(from, to)
                                else -> RomTexts.switchSameVersionMessage(from, to)
                            },
                            confirmText = if (downgrade) "降级" else "切换",
                        ),
                    )
                }
            }

            else -> apply(rowId)
        }
    }

    private fun confirmSwitch() {
        val target = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        apply(target.targetRowId)
    }

    // 先写库，写成功才改界面、才谈重启。顺序反了会出现界面说切了、重启后还是老 ROM
    private fun apply(rowId: String) {
        val target = entries.firstOrNull { it.rowId == rowId } ?: return
        // 结果句要说出切到了哪一份 ROM，同版本号也可能有多个
        val doneText = RomTexts.switched(target)
        scope.launch {
            /**
             * 写进库的是身份，不只是 romKey。只写 romKey 时本地那份和内置那份会写成同一个字符串，
             * 用户看到切换成功，重启后跑的还是原来那一份
             */
            val saved = withContext(Dispatchers.IO) {
                appState.setRomVersion(vmId, target.key, target.payloadSha256, target.source)
            }
            if (!saved) {
                _state.update { it.copy(error = RomTexts.SAVE_FAILED) }
                return@launch
            }
            /**
             * 这一版还不能用就交给 ROM 列表的解包链，解完它自己会请求重启回到启动页
             * 这一页不解包，解包要进度也要能中断
             */
            if (!target.available) {
                _state.update {
                    it.copy(
                        message = doneText,
                        // 交出去的是这一行，含指纹。只给 romKey 会去解同版本的另一份内容
                        handoffKey = target.key,
                        handoffRowId = target.rowId,
                        error = null,
                    )
                }
                return@launch
            }
            val runtime = appState.runtime(vmId)
            val needsRestart = RomVersionPolicy.requiresRestart(
                running = runtime.running,
                busy = runtime.busy,
                stopping = runtime.stopping,
            )
            _state.update {
                it.copy(
                    message = doneText,
                    restartPrompt = needsRestart,
                    error = null,
                )
            }
            refresh()
        }
    }

    /**
     * 重启走标准关机，加载框盖到关机结束。这里不接管启动，页面收到重启完成信号后
     * 自己按标准入口发起，用户看到的是启动页。
     */
    private fun confirmRestart() {
        _state.update { it.copy(restartPrompt = false, working = true) }
        appState.stop(vmId)
        scope.launch {
            val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                val runtime = appState.runtime(vmId)
                if (io.a10vm.ui.vm.VmActions.settled(runtime)) {
                    _state.update { it.copy(working = false, restartCompleted = true) }
                    return@launch
                }
                delay(STOP_POLL_MILLIS)
            }
            _state.update { it.copy(working = false, error = RomTexts.STOP_TIMEOUT) }
        }
    }
}

@Composable
fun rememberRomVersionViewModel(vmId: String): RomVersionViewModel {
    val appState = LocalAppState.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(appState, context, scope, vmId) {
        RomVersionViewModel(context, appState, vmId, scope)
    }
}