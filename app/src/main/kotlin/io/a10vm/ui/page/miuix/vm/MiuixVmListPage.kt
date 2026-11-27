// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.vm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.runtime.RenameResult
import io.a10vm.runtime.VmDevice
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.miuix.widgets.MiuixBadge
import io.a10vm.ui.page.miuix.widgets.MiuixConfirmDialog
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.miuixCardInset
import io.a10vm.ui.page.miuix.widgets.miuixSection
import io.a10vm.ui.vm.VmActions
import io.a10vm.ui.vm.startAndOpen
import io.a10vm.ui.vm.vmBootingFx
import io.a10vm.ui.verse.LocalAppState
import io.a10vm.ui.verse.VmRuntime
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * 虚拟机列表（Miuix 分支）。
 *
 * 版式：一张卡 = 一条配置，卡片自身就是入口（点卡片进管理页，
 * 右下角为「更多」菜单与始终可用的启动/进入按钮；停止只在菜单中二次确认。
 *
 * 左右 12dp / 纵向 12dp 网格来自 [MiuixSettingsScaffold] + [miuixSection]。
 */
@Composable
fun MiuixVmListPage(
    enableBlur: Boolean,
    title: String,
    outerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appState = LocalAppState.current
    val devices by appState.devices.collectAsStateWithLifecycle()
    val runtimes by appState.runtimes.collectAsStateWithLifecycle()
    val devicesLoaded by appState.devicesLoaded.collectAsStateWithLifecycle()
    val backup = me.kanadex.geometryos.backup.LocalBackup.current

    var renameTarget by remember { mutableStateOf<VmDevice?>(null) }
    var stopTarget by remember { mutableStateOf<VmDevice?>(null) }

    MiuixSettingsScaffold(
        title = title,
        enableBlur = enableBlur,
        outerPadding = outerPadding,
        bottomContentPadding = 88.dp,
    ) {
        if (devicesLoaded && devices.isEmpty()) {
            miuixSection {
                BasicComponent(
                    title = "还没有虚拟机",
                    summary = "点右下角的加号新建一台，或者从主页的「内置 ROM」进去。",
                )
            }
        }

        items(devices, key = { it.id }) { device ->
            val runtime = runtimes[device.id] ?: VmRuntime()
            MiuixVmCard(
                device = device,
                romTitle = appState.exactRomOf(device)?.title,
                runtime = runtime,
                onClick = { navigator.push(Route.VmManage(device.id)) },
                onLaunch = { VmActions.launch(context, appState, device, runtime) },
                onStop = { stopTarget = device },
                onBackup = { backup.dispatch(me.kanadex.geometryos.backup.BackupAction.Capture(device.id)) },
                onRename = { renameTarget = device },
            )
        }
    }

    stopTarget?.let { device ->
        MiuixConfirmDialog(
            show = true,
            title = "停止 " + device.name + "？",
            message = "将结束这台虚拟机的当前运行。",
            confirmText = "停止",
            onConfirm = {
                stopTarget = null
                appState.stop(device.id)
            },
            onDismissRequest = { stopTarget = null },
        )
    }

    renameTarget?.let { device ->
        MiuixRenameDialog(
            device = device,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                val result = appState.rename(device.id, name)
                appState.recordRename(device, result is RenameResult.Renamed, VmActions.renameMessage(result))
                if (result is RenameResult.Renamed) renameTarget = null
                result
            },
        )
    }
}

/**
 * 一张虚拟机卡，结构与「配置」页的卡片对齐：
 * 上部标题（粗体）/ 副标题 / 运行详情 → 细线 → 下部状态徽标 + 更多菜单 + 启动按钮。
 * 整张卡可点（进管理页），启动中也不禁止跳转。
 */
