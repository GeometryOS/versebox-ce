// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.vm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.icons.AppMiuixIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.miuix.widgets.MiuixBadge
import io.a10vm.ui.page.miuix.widgets.MiuixBlockingLoadingDialog
import io.a10vm.ui.page.miuix.widgets.MiuixConfirmDialog
import io.a10vm.ui.page.miuix.widgets.MiuixNoticeDialog
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.miuixCardInset
import io.a10vm.ui.page.miuix.widgets.miuixSection
import io.a10vm.ui.page.miuix.widgets.miuixToneColors
import me.kanadex.geometryos.rom.RomConfirmKind
import me.kanadex.geometryos.rom.RomGroup
import me.kanadex.geometryos.rom.RomItem
import me.kanadex.geometryos.rom.RomListAction
import me.kanadex.geometryos.rom.RomListMode
import me.kanadex.geometryos.rom.RomTexts
import me.kanadex.geometryos.rom.rememberRomListViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * ROM 列表（Miuix 分支）——**同一个页面，两种用途**（`docs/rom-package/14-版本管理` §6）：
 *
 *  - [RomListMode.Manage]：主页「内置 ROM」进来。可导入 / 删除 / 清理缓存 / 检查 / 新建虚拟机；
 *  - [RomListMode.Pick]：新建虚拟机页「安卓版本」行进来。右下角是「选择 / 已选」，
 *    选中即回上一页；删除、检查、清理和重新解包仍使用同一组行操作。
 *
 * 组件全部来自 miuix 库（Card / SmallTitle / IconButton / TextButton / Icon / WindowDialog），
 * 页面本身只渲染 [RomItem] 与发 Action（`docs/ui-rework/01`、`02`）。
 *
 * 行不进 [miuixSection]：那一张整体卡片无法做「单行增删」的让位动画，所以每行自己发卡片
 * 并套 [miuixCardInset]（与 `MiuixHistoryPage` 的 items 写法一致）。
 */
