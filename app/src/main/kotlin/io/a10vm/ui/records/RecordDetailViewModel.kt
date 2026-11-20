// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.records

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.a10vm.records.LogDigest
import io.a10vm.records.RecordArchive
import io.a10vm.records.RecordExporter
import io.a10vm.records.RecordKind
import io.a10vm.records.RecordStatus
import io.a10vm.records.RecordStore
import io.a10vm.records.VmRecord
import io.a10vm.ui.verse.AppState
import io.a10vm.ui.verse.LocalAppState
import me.kanadex.geometryos.audit.model.RecordEvidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers

/** 记录详情的视图状态。 */
data class RecordDetailState(
    val record: VmRecord? = null,
    val milestones: List<String> = emptyList(),
    val entries: List<RecordArchive.Entry> = emptyList(),
    /** 证据完整性（来自 manifest.json）；页面只渲染它，不自己判断。 */
    val evidence: RecordEvidence = RecordEvidence(),
    val message: String? = null,
    /** 首帧还没读盘完（查询 + 读归档都在 IO），页面据此不要显示「记录不存在」。 */
    val isLoading: Boolean = true,
)

/** 记录详情能发生的全部动作。 */
sealed interface RecordDetailAction {
    data object Refresh : RecordDetailAction

    data object Delete : RecordDetailAction

    /** 导出到系统文件选择器返回的位置；null 表示用户取消。 */
    data class Export(val uri: Uri?) : RecordDetailAction

    /** 走系统分享面板。 */
    data object Share : RecordDetailAction
}

/**
 * 记录详情的唯一逻辑实现，两套 UI 共用。
 *
 * 归档只在记录结束时由 RecordCollector 写好；这里一律读归档快照，
 * 不会去读正在运行的 vm 目录 —— 所以分享历史记录永远拿到的是那一次的定稿。
 */
class RecordDetailViewModel(
    private val context: Context,
    private val appState: AppState,
    private val id: Long,
    private val scope: CoroutineScope,
) {
    private val store = RecordStore.get(context)

    private val _state = MutableStateFlow(RecordDetailState())
    val state: StateFlow<RecordDetailState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
        scope.launch { store.changes.collect { refresh() } }
    }

    fun dispatch(action: RecordDetailAction) {
        when (action) {
            RecordDetailAction.Refresh -> refresh()
            RecordDetailAction.Delete -> delete()
            is RecordDetailAction.Export -> export(action.uri)
            RecordDetailAction.Share -> share()
        }
    }

    /** 系统文件选择器需要的默认文件名；没有记录时返回 null。 */
    fun exportName(): String? = _state.value.record?.let(RecordExporter::zipName)

    /** 导出/分享前的前置判断：没有归档就别给用户一个没有日志的空包。 */
    fun canExport(): Boolean = _state.value.record?.let { RecordArchive.hasArchive(context, it) } == true

    /**
     * 全部读盘动作放 IO：SQLite 查询 + 读归档文本（最多 256 KB）+ 解析时间线 + 目录遍历。
     * 这些以前都在组合期主线程跑，进详情页必卡。
     */
    private fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val record = runCatching { store.find(id) }.getOrNull() ?: return@withContext null
                val startup = runCatching { RecordArchive.startupLog(context, record) }.getOrDefault("")
                val milestones = runCatching {
                    LogDigest.milestones(LogDigest.parse(startup))
                }.getOrDefault(emptyList())
                val entries = runCatching { RecordArchive.entries(context, record) }
                    .getOrDefault(emptyList())
                val evidence = runCatching { RecordArchive.evidence(context, record) }
                    .getOrDefault(RecordEvidence())
                Snapshot(record, milestones, entries, evidence)
            }
            if (snapshot == null) {
                _state.value = RecordDetailState(isLoading = false)
            } else {
                _state.update {
                    it.copy(
                        record = snapshot.record,
                        milestones = snapshot.milestones,
                        entries = snapshot.entries,
                        evidence = snapshot.evidence,
                        isLoading = false,
                    )
                }
            }
        }
    }

    private data class Snapshot(
        val record: VmRecord,
        val milestones: List<String>,
        val entries: List<RecordArchive.Entry>,
        val evidence: RecordEvidence,
    )

    private fun delete() {
        val record = _state.value.record ?: return
        scope.launch {
            withContext(Dispatchers.IO) { appState.deleteRecords(store, listOf(record.id)) }
            _state.value = RecordDetailState()
        }
    }

    /** 打包 zip 也是重活（可能要压 12 MB），全程 IO，结果回主线程显示。 */
    private fun export(uri: Uri?) {
        val record = _state.value.record ?: return
        if (uri == null) return
        if (!guardArchived(record)) return
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                val prepared = RecordExporter.prepare(context, record)
                    ?: return@withContext "打包失败"
                when (val result = RecordExporter.write(context, uri, prepared)) {
                    is RecordExporter.Result.Done -> "已导出到 " + result.exported.displayPath
                    is RecordExporter.Result.Failed -> result.message
                }
            }
            _state.update { it.copy(message = message) }
        }
    }

    private fun share() {
        val record = _state.value.record ?: return
        if (!guardArchived(record)) return
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                val prepared = RecordExporter.prepare(context, record)
                    ?: return@withContext false
                runCatching {
                    RecordExporter.sharePrepared(context, prepared, "VerseBox 运行记录")
                }.isSuccess
            }
            if (!started) _state.update { it.copy(message = "无法打开分享面板") }
        }
    }

    /**
     * 归档只有在记录结束时才生成（RecordCollector.finish）。
     * 进行中、或上次被系统杀掉没来得及收尾的记录没有归档，导出只会得到一个没有日志的空包，
     * 所以这里直接拦下并说明原因。
     */
    private fun guardArchived(record: VmRecord): Boolean {
        if (RecordArchive.hasArchive(context, record)) return true
        val why = when {
            record.status == RecordStatus.RUNNING -> "这条记录还在进行中，日志要等它结束才会归档"
            // 有些记录**本来就没有客体日志**：创建虚拟机、ROM 导入 / 删除，
            // 以及在创建会话之前就被拒绝的启动（例如 ROM 缺失）。这不是"没正常结束"，
            // 文案必须说清楚，否则用户会以为日志丢了。
            record.kind == RecordKind.CREATE_VM ||
                record.kind == RecordKind.ROM_IMPORT ||
                record.kind == RecordKind.ROM_DELETE ||
                record.kind == RecordKind.ROM_CHECK ||
                record.kind == RecordKind.ROM_VERSION ->
                "这条记录没有客体日志可归档（" + record.kind.label + "不产生运行日志）"

            else -> "这条记录没有归档日志：这次操作在创建会话之前就被拒绝了，失败原因见上面的结论"
        }
        _state.update { it.copy(message = why) }
        return false
    }
}

@Composable
fun rememberRecordDetailViewModel(id: Long): RecordDetailViewModel {
    val context = LocalContext.current
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()
    return remember(context, appState, id, scope) {
        RecordDetailViewModel(context, appState, id, scope)
    }
}
