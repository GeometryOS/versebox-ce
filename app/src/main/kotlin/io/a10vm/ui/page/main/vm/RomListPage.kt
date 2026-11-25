// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.vm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.main.widget.chip.CapsuleTag
import io.a10vm.ui.page.main.widget.chip.material3ToneColors
import io.a10vm.ui.page.main.widget.setting.BaseItemContainer
import io.a10vm.ui.page.main.widget.setting.BlockingLoadingIndicator
import io.a10vm.ui.page.main.widget.setting.NavigationItemWidget
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import me.kanadex.geometryos.rom.RomConfirmKind
import me.kanadex.geometryos.rom.RomGroup
import me.kanadex.geometryos.rom.RomItem
import me.kanadex.geometryos.rom.RomListAction
import me.kanadex.geometryos.rom.RomListMode
import me.kanadex.geometryos.rom.RomTexts
import me.kanadex.geometryos.rom.rememberRomListViewModel

/**
 * ROM 列表（Material 3 分支）：与 Miuix 分支共用 [rememberRomListViewModel] 的 State/Action，
 * 两种用途（管理 / 选择）也在同一个页面里，版式与 Miuix 分支逐项相同
 * （`docs/rom-package/14-版本管理` §6）。
 *
 * 分组用 [SegmentedColumn]（自带形状拼接与增删让位动画），行内是
 * `BaseItemContainer`（库的 Surface + 分组形状）+ 标题 + [CapsuleTag] 徽章 + 分隔线 + 操作行。
 * 页面里没有任何查询、格式化或状态判断（`docs/ui-rework/02` §2）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RomListPage(useBlur: Boolean, mode: RomListMode = RomListMode.Manage) {
    val navigator = LocalNavigator.current
    val viewModel = rememberRomListViewModel(mode)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = rememberMaterial3BlurBackdrop(useBlur)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.dispatch(RomListAction.Import(uri))
    }

    // 选择模式：选完立刻回上一页（不在这一页创建）。
    LaunchedEffect(state.pickedKey) {
        if (state.pickedKey == null) return@LaunchedEffect
        viewModel.dispatch(RomListAction.ConsumePicked)
        navigator.pop()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.verseBoxMaterial3BlurEffect(backdrop),
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                // 标题的唯一来源是 State（选择 / 管理两种用途），页面不自己判断模式拼标题。
                title = { Text(state.title) },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(AppIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { picker.launch(ROM_ZIP_TYPES) }) {
                        Icon(AppIcons.Download, contentDescription = "导入 ROM 包")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backdrop.getMaterial3AppBarColor(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    scrolledContainerColor = backdrop.getMaterial3AppBarColor(),
                ),
            )
        },
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
        ) {
            // 正在读：必须有自己的表示，否则会先闪一次「还没有 ROM」再突然变成列表。
            if (state.isLoading) {
                item(key = "loading") {
                    SegmentedColumn(title = "ROM") {
                        item(key = "loading-row") {
                            NavigationItemWidget(
                                icon = AppIcons.Android,
                                title = "正在读取 ROM…",
                                description = "扫描安装包与已导入的包",
                                onClick = {},
                            )
                        }
                    }
                }
            }
            if (state.isEmpty) {
                item(key = "empty") {
                    SegmentedColumn(title = "ROM") {
                        item(key = "empty-row") {
                            NavigationItemWidget(
                                icon = AppIcons.Android,
                                title = "还没有 ROM",
                                description = "点右上角导入一个 ROM 包；安装包里内置的 ROM 会出现在这里。",
                                onClick = { picker.launch(ROM_ZIP_TYPES) },
                            )
                        }
                    }
                }
            }
            state.groups.forEach { group ->
                item(key = "group-" + group.title) {
                    SegmentedColumn(title = group.title) {
                        group.items.forEach { rom ->
                            item(key = rom.rowId) { _ ->
                                RomRow(
                                    rom = rom,
                                    pickMode = state.isPick,
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
                }
            }
        }
    }

    BlockingLoadingIndicator(visible = state.busy, text = state.busyText, backdrop = backdrop)

    state.confirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { viewModel.dispatch(RomListAction.DismissDelete) },
            title = { Text(confirm.title) },
            text = { Text(confirm.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (confirm.kind) {
                            RomConfirmKind.Delete -> viewModel.dispatch(RomListAction.ConfirmDelete)
                            RomConfirmKind.ClearCache -> viewModel.dispatch(RomListAction.ConfirmClearCache)
                            RomConfirmKind.Repair -> viewModel.dispatch(RomListAction.ConfirmRepair)
                        }
                    },
                ) {
                    Text(confirm.confirmText)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dispatch(RomListAction.DismissDelete) }) {
                    Text("取消")
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    // 只有一个按钮的告知（检查出问题但原包不在）：M3 的 AlertDialog 天然支持只给 confirmButton，
    // 不给 dismissButton 就不会画出假的「取消」。
    state.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = { viewModel.dispatch(RomListAction.DismissNotice) },
            title = { Text(notice.title) },
            text = { Text(notice.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dispatch(RomListAction.DismissNotice) }) {
                    Text(notice.confirmText)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    // 定位：修复链带过来的 key 落到对应组（09/04 §2 第 4 步）。
    LaunchedEffect(state.highlightKey, state.blinkToken) {
        val key = state.highlightKey ?: return@LaunchedEffect
        val index = romGroupIndex(state.groups, key, state.isLoading, state.isEmpty)
        if (index >= 0) listState.animateScrollToItem(index)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.dispatch(RomListAction.ConsumeMessage)
        snackbarHostState.showSnackbar(message)
    }

    LaunchedEffect(state.createdVmId) {
        val id = state.createdVmId ?: return@LaunchedEffect
        viewModel.dispatch(RomListAction.ConsumeCreated)
        navigator.push(Route.VmManage(id))
    }
}

/** SAF 类型过滤：zip 为主，octet-stream 兜底（PC 拷来的大 zip 可能没有 MIME）。 */
private val ROM_ZIP_TYPES = arrayOf("application/zip", "application/octet-stream")