@Composable
private fun MiuixVmCard(
    device: VmDevice,
    romTitle: String?,
    runtime: VmRuntime,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onBackup: () -> Unit,
) {
    // 菜单弹层以这张卡的「更多」按钮为锚点，与记录页右上角筛选同款交互。
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .miuixCardInset()
            .vmBootingFx(active = runtime.busy && !runtime.running),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        // 上部：标题（粗体）+ 副标题 + 运行详情
        Column(
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = device.name,
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    append(VmActions.deviceSubtitle(device, romTitle))
                    if (device.legacy) append(" · 已有数据")
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (runtime.detail.isNotBlank()) {
                Text(
                    text = runtime.detail,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        // 下部：可启动提示（状态 badge） + 更多菜单 + 启动按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MiuixBadge(
                text = VmActions.stateLabel(runtime),
                textColor = when (VmActions.tone(runtime)) {
                    VmActions.Tone.Error -> MiuixTheme.colorScheme.error
                    VmActions.Tone.Active -> MiuixTheme.colorScheme.primary
                    VmActions.Tone.Busy -> MiuixTheme.colorScheme.onTertiaryContainer
                    else -> MiuixTheme.colorScheme.onSecondaryContainer
                },
                containerColor = when (VmActions.tone(runtime)) {
                    VmActions.Tone.Error -> MiuixTheme.colorScheme.error.copy(alpha = 0.15f)
                    VmActions.Tone.Active -> MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                    VmActions.Tone.Busy -> MiuixTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                    else -> MiuixTheme.colorScheme.secondaryContainer
                },
            )
            Spacer(modifier = Modifier.weight(1f))
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    holdDownState = menuOpen,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(imageVector = AppIcons.MoreVert, contentDescription = "更多")
                }
                WindowListPopup(
                    show = menuOpen,
                    alignment = PopupPositionProvider.Align.End,
                    onDismissRequest = { menuOpen = false },
                ) {
                    ListPopupColumn {
                        BasicComponent(
                            title = "管理",
                            onClick = {
                                menuOpen = false
                                onClick()
                            },
                        )
                        BasicComponent(
                            title = "重命名",
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        BasicComponent(title = "备份虚拟机", onClick = {
                            menuOpen = false
                            onBackup()
                        })
                        if (VmActions.canStop(runtime)) {
                            BasicComponent(
                                title = "停止虚拟机",
                                titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                                onClick = {
                                    menuOpen = false
                                    onStop()
                                },
                            )
                        }
                    }
                }
            }
            VmLaunchButton(runtime = runtime, onLaunch = onLaunch)
        }
    }
}

/**
 * 操作按钮使用组件库的 Button，运行态只改变「启动/进入」文案。
 */
@Composable
private fun VmLaunchButton(runtime: VmRuntime, onLaunch: () -> Unit) {
    Button(
        onClick = onLaunch,
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Text(text = VmActions.launchLabel(runtime))
    }
}

/**
 * 重命名对话框：miuix [WindowDialog] + 库自带 [TextField]（label 当占位符），
 * 下面是「取消 / 确定」等宽双按钮（确定 primary）——与安装器弹窗同一排法。
 * 输入 → VmActions.canSubmit → AppState.rename（SQLite 事务 + 唯一索引）。
 */
@Composable
fun MiuixRenameDialog(
    device: VmDevice,
    onDismiss: () -> Unit,
    onConfirm: (String) -> RenameResult,
) {
    var name by remember(device.id) { mutableStateOf(device.name) }
    var error by remember(device.id) { mutableStateOf<String?>(null) }

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = "重命名虚拟机",
        content = {
            Column {
                TextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = "名称",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                error?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.size(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "取消",
                        onClick = onDismiss,
                    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "确定",
                        onClick = {
                            if (!VmActions.canSubmit(name)) {
                                error = "名字不能为空，且最长 " + io.a10vm.runtime.VmRegistry.MAX_NAME_LENGTH + " 个字"
                            } else {
                                val result = onConfirm(name)
                                if (result !is RenameResult.Renamed) error = VmActions.renameMessage(result)
                            }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}