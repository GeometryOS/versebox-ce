// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.a10vm.runtime.VmDevices
import io.a10vm.ui.BootLogText
import me.kanadex.geometryos.boot.BootGateSequence
import io.a10vm.ui.verse.VmRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kanadex.geometryos.rom.RomRef
import me.kanadex.geometryos.rom.RomSource
import me.kanadex.geometryos.rom.RomRepository

/**
 * 第一屏的唯一逻辑实现（`docs/ui-rework/09/05` §4）。
 *
 * 职责边界：
 * - **它**把运行态翻译成 [BootUiState]：阶段、主提示、状态胶囊、等待秒数、日志队列、故障、弹窗；
 * - **它**决定 C 类该怎么分诊（C1–C4）、弹窗只弹一次、重新尝试时清空上一轮；
 * - **它**在 IO 上抓快照、打包、判 ROM；
 * - 两个 UI 分支只做两件事：渲染 [BootUiState]、把点击翻译成 [BootAction]。
 *
 * 「出过首帧」与「运行过」都是**粘性**的：一旦为真就不再回退。否则客体框架进程重启时
 * 服务会把 `frameReady` 重新报成 false，第一屏又会盖上来——那正是「框架退出仍被启动页遮挡」的病根。
 */
internal class BootViewModel(
    private val context: Context,
    private val vmId: String,
    private val vmName: String,
    private val startGuest: () -> Unit,
    private val navigate: (BootGateSequence.Result) -> Unit,
    private val romLine: String,
    runtime: StateFlow<VmRuntime>,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        BootUiState(
            vmName = vmName,
            romLine = romLine,
            headline = BootTexts.headline(vmName, stopping = false),
            stageLine = BootTexts.STAGE_IDLE,
        ),
    )
    val state: StateFlow<BootUiState> = _state.asStateFlow()

    /** 已入过队的原始行：运行时的 bootLines 是累计列表，必须去重。 */
    private val seenLines = LinkedHashSet<String>()
    private val devices = VmDevices(context)
    private val roms = RomRepository.get(context)
    private val sequence = BootGateSequence(context.resources)
    private var gateJob: kotlinx.coroutines.Job? = null
    private var pendingGateResponse: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /** 上一次看到的启动会话：变了就是「又启动了一次」，日志要清空重来。 */
    private var lastSession = ""

    /** 见过一次真正的启动尝试（busy / running）。用来区分「启动失败」与「只是断开了连接」。 */
    private var sawAttempt = false

    /**
     * 出过首帧（粘性）。
     *
     * 粘性只作用于**同一次运行会话**：用户主动重新进入这台虚拟机的窗口时，必须重新走干净的
     * 启动路径（有进度、有日志），所以 `onStart` 会把它清掉；框架进程退出后服务把
     * `frameReady` 报回 false 的那次，**不许**让它回退（否则启动页会盖住最后一帧）。
     */
    private var hadFirstFrame = false

    /** 粘性：真的跑起来过。用来判断「不是运行中」到底是关机、还是压根没起来。 */
    private var sawRunning = false

    /** 上一次异常还没被看过的标记：重新进入时据此把对话框再摆出来一次。 */
    private var pendingReport = false

    /** 上一次异常的一句话原因（空 = 没有）。 */
    private var lastFailure = ""

    /** 最近一次运行态快照：自动修复前要判断"服务是不是已经在修了"。 */
    private var lastRuntime: VmRuntime? = null

    private var resetToken = 0
    private val alertedSessions = HashSet<String>()

    /** 系统动画是否开启；关掉时日志必须一次性显示完整（`09/02` §1 的无障碍降级）。 */
    @Volatile
    private var animatorsEnabled = true

    init {
        scope.launch { animatorsEnabled = withContext(Dispatchers.IO) { animationsEnabled() } }
        scope.launch { runtime.collect { applyRuntime(it) } }
    }

    fun requestStart() {
        if (gateJob?.isActive == true || lastRuntime?.let { it.busy || it.running || it.stopping } == true) return
        _state.update { it.copy(fault = null, alert = null) }
        gateJob = scope.launch {
            try {
                val result = sequence.run(
                    binding = { withContext(Dispatchers.IO) { readBinding() } },
                    entries = { ref -> withContext(Dispatchers.IO) { roms.versionEntries()[ref.id].orEmpty() } },
                    inspect = { ref ->
                        withContext(Dispatchers.IO) {
                            if (readBinding() != ref) null else roms.rowFor(ref)?.let {
                                BootGateSequence.Inspection(roms.gate(it), it.key, it.rowId)
                            }
                        }
                    },
                    ask = ::askGate,
                )
                when (result) {
                    BootGateSequence.Result.NoInstance -> _state.update { it.copy(fault = BootFault.NoInstance) }
                    BootGateSequence.Result.Start -> startGuest()
                    else -> navigate(result)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.e("VerseBoxBoot", "Startup gate failed for $vmId", failure)
                _state.update { it.copy(fault = BootFault.Other(failure.message ?: BootTexts.FAILURE_UNKNOWN)) }
            }
        }
    }

    private fun readBinding(): RomRef? = devices.find(vmId)?.let { device ->
        RomRef(device.romId, RomSource.entries.firstOrNull { it.name == device.romSource }, device.romPayload)
    }

    fun cancelStart() {
        gateJob?.cancel()
    }

    private suspend fun askGate(gate: RomGate): Boolean {
        val response = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingGateResponse = response
        _state.update { it.copy(romGate = gate) }
        return try {
            response.await()
        } finally {
            pendingGateResponse = null
            _state.update { it.copy(romGate = null) }
        }
    }

    /**
     * 用户**主动**进入这台虚拟机的窗口。
     *
     * 两种进入要分开处理（2026-11-09 用户原话）：
     * 1. **关机中**强行进入：不许重来一遍启动 —— 保留现有日志与"正在关机"的事实，
     *    关完自动退出（这一条以前会清空日志、显示成"正在启动"、还卡住不退）；
     * 2. 正常进入：走干净的启动路径（上一轮异常以对话框摆出来）。
     */
    fun onEnterFromOutside() {
        val runtime = lastRuntime
        // 只要这台虚拟机**还有操作在飞**（正在启动 / 正在运行 / 正在关机），就绝不能当"新的一次启动"处理：
        // 那会把日志清空 —— 真机上就是"强行进入关机中的虚拟机，日志是空的"。
        val inFlight = runtime != null && (runtime.busy || runtime.running)
        if (inFlight) {
            val shuttingDown = runtime.stopping || (!runtime.running && runtime.busy)
            _state.update {
                it.copy(
                    stopping = shuttingDown || it.stopping,
                    exitRequested = false,
                    hadFirstFrame = false,
                    fault = null,
                    headline = BootTexts.headline(vmName, stopping = shuttingDown || it.stopping),
                    stageLine = if (shuttingDown) BootTexts.STAGE_STOPPING else it.stageLine,
                )
            }
            pendingReport = false
            return
        }
        sawRunning = false
        sawAttempt = false
        // 注意：**不清 lastSession**。清掉它会让下一次 bundle 被当成"新会话"，
        // 于是队列被清空 —— 真机上就表现为"强行进入关机中的虚拟机，日志是空的"。
        if (pendingReport && lastFailure.isNotEmpty()) {
            _state.update { it.copy(alert = BootTexts.alert(lastFailure)) }
        }
        pendingReport = false
        feedClear()
        resetToken += 1
        _state.update {
            it.copy(
                queue = emptyList(),
                resetToken = resetToken,
                fault = null,
                alert = _state.value.alert,
                hadFirstFrame = false,
                stopping = false,
                exitRequested = false,
                headline = BootTexts.headline(vmName, stopping = false),
                stageLine = BootTexts.STAGE_IDLE,
            )
        }
    }

    /** 上一次异常已被看过（弹窗点了忽略 / 已离开对话框）。 */
    private fun feedClear() {
        BootLogFeed.clear(seenLines)
        hadFirstFrame = false
    }

    fun dispatch(action: BootAction) {
        when (action) {
            BootAction.OpenRecord -> openRecord()
            BootAction.ShareSnapshot -> shareSnapshot()
            BootAction.ConfirmFault -> confirmFault()
            BootAction.DismissFault -> _state.update { it.copy(fault = null) }
            BootAction.DismissAlert -> _state.update { it.copy(alert = null) }
            BootAction.Restart -> restart()
            BootAction.Recheck -> requestStart()
            is BootAction.RespondToGate -> if (_state.value.romGate === action.gate) {
                pendingGateResponse?.complete(action.confirmed)
            } else Unit
            BootAction.ConsumeMessage -> _state.update { it.copy(message = null) }
        }
    }

    // ------------------------------------------------------------------ 运行态

    private fun applyRuntime(runtime: VmRuntime) {
        lastRuntime = runtime
        if (runtime.frameReady || runtime.frames > 0L) hadFirstFrame = true
        if (runtime.running) sawRunning = true
        if (runtime.busy || runtime.running) sawAttempt = true
        // 真的挂了（出过画面 / 见过启动尝试，且进入错误态）：记下来，等「重新进入」时再摆出来。
        if (runtime.failed && (hadFirstFrame || sawAttempt) && !runtime.disconnected) {
            if (lastFailure.isEmpty()) {
                lastFailure = runtime.detail.ifBlank { BootTexts.FAILURE_UNKNOWN }
                pendingReport = true
            }
        }

        val session = runtime.bootSession
        if (session.isNotEmpty() && session != lastSession) {
            lastSession = session
            // 新一轮启动：上一轮的日志与结论都不再成立。
            BootLogFeed.clear(seenLines)
            resetToken += 1
            // 新会话：上一轮的「跑过 / 该退出」也一并清掉，否则刚重开一次就会被判成「已停止」。
            sawRunning = false
            _state.update {
                it.copy(
                    queue = emptyList(),
                    resetToken = resetToken,
                    fault = null,
                    alert = null,
                    stopping = false,
                    exitRequested = false,
                )
            }
        }

        // 「停止中」：
        //  · 服务明确报了 stopping（用户点了停止，启动中/运行中都算）；
        //  · 或者跑起来过、现在不在运行、但服务还在忙（老路径的兜底）。
        val stopping = runtime.stopping || (sawRunning && !runtime.running && runtime.busy)
        // 停止完成（两种来源都算）：服务不再忙、也不在跑，且不是错误态。
        val stopFinished = (runtime.stopping.not() || !runtime.busy) && !runtime.running && !runtime.busy && !runtime.failed
        val exit = (sawRunning || runtime.stopping || _state.value.stopping) && stopFinished

        val stage = when {
            stopping -> BootTexts.STAGE_STOPPING
            else -> runtime.bootLines.lastOrNull()?.let { BootLogText.message(it) } ?: _state.value.stageLine
        }
        val seconds = (runtime.bootElapsedMillis / 1000L).coerceAtLeast(0L).toInt()
        _state.update {
            it.copy(
                phase = phaseOf(runtime, stopping),
                hadFirstFrame = hadFirstFrame,
                stopping = stopping && !exit,
                exitRequested = it.exitRequested || exit,
                headline = BootTexts.headline(vmName, stopping || it.stopping),
                stageLine = stage,
                waitSeconds = seconds,
                waitTone = BootTexts.waitTone(seconds),
                recordId = runtime.recordId ?: it.recordId,
            )
        }

        if (runtime.bootLines.isNotEmpty()) onLines(runtime.bootLines)
        maybeAlert(runtime)
        maybeRuntimeFault(runtime)
    }

    private fun phaseOf(runtime: VmRuntime, stopping: Boolean): BootPhase = when {
        stopping -> BootPhase.Stopping
        runtime.failed && hadFirstFrame -> BootPhase.NoPicture
        runtime.failed -> BootPhase.Failed
        hadFirstFrame -> BootPhase.Ready
        else -> BootPhase.Booting
    }

    // ------------------------------------------------------------------ 日志队列

    /**
     * 只把新行放进队列（节奏与位移在 UI 侧的同一次续体里完成，见 [BootLogFeed] 的说明）。
     *
     * 系统动画关闭时不走「逐行」：直接把队列交给 UI，由它一次性铺满（`09/02` §1）。
     */
    private fun onLines(lines: List<String>) {
        val fresh = BootLogFeed.fresh(lines, seenLines)
        if (fresh.isEmpty()) return
        _state.update { it.copy(queue = it.queue + fresh, resetToken = resetToken) }
    }

    // ------------------------------------------------------------------ 分诊

    private fun maybeAlert(runtime: VmRuntime) {
        // B 类：出过画面又停了。连接断开不算（窗口切后台时也会走到这里）。
        if (!hadFirstFrame || !runtime.failed || runtime.disconnected) return
        if (_state.value.alert != null) return
        val session = runtime.bootSession.ifEmpty { vmId }
        if (!alertedSessions.add(session)) return
        _state.update { it.copy(alert = BootTexts.alert(runtime.detail)) }
        lastFailure = runtime.detail.ifBlank { BootTexts.FAILURE_UNKNOWN }
        pendingReport = false
    }

    private fun maybeRuntimeFault(runtime: VmRuntime) {
        if (hadFirstFrame || !sawAttempt || !runtime.failed || runtime.disconnected) return
        if (_state.value.fault != null) return
        _state.update {
            it.copy(fault = BootFault.Other(runtime.detail.ifBlank { BootTexts.FAILURE_UNKNOWN }))
        }
    }

    // ------------------------------------------------------------------ 动作

    /** 「查看运行记录」：先关掉对话框，再走 09/05 的定位链路（不是直接进详情）。 */
    private fun openRecord() {
        val recordId = _state.value.recordId
        _state.update { it.copy(alert = null, fault = null) }
        BootLauncher.openRecord(context, vmId, recordId)
    }

    private fun confirmFault() {
        when (val fault = _state.value.fault) {
            null -> Unit
            BootFault.NoInstance -> {
                _state.update { it.copy(fault = null) }
                BootLauncher.openHome(context)
            }

            is BootFault.Other -> {
                _state.update { it.copy(fault = null) }
                shareSnapshot()
            }
        }
    }

    /** 抓四段日志打包成 zip 交给系统面板：读盘与压缩全在 IO（`05` §6）。 */
    private fun shareSnapshot() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, busyText = BootTexts.SNAPSHOT_PACKING) }
        scope.launch {
            // 优先复用记录中心的归档（同一个 RecordExporter），没有归档才抓实时片段。
            val payload = withContext(Dispatchers.IO) {
                runCatching {
                    BootSnapshot.preparePayload(context, vmId, _state.value.vmName, _state.value.recordId)
                }.getOrNull()
            }
            _state.update { it.copy(busy = false, busyText = "") }
            if (payload == null) {
                _state.update { it.copy(message = BootTexts.SNAPSHOT_FAILED, messageTitle = BootTexts.SNAPSHOT_SUBJECT) }
                return@launch
            }
            runCatching { BootSnapshot.share(context, payload) }
                .onFailure {
                    _state.update { current ->
                        current.copy(message = BootTexts.SHARE_FAILED, messageTitle = BootTexts.SNAPSHOT_SUBJECT)
                    }
                }
        }
    }

    /** 在**当前窗口**里重跑一次启动流程：不重建 Activity，日志清空成新的一段（`09/04` §3 第 4 条）。 */
    private fun restart() {
        sawAttempt = false
        BootLogFeed.clear(seenLines)
        resetToken += 1
        _state.update {
            it.copy(
                queue = emptyList(),
                resetToken = resetToken,
                fault = null,
                alert = null,
                hadFirstFrame = false,
                stopping = false,
                exitRequested = false,
                headline = BootTexts.headline(vmName, stopping = false),
                stageLine = BootTexts.STAGE_IDLE,
            )
        }
        requestStart()
    }

    private fun animationsEnabled(): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }.getOrDefault(true)
}

/**
 * ViewModel 的创建位置（`docs/ui-rework/02` §4.5）：key 覆盖全部构造参数，
 * 重组不重建、参数变了才重建。
 */
@Composable
internal fun rememberBootViewModel(
    vmId: String,
    vmName: String,
    romLine: String,
    runtime: StateFlow<VmRuntime>,
    startGuest: () -> Unit,
    navigate: (BootGateSequence.Result) -> Unit,
): BootViewModel {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, vmId, vmName, romLine, runtime, scope, startGuest, navigate) {
        BootViewModel(context, vmId, vmName, startGuest, navigate, romLine, runtime, scope)
    }
}