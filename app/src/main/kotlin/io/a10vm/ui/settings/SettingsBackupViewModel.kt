// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置导出 / 还原的视图状态。**页面只渲染它**，不算文案、不碰文件（docs/ui-rework/02）。
 *
 * @param busy 正在读写（两个动作互斥，期间两个入口都禁用）
 * @param message 一次性提示（成功/失败），页面消费后清空
 * @param exportFileName 非空表示"请打开系统文件选择器，用这个名字保存"
 * @param preview 待确认的还原内容（来自 [SettingsBackup.parse]）
 */
data class SettingsBackupState(
    val busy: Boolean = false,
    val message: String? = null,
    val exportFileName: String? = null,
    val preview: SettingsBackup.Preview? = null,
)

sealed interface SettingsBackupAction {
    /** 用户点了「导出设置」：生成建议文件名，等 UI 打开系统文件选择器。 */
    data object RequestExport : SettingsBackupAction

    /** 系统文件选择器给了落点：把内容写进去。 */
    data class ExportTo(val uri: Uri) : SettingsBackupAction

    /** 用户选了备份文件：读出来先校验，通过后给确认框。 */
    data class RequestRestore(val uri: Uri) : SettingsBackupAction

    /** 确认框里点了「还原」。 */
    data object ConfirmRestore : SettingsBackupAction

    data object DismissPreview : SettingsBackupAction
    data object ConsumeMessage : SettingsBackupAction
}

/**
 * 设置的导出与还原：**两套 UI 分支共用这一个 ViewModel**（docs/ui-rework/02 §3）。
 *
 * 它持有状态、做 IO（读写 SAF、解析、应用），页面只发 Action、只渲染 State。
 */
class SettingsBackupViewModel(
    private val context: Context,
    private val preferences: AppPreferences,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SettingsBackupState())
    val state: StateFlow<SettingsBackupState> = _state.asStateFlow()

    fun dispatch(action: SettingsBackupAction) {
        when (action) {
            SettingsBackupAction.RequestExport -> {
                if (_state.value.busy) return
                _state.update { it.copy(exportFileName = SettingsBackup.fileName()) }
            }

            is SettingsBackupAction.ExportTo -> export(action.uri)
            is SettingsBackupAction.RequestRestore -> prepareRestore(action.uri)
            SettingsBackupAction.ConfirmRestore -> confirmRestore()
            SettingsBackupAction.DismissPreview -> _state.update { it.copy(preview = null) }
            SettingsBackupAction.ConsumeMessage -> _state.update { it.copy(message = null) }
        }
    }

    private fun export(uri: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, exportFileName = null) }
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    val payload = SettingsBackup.exportPayload(
                        context = context,
                        versionName = info.versionName.orEmpty(),
                        versionCode = info.longVersionCode,
                    )
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("打不开目标文件")
                    stream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    "已导出当前设置"
                }.getOrElse { "导出失败：" + (it.message ?: "未知错误") }
            }
            _state.update { it.copy(busy = false, message = message) }
        }
    }

    private fun prepareRestore(uri: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: error("打不开这个文件")
                    SettingsBackup.parse(text)
                }
            }
            _state.update { current ->
                result.fold(
                    onSuccess = { preview -> current.copy(busy = false, preview = preview) },
                    onFailure = { failure ->
                        current.copy(
                            busy = false,
                            message = failure.message ?: "还原失败：文件读不出来",
                        )
                    },
                )
            }
        }
    }

    private fun confirmRestore() {
        val preview = _state.value.preview ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, preview = null) }
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val applied = SettingsBackup.apply(context, preview)
                    // 写完立刻让内存流跟上，UI 不必等下次重启。
                    preferences.reload()
                    if (applied.ignored > 0) {
                        "已还原 " + applied.applied + " 项设置（跳过 " + applied.ignored + " 项未知类型）"
                    } else {
                        "已还原 " + applied.applied + " 项设置"
                    }
                }.getOrElse { "还原失败：" + (it.message ?: "未知错误") }
            }
            _state.update { it.copy(busy = false, message = message) }
        }
    }
}

/** 两个分支的页面都用它拿同一个 ViewModel（key 覆盖全部构造参数）。 */
@Composable
fun rememberSettingsBackupViewModel(): SettingsBackupViewModel {
    val context = LocalContext.current
    val preferences = rememberAppPreferences()
    val scope = rememberCoroutineScope()
    return remember(context, preferences, scope) {
        SettingsBackupViewModel(context, preferences, scope)
    }
}