@Composable
fun MiuixRomListPage(useBlur: Boolean, mode: RomListMode = RomListMode.Manage, restoreImport: Boolean = false) {
    val navigator = LocalNavigator.current
    val viewModel = rememberRomListViewModel(mode)
    val backupForSelection = me.kanadex.geometryos.backup.LocalBackup.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (restoreImport) {
        val backup = me.kanadex.geometryos.backup.LocalBackup.current
        val request by backup.state.collectAsStateWithLifecycle()
        LaunchedEffect(request.pendingRomUri) {
            val uri = request.pendingRomUri ?: return@LaunchedEffect
            viewModel.dispatch(RomListAction.Import(uri) { result ->
                navigator.pop()
                backup.dispatch(me.kanadex.geometryos.backup.BackupAction.RomImported(result))
            })
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // 定位：修复链带过来的 key 落到具体行（09/04 §2 第 4 步）。
    // index 按**本分支的 item 结构**算（加载项 / 空态 / 组标题 / 每行各一个 item），不是猜的。
    LaunchedEffect(state.highlightKey, state.blinkToken) {
        val key = state.highlightKey ?: return@LaunchedEffect
        val index = romItemIndex(state.groups, key, state.isLoading, state.isEmpty)
        if (index >= 0) listState.animateScrollToItem(index)
    }

    // 选择模式：选完立刻回上一页（不在这一页创建）。
    LaunchedEffect(state.pickedKey) {
        if (state.pickedKey == null) return@LaunchedEffect
        if (mode == RomListMode.Pick) backupForSelection.dispatch(me.kanadex.geometryos.backup.BackupAction.Pick(null))
        viewModel.dispatch(RomListAction.ConsumePicked)
        navigator.pop()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.dispatch(RomListAction.Import(uri))
    }

    MiuixSettingsScaffold(
        // 标题的唯一来源是 State（选择 / 管理两种用途），页面不自己判断模式拼标题。
        title = state.title,
        enableBlur = useBlur,
        showBackButton = true,
        bottomContentPadding = 24.dp,
        listState = listState,
        topBarActions = {
            IconButton(onClick = { picker.launch(ROM_ZIP_TYPES) }) {
                Icon(imageVector = AppIcons.Download, contentDescription = "导入 ROM 包")
            }
        },
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
    ) {
        // 正在读：必须有自己的表示，否则会先闪一次「还没有 ROM」再突然变成列表
        // （docs/ui-rework/05 §4）。
        if (state.isLoading) {
            miuixSection {
                BasicComponent(title = "正在读取 ROM…", summary = null)
            }
        }

        if (state.isEmpty) {
            miuixSection {
                BasicComponent(
                    title = "还没有 ROM",
                    summary = "点右上角导入一个 ROM 包；安装包里内置的 ROM 会出现在这里。",
                )
            }
        }

        state.groups.forEach { group ->
            item(key = "title-" + group.title) {
                SmallTitle(text = group.title)
            }
            items(group.items, key = { it.rowId }) { rom ->
                MiuixRomCard(
                    rom = rom,
                    pickMode = state.isPick,
                    onOpen = { navigator.push(Route.RomDetail(rom.rowId, state.isPick)) },
                    highlight = state.highlightKey == rom.key,
                    onHighlightFinished = { viewModel.dispatch(RomListAction.ConsumeFocus) },
                    onDelete = { viewModel.dispatch(RomListAction.RequestDelete(rom.rowId)) },
                    onCheck = { viewModel.dispatch(RomListAction.CheckRom(rom.rowId)) },
                    onRepair = { viewModel.dispatch(RomListAction.RequestRepair(rom.rowId)) },
                    onClearCache = { viewModel.dispatch(RomListAction.RequestClearCache(rom.rowId)) },
                    onCreate = { viewModel.dispatch(RomListAction.Create(rom.rowId)) },
                    onPick = { viewModel.dispatch(RomListAction.Pick(rom.rowId)) },
                )
            }
        }
    }

    MiuixRomOperationDialogs(state, viewModel::dispatch)

    // 一次性提示：走骨架的 Snackbar，不自己弹对话框。
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.dispatch(RomListAction.ConsumeMessage)
        snackbarHostState.showSnackbar(message)
    }

    // 管理模式下创建成功 → 进管理页（与虚拟机列表的「新建」同一个落点）。
    LaunchedEffect(state.createdVmId) {
        val id = state.createdVmId ?: return@LaunchedEffect
        viewModel.dispatch(RomListAction.ConsumeCreated)
        navigator.push(Route.VmManage(id))
    }
}

@Composable
internal fun MiuixRomOperationDialogs(
    state: me.kanadex.geometryos.rom.RomListState,
    dispatch: (RomListAction) -> Unit,
    ) {
    MiuixBlockingLoadingDialog(visible = state.busy, text = state.busyText)

    state.confirm?.let { confirm ->
        MiuixConfirmDialog(
            show = true,
            title = confirm.title,
            message = confirm.message,
            confirmText = confirm.confirmText,
            onConfirm = {
                when (confirm.kind) {
                    RomConfirmKind.Delete -> dispatch(RomListAction.ConfirmDelete)
                    RomConfirmKind.ClearCache -> dispatch(RomListAction.ConfirmClearCache)
                    RomConfirmKind.Repair -> dispatch(RomListAction.ConfirmRepair)
                }
            },
            onDismissRequest = { dispatch(RomListAction.DismissDelete) },
        )
    }

    // 只有一个按钮的告知（检查出问题但原包不在）：用同一副骨架、去掉「取消」。
    state.notice?.let { notice ->
        MiuixNoticeDialog(
            show = true,
            title = notice.title,
            message = notice.message,
            confirmText = notice.confirmText,
            onDismissRequest = { dispatch(RomListAction.DismissNotice) },
        )
    }
}

/**
 * 一个 ROM 行。
 *
 * [pickMode] 为 true 时右下角是「选择 / 已选」，右上角给选中项打勾、标题用强调色——
 * 这三件事全部用库组件表达：勾是 miuix 的 [Icon]（与分辨率页同一颗），
 * 强调色是主题角色色 `primary`，禁用态交给 [TextButton] 的 `enabled`（不是自己加 alpha，
 * `docs/ui-rework/03` §8「禁用态用组件 enabled 参数」）。
 *
 * [highlight] 为 true 时按 `09/05` §3 做 2 × 400 ms 的 alpha 脉冲——行本身仍是库的 [Card]，
 * 只有 alpha 由动画驱动（属于动画，不是自造控件）。
 */
@Composable
internal fun MiuixRomCard(
    rom: RomItem,
    pickMode: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onCheck: () -> Unit,
    onRepair: () -> Unit,
    onClearCache: () -> Unit,
    onCreate: () -> Unit,
    onPick: () -> Unit,
    highlight: Boolean = false,
    onHighlightFinished: () -> Unit = {},
    versionMode: Boolean = false,
    onSelectVersion: () -> Unit = {},
) {
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(highlight) {
        if (!highlight) return@LaunchedEffect
        repeat(2) {
            pulse.animateTo(0.35f, animationSpec = tween(durationMillis = 400))
            pulse.animateTo(1f, animationSpec = tween(durationMillis = 400))
        }
        onHighlightFinished()
    }
    Card(
        modifier = Modifier
            .miuixCardInset()
            .graphicsLayer { alpha = pulse.value },
        onClick = onOpen,
        showIndication = true,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = rom.title,
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Medium,
                    // 被选中的那一行用强调色，与其它卡片区分（颜色取主题角色色，不写死）。
                    color = if (rom.selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                rom.badges.forEach { badge ->
                    val (textColor, containerColor) = miuixToneColors(badge.tone)
                    MiuixBadge(text = badge.text, textColor = textColor, containerColor = containerColor)
                }
                if (rom.selected) {
                    Icon(
                        imageVector = AppMiuixIcons.Ok,
                        contentDescription = "已选中",
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = rom.description,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rom.meta,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 行内管理动作在管理和选择模式下共用；按钮可用性来自ROM行模型。
            if (rom.canDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = AppIcons.Delete,
                        contentDescription = "删除这个 ROM",
                        tint = MiuixTheme.colorScheme.error,
                    )
                }
            }
            // 检查与重新解包：**内置与本地导入都有**（同一条修复链，docs/rom-package/12/03 §5）。
            // 这里曾经只给本地导入，导致内置包的解包体坏了之后列表里连入口都没有。
            if (rom.canCheck) {
                IconButton(onClick = onCheck, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = AppIcons.Active,
                        contentDescription = "检查解包内容",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (rom.canRepair) {
                IconButton(onClick = onRepair, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = AppIcons.Repack,
                        contentDescription = "从原包重新解包",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (rom.canClearCache) {
                IconButton(onClick = onClearCache, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = AppIcons.ClearCache,
                        contentDescription = "清理已解包缓存",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            when {
                versionMode -> TextButton(
                    text = if (rom.selected) "正在使用" else "切换到此版本",
                    enabled = rom.canUse && !rom.selected,
                    onClick = onSelectVersion,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                pickMode -> TextButton(
                    text = if (rom.selected) RomTexts.PICKED_ACTION else RomTexts.PICK_ACTION,
                    enabled = rom.canUse && !rom.selected,
                    onClick = onPick,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                rom.canUse -> TextButton(
                    text = "新建虚拟机",
                    onClick = onCreate,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/**
 * 一行 ROM 在 Miuix 版式里的位置：加载项 / 空态各占一个 item，每个非空组贡献
 * 「1 个组标题 item + N 个行 item」。
 *
 * @return -1 = 这一行不在当前列表里（例如已被删除）
 */
private fun romItemIndex(groups: List<RomGroup>, key: String, isLoading: Boolean, isEmpty: Boolean): Int {
    var index = 0
    if (isLoading) index++
    if (isEmpty) index++
    for (group in groups) {
        index++ // 组标题（SmallTitle）
        val offset = group.items.indexOfFirst { it.key == key }
        if (offset >= 0) return index + offset
        index += group.items.size
    }
    return -1
}

/**
 * SAF 的类型过滤：主类型是 zip，另带 octet-stream 兜底——
 * 从 PC 拷进手机的大 zip 有时没有 MIME，只给 zip 会让文件在选择器里变灰。
 */
private val ROM_ZIP_TYPES = arrayOf("application/zip", "application/octet-stream")