/**
 * 一行 ROM 在 M3 版式里的位置。
 *
 * M3 把**一个组**渲染成一个 LazyColumn item（`SegmentedColumn` 内部再铺行），
 * 所以这里只能滚到组：目标行一定在组的第一屏内（同组 ROM 数量远小于一屏）。
 */
private fun romGroupIndex(groups: List<RomGroup>, key: String, isLoading: Boolean, isEmpty: Boolean): Int {
    var index = 0
    if (isLoading) index++
    if (isEmpty) index++
    for (group in groups) {
        if (group.items.any { it.key == key }) return index
        index++
    }
    return -1
}

/** 一个 ROM 行：`BaseItemContainer` 承担分组形状与底色，行内只有库组件。 */
@Composable
private fun RomRow(
    rom: RomItem,
    pickMode: Boolean,
    onDelete: () -> Unit,
    onCheck: () -> Unit,
    onRepair: () -> Unit,
    onClearCache: () -> Unit,
    onCreate: () -> Unit,
    onPick: () -> Unit,
    highlight: Boolean = false,
    onHighlightFinished: () -> Unit = {},
) {
    // 定位反馈：2 × 400 ms 的 alpha 脉冲（09/05 §3）。行本身仍是库组件，只有 alpha 由动画驱动。
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(highlight) {
        if (!highlight) return@LaunchedEffect
        repeat(2) {
            pulse.animateTo(0.35f, animationSpec = tween(durationMillis = 400))
            pulse.animateTo(1f, animationSpec = tween(durationMillis = 400))
        }
        onHighlightFinished()
    }
    BaseItemContainer(modifier = Modifier.graphicsLayer { alpha = pulse.value }) {
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
                    style = MaterialTheme.typography.titleMedium,
                    // 被选中的那一行用强调色（主题角色色），与其它卡片区分。
                    color = if (rom.selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                rom.badges.forEach { badge ->
                    val colors = material3ToneColors(badge.tone)
                    CapsuleTag(
                        text = badge.text,
                        contentColor = colors.first,
                        containerColor = colors.second,
                    )
                }
                if (rom.selected) {
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = rom.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rom.meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 选择模式额外提供回填按钮，管理操作仍由同一行模型控制。
            if (rom.canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(AppIcons.Delete, contentDescription = "删除这个 ROM", tint = MaterialTheme.colorScheme.error)
                }
            }
            // 检查与重新解包：内置与本地导入都有（同一条修复链，docs/rom-package/12/03 §5）。
            if (rom.canCheck) {
                IconButton(onClick = onCheck) {
                    Icon(AppIcons.Active, contentDescription = "检查解包内容")
                }
            }
            if (rom.canRepair) {
                IconButton(onClick = onRepair) {
                    Icon(AppIcons.Repack, contentDescription = "从原包重新解包")
                }
            }
            if (rom.canClearCache) {
                IconButton(onClick = onClearCache) {
                    Icon(AppIcons.ClearCache, contentDescription = "清理已解包缓存")
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (pickMode) {
                // 「已选」= 组件 enabled=false 的禁用态（偏灰偏浅由库给），不是自己加 alpha。
                TextButton(onClick = onPick, enabled = rom.canUse && !rom.selected) {
                    Text(if (rom.selected) RomTexts.PICKED_ACTION else RomTexts.PICK_ACTION)
                }
            } else {
                TextButton(onClick = onCreate, enabled = rom.canUse) {
                    Text("新建虚拟机")
                }
            }
        }
    }
}