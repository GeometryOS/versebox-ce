// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 VerseBox contributors
package io.a10vm.ui.page.main.settings.preferred.about

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 关于页的状态与动作入口。
 *
 * 与 InstallerX 的 AboutViewModel 相比，这里只保留 VerseBox 真的有能力做的事情：
 * 读取应用图标、版本信息，以及把「获取更新」交给页面里的更新弹窗（外链 GitHub）。
 * 在线更新检查 / 应用内安装属于安装器业务，本工程不做，所以 [AboutState.hasUpdate] 恒为 false，
 * 页面里对应的分支按原逻辑自然不显示。
 */
class AboutViewModel(private val context: Context) {
    private val _state = MutableStateFlow(AboutState())
    val state: StateFlow<AboutState> = _state.asStateFlow()

    private val _uiEvents = MutableSharedFlow<AboutEvent>(extraBufferCapacity = 4)
    val uiEvents: SharedFlow<AboutEvent> = _uiEvents.asSharedFlow()

    init {
        _state.value = _state.value.copy(appIcon = loadIcon())
    }

    private fun loadIcon(): ImageBitmap? = runCatching {
        context.packageManager.getApplicationIcon(context.packageName).toBitmap().asImageBitmap()
    }.getOrNull()

    fun dispatch(action: AboutAction) {
        when (action) {
            // 「获取更新」由页面弹窗完成（GitHub / 问题反馈两个外链），这里不需要动作。
            AboutAction.PerformUpdate -> Unit
            // VerseBox 不维护第二套文件日志：记录中心已经归档了每次操作。
            is AboutAction.SetEnableFileLogging -> Unit
            AboutAction.ShareLog -> Unit
        }
    }
}

@Composable
fun rememberAboutViewModel(): AboutViewModel {
    val context = LocalContext.current
    return remember(context) { AboutViewModel(context) }
}
