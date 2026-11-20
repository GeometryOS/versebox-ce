// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.a10vm.runtime.limits.ChildProcessLimit
import io.a10vm.runtime.limits.ChildProcessLimitMonitor
import io.a10vm.runtime.limits.LimitOrigin
import io.a10vm.ui.processlimit.ProcessLimitSupport
import io.a10vm.ui.verse.AppState
import io.a10vm.ui.verse.LocalAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 安卓 12 进程限制页的视图状态。
 *
 * 文案、按钮文字、"现在算不算正在检测"全部在这里算好，页面只负责把它们画出来。
 */
data class ProcessLimitState(
    val title: String = "尚未检测",
    val detail: String = "",
    val actionText: String = "点这里重新检测",
    val ok: Boolean = false,
    val checking: Boolean = false,
)

/** 进程限制页的全部动作。 */
sealed interface ProcessLimitAction {

    /** 进入页面时的自动检测：只在还没有结论时真正跑。 */
    data object Check : ProcessLimitAction

    /** 用户点「重新检测」：允许接管上一次还在观察期的检测。 */
    data object Recheck : ProcessLimitAction
}

/**
 * 安卓 12 进程限制的唯一逻辑实现，Miuix 与 Material 3 两个页面共用（见 `docs/ui-rework/02` §4.6）。
 *
 * 分工：
 * - 检测在 `:phantom` 进程（[io.a10vm.runtime.limits.PhantomProbeService]）里跑，主进程只是客户端；
 * - 这里只做：把快照翻成文案、判断虚拟机是否在跑、把点击翻成 Action；
 * - `init` 只订阅状态流，不发起检测、不碰磁盘。
 */
class ProcessLimitViewModel(
    private val appState: AppState,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(render(ChildProcessLimitMonitor.state.value))
    val state: StateFlow<ProcessLimitState> = _state.asStateFlow()

    init {
        scope.launch {
            ChildProcessLimitMonitor.state.collect { snapshot ->
                _state.value = render(snapshot)
            }
        }
    }

    fun dispatch(action: ProcessLimitAction) {
        when (action) {
            ProcessLimitAction.Check -> {
                if (ChildProcessLimitMonitor.state.value.limit !is ChildProcessLimit.Unknown) return
                check()
            }

            ProcessLimitAction.Recheck -> check()
        }
    }

    private fun check() {
        // 还没有结论（正在 fork / 正在确认）时不接受重跑：最多 3 秒，文案已写明"正在检测…"。
        if (ChildProcessLimitMonitor.state.value.phase.pending) return

        // 虚拟机在跑也允许检测：检测在 :phantom 进程里 fork，测的是"系统会不会回收子进程"，
        // 与虚拟机是否运行无关；以前那句"运行中禁止检测"会让人以为功能坏了。
        // 主进程只发一条 Intent：串行化、取消旧的一批、fork、戳系统、回收全在 :phantom 进程里。
        // 这里既没有 fork 也没有 sleep，UI 线程不会被任何一步挂住。
        ChildProcessLimitMonitor.start(LimitOrigin.MANUAL)
        _state.value = render(ChildProcessLimitMonitor.state.value)
    }

    private fun render(snapshot: ChildProcessLimitMonitor.Snapshot): ProcessLimitState =
        ProcessLimitState(
            title = ProcessLimitSupport.statusTitle(snapshot.limit, snapshot.phase),
            detail = ProcessLimitSupport.statusDetail(snapshot.limit, snapshot.phase, snapshot.forked),
            actionText = ProcessLimitSupport.statusAction(snapshot.phase),
            ok = snapshot.limit.ok,
            checking = snapshot.phase.pending,
        )
}

@Composable
fun rememberProcessLimitViewModel(): ProcessLimitViewModel {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()
    // 上下文绑定与启动检测都在 AppState.init 里做过了（进程级单例，页面不再插手）。
    return remember(appState, scope) { ProcessLimitViewModel(appState, scope) }
}
