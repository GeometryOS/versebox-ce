// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.a10vm.runtime.RomCatalog
import io.a10vm.runtime.VmDevices
import io.a10vm.ui.BootLogText
import io.a10vm.ui.verse.VmRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kanadex.geometryos.rom.RomKeys
import me.kanadex.geometryos.rom.RomRepository
import me.kanadex.geometryos.rom.RomState
import me.kanadex.geometryos.rom.RomTexts
import me.kanadex.geometryos.rom.RomVersionDecision
import me.kanadex.geometryos.rom.RomVersionPolicy

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
class BootViewModel(
    private val context: Context,
    private val vmId: String,
    private val vmName: String,
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

    /** 旧格式（APK 里 assets/roms 下的 yaml）的 ROM 目录；只解析一次。 */
    private val legacyCatalog by lazy { RomCatalog(context) }

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

    /** 自动修复链只发起一次：避免 classify 反复判定时来回跳转（2026-11-09 用户要求"自动"）。 */
    private var autoRepairStarted = false

    /** 最近一次运行态快照：自动修复前要判断"服务是不是已经在修了"。 */
    private var lastRuntime: VmRuntime? = null

    private var resetToken = 0
    private var faultChecked = false
    private val alertedSessions = HashSet<String>()

    /** 系统动画是否开启；关掉时日志必须一次性显示完整（`09/02` §1 的无障碍降级）。 */
    @Volatile
    private var animatorsEnabled = true

    init {
        scope.launch { animatorsEnabled = withContext(Dispatchers.IO) { animationsEnabled() } }
        scope.launch { runtime.collect { applyRuntime(it) } }
        evaluateFault()
        evaluateRomGate()
    }

    /**
     * 启动闸门：**在启动页上**判定并弹窗（2026-11-12）。
     *
     * 为什么在启动页：点启动之后用户看到的就是这一屏。判定放在主页那一层，
     * 弹窗会被启动页盖住（上一个版本的真机事故）。
     *
     * 只读判据、不发 START、不改任何数据：真正的启动由 `AppState.start` 决定，
     * 用户点「继续启动」时由宿主 Activity 直接发 START（绕过版本闸门）。
     */
    private fun evaluateRomGate() {
        scope.launch {
            val device = withContext(Dispatchers.IO) { runCatching { devices.find(vmId) }.getOrNull() }
                ?: return@launch
            val romId = device.romId
            val entries = withContext(Dispatchers.IO) {
                runCatching { roms.versionEntries() }.getOrDefault(emptyMap())
            }
            val series = entries[RomKeys.idOf(romId)].orEmpty()
            val gate = when {
                romId.isBlank() || series.isEmpty() -> RomGate(
                    title = BootTexts.GATE_NEED_VERSION_TITLE,
                    message = BootTexts.gateMissing(if (romId.isBlank()) "（未记录）" else romId),
                    openVersionText = BootTexts.GATE_OPEN_VERSION,
                )
                else -> when (val decision = RomVersionPolicy.decision(series, romId)) {
                    is RomVersionDecision.CurrentMissing -> RomGate(
                        title = BootTexts.GATE_NEED_VERSION_TITLE,
                        message = decision.fallback?.let {
                            BootTexts.gateMissingWithNewer(
                                RomKeys.idOf(romId) + " " + RomKeys.versionOf(romId),
                                it.nameWithVersion,
                            )
                        } ?: BootTexts.gateMissing(romId),
                        openVersionText = BootTexts.GATE_OPEN_VERSION,
                    )
                    is RomVersionDecision.Unavailable -> RomGate(
                        title = BootTexts.GATE_NEED_VERSION_TITLE,
                        message = BootTexts.gateMissing(romId),
                        openVersionText = BootTexts.GATE_OPEN_VERSION,
                    )
                    is RomVersionDecision.UpdateAvailable -> RomGate(
                        title = RomTexts.UPDATE_TITLE,
                        message = BootTexts.gateUpdate(decision.current.nameWithVersion, decision.latest.nameWithVersion),
                        openVersionText = BootTexts.GATE_OPEN_VERSION,
                        continueText = BootTexts.GATE_CONTINUE,
                    )
                    RomVersionDecision.UpToDate -> null
                }
            }
            _state.update { it.copy(romGate = gate) }
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
        faultChecked = false
        autoRepairStarted = false
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
        evaluateFault()
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
            BootAction.Recheck -> {
                faultChecked = false
                evaluateFault()
            }

            BootAction.DismissRomGate -> _state.update { it.copy(romGate = null) }
            // 这两个由宿主 Activity 处理（要发 Intent / 直接发 START）；走到这里只清弹窗。
            BootAction.OpenRomVersion -> _state.update { it.copy(romGate = null) }
            BootAction.ContinueStart -> _state.update { it.copy(romGate = null) }
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
            faultChecked = false
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
            if (!hadFirstFrame) evaluateFault()
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

    /**
     * C 类分诊（`09/03` §2）。
     *
     * 只在「没有结论」时跑一次：先查最便宜的 `resolve()`（只读登记 + 目录存在性），
     * 只有在它给不出结果时才扫全表——这样正常启动的路径上不会为了自检去量目录。
     */
    private fun evaluateFault() {
        if (faultChecked) return
        faultChecked = true
        scope.launch {
            val fault = withContext(Dispatchers.IO) { classify() }
            // 运行态给出的 C4（[BootFault.Other]）不能被慢一步回来的 ROM 扫描结果抹掉。
            _state.update { current ->
                if (current.fault is BootFault.Other) current else current.copy(fault = fault)
            }
            // 解包坏了但原包还在 → **自动**走修复链（09/04 §2）：
            // 不是等用户点「去修复」，而是启动时发现就自己跳过去修，修完回到这里重跑。
            val damaged = fault as? BootFault.RomDamaged
            if (damaged != null && damaged.canRepair && !autoRepairStarted) {
                // 服务自己已经在修（BUSY + 那句"正在用原始 ROM 修复"）就让它修完，别两边同时跳。
                val serviceRepairing = lastRuntime?.busy == true
                if (!serviceRepairing) {
                    autoRepairStarted = true
                    _state.update { it.copy(message = BootTexts.autoRepair(damaged.romTitle)) }
                    confirmFault()
                }
            }
        }
    }

    private fun classify(): BootFault? {
        val device = runCatching { devices.find(vmId) }.getOrNull() ?: return BootFault.NoInstance
        val romId = device.romId
        if (romId.isEmpty()) return null
        // 1) 旧格式 ROM：内容跟着 APK 的 assets 走，没有可修的派生层。
        if (runCatching { legacyCatalog.list().any { it.id == romId } }.getOrDefault(false)) return null
        // 2) 登记行分级：查不到 = 原包不在了（C2）；状态坏 / 清单对不上 = 解包损坏（C3）。
        //
        // **判据只能是 payloadProblems（按清单逐条比对）**。这里原来先看 resolve()，而 resolve()
        // 只判"解包目录三件必需物在不在"——删掉 payload 里一个 build.prop 它照样返回非空，
        // 于是 C3 只在"整个目录都没了"时才出现（真机表现为"小概率才弹修复"）。
        val rows = runCatching { roms.rows() }.getOrElse { emptyList() }
        // **"查不到这一版"不在这里分类**：它属于启动闸门（RomGate）的事实。
        // 两个分类器各判一次，就是两个弹窗叠在一起（真机：上面一个"原包不存在"）。
        // 一个事实只允许一个分类器回答。
        val row = rows.firstOrNull { it.key == romId }
            ?: rows.firstOrNull { RomKeys.idOf(it.key) == romId }
            ?: return null
        val problems = runCatching { roms.payloadProblems(row) }.getOrDefault(emptyList())
        val broken = row.state == RomState.Failed || row.state == RomState.Missing || problems.isNotEmpty()
        if (!broken) return null
        val canRepair = runCatching { roms.hasArchive(row.key) }.getOrDefault(false)
        return BootFault.RomDamaged(
            key = row.key,
            rowId = row.rowId,
            romTitle = RomTexts.title(row) + " " + row.version,
            problems = problems,
            canRepair = canRepair,
        )
    }

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

            is BootFault.RomMissing -> {
                _state.update { it.copy(fault = null) }
                BootLauncher.openRomList(context)
            }

            is BootFault.RomDamaged -> {
                // 已经把人送去 ROM 管理页了：这条"正在自动修复"的提示在那边有进度弹窗承接，
                // 留在这里会在回来时又弹一次（真机上表现为"回来后莫名其妙一个对话框"）。
                _state.update { it.copy(fault = null, message = null) }
                // 启动窗口留在栈里不 finish：修完由 ROM 列表发回重启信号（09/04 §2）。
                BootLauncher.openRomRepair(context, fault.key, vmId, fault.rowId)
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
        faultChecked = false
        autoRepairStarted = false
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
        BootRestartBus.request(vmId)
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
fun rememberBootViewModel(
    vmId: String,
    vmName: String,
    romLine: String,
    runtime: StateFlow<VmRuntime>,
): BootViewModel {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, vmId, vmName, romLine, runtime, scope) {
        BootViewModel(context, vmId, vmName, romLine, runtime, scope)
    }
}