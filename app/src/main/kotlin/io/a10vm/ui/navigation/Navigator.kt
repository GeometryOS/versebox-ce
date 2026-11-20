// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
// 结构与 InstallerX-Revived 的 Navigator 一致（其来源为 KernelSU / ReSukiSU manager，GPL-3.0）。
package io.a10vm.ui.navigation

import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 返回栈的唯一写入口。
 *
 * 除了 push/pop，还负责三件真实用到的事：
 * 1. 同一页重复 push 会被丢掉（连点两下不会压两层一样的页面）；
 * 2. pop 有 100ms 防抖（返回手势 + 返回键同时触发时只退一次）；
 * 3. 结果回传（navigateForResult / setResult）给重命名这类「下一个页面选完再回来」的场景。
 */
class Navigator(val backStack: NavBackStack) {
    private val resultBus = mutableMapOf<String, MutableSharedFlow<Any>>()

    fun push(key: NavKey) {
        if (backStack.lastOrNull() == key) {
            Log.i(TAG, "忽略重复 push：$key")
            return
        }
        backStack.add(key)
    }

    fun replace(key: NavKey) {
        if (backStack.isNotEmpty()) {
            backStack[backStack.lastIndex] = key
        } else {
            backStack.add(key)
        }
    }

    fun replaceAll(keys: List<NavKey>) {
        if (keys.isEmpty()) return
        if (backStack.isNotEmpty()) {
            backStack.clear()
            backStack.addAll(keys)
        }
    }

    private var lastPopTime = 0L

    fun pop() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPopTime < 100) {
            Log.i(TAG, "100ms 内重复 pop，忽略")
            return
        }
        if (backStackSize() <= 1) return
        lastPopTime = System.currentTimeMillis()
        backStack.removeLastOrNull()
    }

    fun popUntil(predicate: (NavKey) -> Boolean) {
        while (backStack.isNotEmpty() && !predicate(backStack.last())) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    fun navigateForResult(route: Route, requestKey: String) {
        ensureChannel(requestKey)
        push(route)
    }

    fun <T : Any> setResult(requestKey: String, value: T) {
        ensureChannel(requestKey).tryEmit(value)
        pop()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> observeResult(requestKey: String): SharedFlow<T> = ensureChannel(requestKey) as SharedFlow<T>

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearResult(requestKey: String) {
        ensureChannel(requestKey).resetReplayCache()
    }

    fun current(): NavKey? = backStack.lastOrNull()

    fun backStackSize(): Int = backStack.size

    private fun ensureChannel(key: String): MutableSharedFlow<Any> = resultBus.getOrPut(key) {
        MutableSharedFlow(replay = 1, extraBufferCapacity = 0)
    }

    private companion object {
        const val TAG = "VerseBoxNavigator"
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator not provided")
}
