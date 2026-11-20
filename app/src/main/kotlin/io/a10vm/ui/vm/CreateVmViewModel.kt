// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.a10vm.runtime.RenameResult
import io.a10vm.ui.verse.AppState
import io.a10vm.ui.verse.LocalAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kanadex.geometryos.rom.RomRow
import me.kanadex.geometryos.rom.RomTexts

/** 新建虚拟机的视图状态（无 Compose 类型，docs/ui-rework/02 §1）。 */
data class CreateVmState(
    val romId: String? = null,
    /** 选中的**那一行**（来源:romKey@sha8）；同版本两份内容靠它分开。 */
    val rowId: String? = null,
    /**
     * 选中 ROM 的摘要：Android 15 V1.0.0 · 内置。
     *
     * 由逻辑层用 [RomTexts] 拼好——页面不许自己拼名称/版本/来源，
     * 也不许把这一行写成"按安卓版本选系统"（安卓版本是 ROM 的属性，不是它的身份）。
     */
    val romSummary: String = "",
    val name: String = "",
    val error: String? = null,
    /** 创建成功后由 UI 消费：拿到 id 就跳转管理页并清掉它。 */
    val createdId: String? = null,
)

/** 新建虚拟机页的全部动作。 */
sealed interface CreateVmAction {
    data class SetName(val name: String) : CreateVmAction
    data object Create : CreateVmAction
    data object ConsumeCreated : CreateVmAction
}

/**
 * 新建虚拟机的唯一逻辑实现，Miuix 与 Material 3 两个页面共用。
 *
 * ## 选中态只有一个真相：AppState.createVmRomId + AppState.createVmRowId
 *
 * 这里曾经自己维护一份 romId，并用 it.romId ?: available.firstOrNull()?.id 兜默认值。
 * 后果是两个页面各说一套：新建页显示上一页选的 Android 10，点进选择页却因为
 * createVmRomId 还是 null 而把 Android 15 画成已选，返回后又被默认值顶回 Android 15。
 *
 * 现在的规矩（docs/ui-rework/02 §3）：
 *  1. 选中项写**两个**字段（romKey + rowId）；只写 romKey 时，选择页按 rowId 判定「已选」，
 *     会把同一行画成"没选"（2026-11-12 点名的 P2）；
 *  2. 本 VM 只**镜像**它们（订阅），没有第二份可写副本；
 *  3. 需要默认值时，两个字段**一起写回**；
 *  4. 默认值只在「从来没有选过」时落一次，不覆盖用户已经选过的项。
 */
class CreateVmViewModel(private val appState: AppState, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(
        CreateVmState(
            romId = appState.createVmRomId.value,
            rowId = appState.createVmRowId.value,
        ),
    )
    val state: StateFlow<CreateVmState> = _state.asStateFlow()

    init {
        // 摘要与默认值都来自**行**（RomRow）：RomSpec 只有名称，没有版本号、来源与内容指纹。
        scope.launch {
            appState.romRows.collect { rows ->
                if (rows.isNotEmpty()) seedDefault(rows)
                publish(rows)
            }
        }
        scope.launch {
            appState.createVmRomId.collect { publish(appState.romRows.value) }
        }
        scope.launch {
            appState.createVmRowId.collect { publish(appState.romRows.value) }
        }
    }

    private fun seedDefault(rows: List<RomRow>) {
        if (appState.createVmRomId.value != null) return
        // 可选 = 已解包，或原包在（创建前会自动解包）；占位包不算。
        val pick = rows.firstOrNull { it.isReady || (!it.placeholder && it.hasArchive) } ?: return
        appState.createVmRomId.value = pick.key
        appState.createVmRowId.value = pick.rowId
    }

    private fun publish(rows: List<RomRow>) {
        val rowId = appState.createVmRowId.value
        val key = appState.createVmRomId.value
        val row = rows.firstOrNull { it.rowId == rowId } ?: rows.firstOrNull { it.key == key }
        _state.update {
            it.copy(
                romId = key,
                rowId = row?.rowId ?: rowId,
                romSummary = row?.let { target ->
                    RomTexts.titleWithVersion(target) + " · " + RomTexts.sourceLabel(target.source)
                }.orEmpty(),
            )
        }
    }

    fun dispatch(action: CreateVmAction) {
        when (action) {
            is CreateVmAction.SetName -> _state.update { it.copy(name = action.name, error = null) }
            CreateVmAction.Create -> create()
            CreateVmAction.ConsumeCreated -> _state.update { it.copy(createdId = null) }
        }
    }

    private fun create() {
        val snapshot = _state.value
        val romId = snapshot.romId
        if (romId == null) {
            _state.update { it.copy(error = "没有可用的 ROM") }
            return
        }
        // 取的是**用户选的那一行**：同版本两份内容时必须按指纹解包、按指纹建实例。
        val rows = appState.romRows.value
        val row = rows.firstOrNull { it.rowId == snapshot.rowId }
            ?: rows.firstOrNull { it.key == romId }
        val payload = row?.payloadSha256.orEmpty()
        val source = row?.source
        scope.launch {
            // 创建就是创建：写注册表 + 一条记录 + 一条审计，**不碰磁盘、不问 ROM 状态**。
            //
            // 这里曾经有一道「创建前闸门」（ROM 没解包就跳去 ROM 管理、解完再回来建），那是错的：
            // 用户起好名字（后面还会加别的配置），点保存却被拽去解包，回来还得重填一遍。
            // 解包是**开机**的一部分：AppState.start 会问同一个 RomGate，缺解包体就跳 ROM 管理解包
            // （有进度、有审计），修完自动回启动页重跑。所以创建与解包彻底解耦，
            // 内置 ROM 与本地 ROM 在这条链上一视同仁——本地导入过也可能被系统清掉解包体，
            // 容错不能只给内置那一档。
            val device = withContext(Dispatchers.IO) {
                runCatching { appState.create(romId, payload, source) }
                    .getOrElse { failure ->
                        _state.update { it.copy(error = failure.message ?: "创建失败，请检查存储空间") }
                        null
                    }
            }
            if (device == null) return@launch
            if (snapshot.name.isNotBlank()) {
                val result = withContext(Dispatchers.IO) { appState.rename(device.id, snapshot.name) }
                appState.recordRename(device, result is RenameResult.Renamed, VmActions.renameMessage(result))
                if (result !is RenameResult.Renamed) {
                    appState.delete(device.id)
                    _state.update { it.copy(error = VmActions.renameMessage(result)) }
                    return@launch
                }
            }
            _state.update { it.copy(createdId = device.id) }
        }
    }
}

@Composable
fun rememberCreateVmViewModel(): CreateVmViewModel {
    val appState = LocalAppState.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return remember(appState, scope) { CreateVmViewModel(appState, scope) }
}
