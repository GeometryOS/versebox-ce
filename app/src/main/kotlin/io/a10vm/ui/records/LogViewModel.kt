// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.records

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.a10vm.records.RecordArchive
import io.a10vm.records.RecordExporter
import io.a10vm.records.RecordStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 日志原文页的视图状态。 */
data class LogState(
    val text: String = "正在读取…",
    val canShare: Boolean = false,
    val message: String? = null,
)

/**
 * 日志原文页的逻辑：读归档文本（有 256 KB 截断）、打包分享。
 * 页面只负责展示 [LogState] 并把「分享」转成 [share]。
 */
class LogViewModel(
    private val context: Context,
    private val recordId: Long,
    private val path: String,
    private val scope: CoroutineScope,
) {
    private val store = RecordStore.get(context)
    private val _state = MutableStateFlow(LogState())
    val state: StateFlow<LogState> = _state.asStateFlow()

    init {
        load()
    }

    /** 读归档文本是 IO（最多 256 KB），不能占组合线程。 */
    private fun load() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val record = runCatching { store.find(recordId) }.getOrNull()
                    ?: return@withContext LogState(text = "记录不存在", canShare = false)
                val text = runCatching { RecordArchive.readText(context, record, path) }
                    .getOrElse { "读取失败：${it.message}" }
                LogState(text = text, canShare = RecordArchive.hasArchive(context, record))
            }
            _state.value = loaded
        }
    }

    fun share() {
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                val record = runCatching { store.find(recordId) }.getOrNull() ?: return@withContext null
                if (!RecordArchive.hasArchive(context, record)) {
                    return@withContext "这条记录还没有归档日志，无法分享"
                }
                val prepared = RecordExporter.prepare(context, record)
                    ?: return@withContext "打包失败"
                runCatching {
                    RecordExporter.sharePrepared(context, prepared, "VerseBox 运行记录")
                }.fold(onSuccess = { null }, onFailure = { "无法打开分享面板" })
            }
            message?.let { _state.update { state -> state.copy(message = it) } }
        }
    }
}

@Composable
fun rememberLogViewModel(recordId: Long, path: String): LogViewModel {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, recordId, path, scope) {
        LogViewModel(context, recordId, path, scope)
    }
}
