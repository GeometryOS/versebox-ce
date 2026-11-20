// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.display.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import me.kanadex.geometryos.display.DisplayChangePolicy
import me.kanadex.geometryos.display.HostDisplay
import me.kanadex.geometryos.display.VmDisplayMode
import me.kanadex.geometryos.display.VmDisplayOption
import me.kanadex.geometryos.display.VmDisplayTexts
import me.kanadex.geometryos.display.buildDisplayOptions

/**
 * 分辨率设置页的视图状态。**没有 Compose 类型**（`docs/ui-rework/02` §1）。
 *
 * 页面只需要三件事：画 [options]（单选列表）、按 [restartPrompt] 决定弹不弹「重启」、
 * 按 [working] 决定盖不盖「正在进行中，请稍候...」。
 */
data class VmDisplayState(
    val host: HostDisplay = HostDisplay.FALLBACK,
    val selected: VmDisplayMode = VmDisplayMode.Default,
    val options: List<VmDisplayOption> = emptyList(),
    /** 需要重启才能生效：非空 = 页面要显示「重启」对话框。 */
    val restartPrompt: Boolean = false,
    /** 关机收尾中：页面盖加载框，文案固定 [VmDisplayTexts.IN_PROGRESS]。 */
    val working: Boolean = false,
    /** 关机完成：页面据此退出本页 + 虚拟机设置页、回到主页虚拟机 tab、再按标准流程启动。 */
    val restartCompleted: Boolean = false,
    val error: String? = null,
)

/** 分辨率设置页的全部动作。 */
sealed interface VmDisplayAction {
    data class Select(val mode: VmDisplayMode) : VmDisplayAction
    data object ConfirmRestart : VmDisplayAction
    data object CancelRestart : VmDisplayAction
    data object ConsumeRestartCompleted : VmDisplayAction
    data object DismissError : VmDisplayAction
}

/**
 * 分辨率设置的唯一逻辑实现，Miuix 与 Material 3 两个页面共用（`docs/ui-rework/02` §3）。
 *
 * 三件事只在这里做一次：
 * 1. **写库**（经 `AppState.setDisplayMode` → `VmRegistry.setDisplayMode`，读回自证 + 审计 + 记录）；
 * 2. **判定要不要重启**（`DisplayChangePolicy`，唯一判据；页面不许自己写 if）；
 * 3. **关机时序**：`AppState.stop` 是同一条标准关机入口，之后只等"回到空闲"，
 *    绝不在这里接管启动——启动由页面按标准 `VmActions.launch` 发起（见 `docs/display-settings/02` 时序图）。
 */
class VmDisplayViewModel(
    private val context: Context,
    private val appState: AppState,
    private val vmId: String,
    private val scope: CoroutineScope,
) {
    private companion object {
        /** 等关机真正结束的上限：与 `AppState.stop` 的任务卡片清理同一个量级。 */
        const val STOP_TIMEOUT_MILLIS = 90_000L
        const val STOP_POLL_MILLIS = 200L
    }

    private val _state = MutableStateFlow(
        VmDisplayState(
            host = DisplaySettings.hostDisplay(context),
            options = buildDisplayOptions(VmDisplayMode.Default, DisplaySettings.hostDisplay(context)),
        ),
    )
    val state: StateFlow<VmDisplayState> = _state.asStateFlow()

    init {
        // 只订阅已经加载好的注册表（`AppState.devices`），不在构造里读库（`docs/ui-rework/05` §1）。
        scope.launch {
            appState.devices.collect { devices ->
                val device = devices.firstOrNull { it.id == vmId } ?: return@collect
                val host = DisplaySettings.hostDisplay(context)
                val mode = DisplaySettings.modeOf(device)
                _state.update {
                    it.copy(
                        host = host,
                        selected = mode,
                        options = buildDisplayOptions(mode, host),
                    )
                }
            }
        }
    }

    fun dispatch(action: VmDisplayAction) {
        when (action) {
            is VmDisplayAction.Select -> select(action.mode)
            VmDisplayAction.ConfirmRestart -> confirmRestart()
            VmDisplayAction.CancelRestart -> _state.update { it.copy(restartPrompt = false) }
            VmDisplayAction.ConsumeRestartCompleted -> _state.update { it.copy(restartCompleted = false) }
            VmDisplayAction.DismissError -> _state.update { it.copy(error = null) }
        }
    }

    /**
     * 选一个分辨率。
     *
     * 顺序是固定的：**先写库**，写成功才改界面、才谈重启。
     * 反过来（先改界面再写库）在写失败时会留下"界面说改了、重启后还是老值"的假象。
     */
    private fun select(mode: VmDisplayMode) {
        val snapshot = _state.value
        if (snapshot.selected.token == mode.token) return
        val device = appState.device(vmId) ?: return
        scope.launch {
            val saved = withContext(Dispatchers.IO) { appState.setDisplayMode(device.id, mode) }
            if (!saved) {
                _state.update { it.copy(error = VmDisplayTexts.SAVE_FAILED) }
                return@launch
            }
            val runtime = appState.runtime(vmId)
            val needsRestart = DisplayChangePolicy.requiresRestart(
                running = runtime.running,
                busy = runtime.busy,
                stopping = runtime.stopping,
            )
            _state.update {
                it.copy(
                    selected = mode,
                    options = buildDisplayOptions(mode, it.host),
                    restartPrompt = needsRestart,
                    error = null,
                )
            }
        }
    }

    /**
     * 用户点了「确定」：走**标准关机**，把加载框盖到关机结束为止。
     *
     * 这里**不做**任何"顺便把虚拟机重新拉起来"的动作：启动由页面在收到
     * [VmDisplayState.restartCompleted] 之后按标准入口 `VmActions.launch` 发起。
     * 这样启动过程仍然由启动页（FullscreenActivity）呈现，不会被这个加载框接管
     * （`docs/display-settings/02` §3 时序）。
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
            // 超时：撤掉加载框、留在本页并提示。不启动、不跳转。
            _state.update { it.copy(working = false, error = VmDisplayTexts.STOP_TIMEOUT) }
        }
    }
}

@Composable
fun rememberVmDisplayViewModel(vmId: String): VmDisplayViewModel {
    val appState = LocalAppState.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return remember(appState, context, scope, vmId) { VmDisplayViewModel(context, appState, vmId, scope) }
}
