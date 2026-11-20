// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内的「请把这台虚拟机重新拉起来」信号。
 *
 * 为什么需要一个独立的信号而不是直接调 startService：修复链的顺序是
 * 「ROM 列表修完 → 回到**原来那个**启动窗口 → 在同一个窗口里重跑」
 * （`docs/ui-rework/09/04` §3 第 4 条：不重建 Activity）。启动窗口和主页是两个 Activity、两个任务，
 * 跨任务传「重新开始」只能靠进程内的一个信箱；窗口回到前台时自己取走并执行。
 *
 * 这一层没有任何 UI 依赖，也不持有 Context。
 */
object BootRestartBus {
    private val _vmId = MutableStateFlow<String?>(null)

    /** 当前待处理的请求（null = 没有）。窗口可以直接 collect。 */
    val vmId: StateFlow<String?> = _vmId.asStateFlow()

    fun request(vmId: String) {
        if (vmId.isNotEmpty()) _vmId.value = vmId
    }

    /** 取走请求：只有 id 对得上才消费，避免一台虚拟机吃掉另一台的重启信号。 */
    fun consume(vmId: String): Boolean {
        if (_vmId.value != vmId) return false
        _vmId.value = null
        return true
    }

    fun clear() {
        _vmId.value = null
    }
}
