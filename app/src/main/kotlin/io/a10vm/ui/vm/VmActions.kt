// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.vm

import io.a10vm.records.RecordKind
import io.a10vm.records.RecordStatus
import io.a10vm.runtime.RenameResult
import io.a10vm.runtime.VmDevice
import io.a10vm.runtime.VmRegistry
import io.a10vm.ui.verse.AppState
import io.a10vm.ui.verse.VmRuntime

/**
 * 虚拟机列表 / 管理页共用的纯逻辑（不含 Compose）。
 *
 * 状态文案与按钮可用性照抄旧实现（ManagementDashboard.renderSelected）：
 * - 文案：运行中 / 正在准备 / 操作未完成 / 可以启动
 * - 规则：start / inspect / desktop 只在「空闲且未运行」可用；
 *   stop / fullscreen / install / importFiles / mic / camera 只在「运行中且不忙」可用；
 *   wifi / logs / delete 只要不忙就可用。
 * 只有这一份判断，Miuix 与 Material 3 两套页面都从这里取，避免两边行为漂移。
 */
object VmActions {

    /** 旧实现的 stateText：状态胶囊上的那行字。 */
    fun stateLabel(runtime: VmRuntime): String = when {
        runtime.state == "ERROR" -> "操作未完成"
        // 用户点了关机：卡片上必须马上看到"正在关机"，而不是继续显示"正在准备"。
        runtime.stopping -> "正在关机"
        runtime.busy && runtime.state == "BUSY" -> "正在准备"
        runtime.running -> "运行中"
        else -> "可以启动"
    }

    enum class Tone { Active, Busy, Error, Idle }

    fun tone(runtime: VmRuntime): Tone = when {
        runtime.state == "ERROR" -> Tone.Error
        runtime.stopping -> Tone.Busy
        runtime.busy -> Tone.Busy
        runtime.running -> Tone.Active
        else -> Tone.Idle
    }

    fun canStart(runtime: VmRuntime): Boolean = !runtime.busy && !runtime.running
    fun canEnter(runtime: VmRuntime): Boolean = runtime.running || runtime.state == "BUSY" && runtime.busy
    fun launchLabel(runtime: VmRuntime): String = if (canEnter(runtime)) "进入" else "启动"
    /**
     * 能不能点「停止」。
     *
     * 判据放宽到「只要不是干净的 IDLE」：真机出现过实例停在 BUSY（解包自愈 / 客体 spawn 卡住）
     * 或 ERROR（会话半死）时，列表里连停止都点不了，用户只能干等（2026-11-09）。
     * 停止本身是幂等的（服务端对空闲实例 close 一次就返回），所以放开没有副作用。
     */
    fun canStop(runtime: VmRuntime): Boolean =
        runtime.running || runtime.busy || runtime.state == "BUSY" || runtime.state == "ERROR"

    /**
     * 改了显示设置之后，**这次改动需不需要重启才能生效**。
     *
     * 判据只有一份（`me.kanadex.geometryos.display.DisplayChangePolicy`）：会话在跑
     * （真正运行 / 正在开机 / 正在关机）就要重启；干净空闲就不用——下次启动自然生效。
     *
     * 页面不许自己写这个 if：两个分支各写一遍，迟早出现"开机中算不算运行"的两种答案。
     */
    fun needsRestartForDisplay(runtime: VmRuntime): Boolean =
        me.kanadex.geometryos.display.DisplayChangePolicy.requiresRestart(
            running = runtime.running,
            busy = runtime.busy,
            stopping = runtime.stopping,
        )

    /**
     * 「关机已经落定」：服务的状态机回到 IDLE / ERROR，且没有 worker 还在跑。
     *
     * 为什么不能只判 `!running && !busy`：
     * 开机中（BUSY）点停止时，服务先把 stopRequested 置位、worker 收尾后才把 busy 放掉，
     * 然后再排一次真正的 STOP。中间那一瞬 busy=false、state 还是 BUSY，
     * 只判两个布尔就会**在 STOP 还没执行时**认为已关机，于是 restart 流程会在
     * 一个即将被关掉的会话上发启动请求（时序 T3，见 docs/display-settings/01 §3.3）。
     */
    fun settled(runtime: VmRuntime): Boolean =
        !runtime.running && !runtime.busy && !runtime.stopping &&
            (runtime.state == "IDLE" || runtime.state == "ERROR")

    /** 主页数字卡：正在运行的台数（只认会话真的活着，不看残留状态字）。 */
    fun runningCount(runtimes: Map<String, VmRuntime>): Int = runtimes.values.count { it.running }

    /** 主页数字卡文案：运行中台数 / 总台数。 */
    fun vmCountLabel(runtimes: Map<String, VmRuntime>, total: Int): String =
        runningCount(runtimes).toString() + " / " + total
    fun launch(context: android.content.Context, appState: AppState, device: VmDevice, runtime: VmRuntime) {
        if (canEnter(runtime)) openWindow(context, appState, device)
        else startAndOpen(context, appState, device)
    }
    fun canOpenDesktop(runtime: VmRuntime): Boolean = runtime.running && !runtime.busy
    fun canInspect(runtime: VmRuntime): Boolean = !runtime.busy
    fun canImport(runtime: VmRuntime): Boolean = runtime.running && !runtime.busy
    fun canDelete(runtime: VmRuntime): Boolean = !runtime.busy

    /** 重命名失败 → 给用户看的一句话。类型来自 RenameResult，不靠字符串猜。 */
    fun renameMessage(result: RenameResult): String = when (result) {
        is RenameResult.Renamed -> "已重命名为「" + result.device.name + "」"
        RenameResult.EmptyName -> "名字不能为空"
        RenameResult.TooLong -> "名字最长 " + VmRegistry.MAX_NAME_LENGTH + " 个字"
        RenameResult.Duplicate -> "已经有同名的虚拟机了"
        RenameResult.NotFound -> "这台虚拟机已经不存在"
        RenameResult.VerifyFailed -> "写入后校验失败，已回滚"
        is RenameResult.Failed -> "重命名失败：" + (result.cause.message ?: "未知错误")
    }

    fun canSubmit(name: String): Boolean = VmRegistry.normalize(name) != null

    fun recordKindLabel(kind: RecordKind): String = kind.label

    fun recordStatusLabel(status: RecordStatus): String = status.label

    fun deviceSubtitle(device: VmDevice, romTitle: String?): String = romTitle ?: device.romId
}

fun startAndOpen(context: android.content.Context, appState: AppState, device: VmDevice) {
    appState.requestVmStart(device.id)
    openWindow(context, appState, device)
}

/** 只开窗口（运行中再点一次的场景）。 */
fun openWindow(context: android.content.Context, appState: AppState, device: VmDevice) {
    openDesktop(context, device, appState.romOf(device))
}