// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.vm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import io.a10vm.ui.page.main.widget.chip.CapsuleTag
import io.a10vm.ui.vm.vmBootingFx
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.runtime.RenameResult
import io.a10vm.runtime.VmDevice
import io.a10vm.runtime.VmRegistry
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import io.a10vm.ui.vm.VmActions
import io.a10vm.ui.verse.LocalAppState
import io.a10vm.ui.verse.VmRuntime
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 虚拟机列表（Material 3 Expressive 分支）。
 *
 * 与 Miuix 分支同一结构：一张卡 = 一台虚拟机，整卡可点进管理页；
 * 右下角提供「更多」菜单与始终可用的启动/进入按钮；停止仅在菜单中二次确认。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VmListPage(
    useBlur: Boolean,
    sharedBackdrop: LayerBackdrop? = null,
    title: String,
    outerPadding: PaddingValues,
) {
    val navigator = LocalNavigator.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val appState = LocalAppState.current
    val devices by appState.devices.collectAsStateWithLifecycle()
    val runtimes by appState.runtimes.collectAsStateWithLifecycle()
    val devicesLoaded by appState.devicesLoaded.collectAsStateWithLifecycle()

    var renameTarget by remember { mutableStateOf<VmDevice?>(null) }
    var stopTarget by remember { mutableStateOf<VmDevice?>(null) }
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = sharedBackdrop ?: rememberMaterial3BlurBackdrop(useBlur)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.verseBoxMaterial3BlurEffect(backdrop),
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(text = title) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backdrop.getMaterial3AppBarColor(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    scrolledContainerColor = backdrop.getMaterial3AppBarColor(),
                ),
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = outerPadding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (devicesLoaded && devices.isEmpty()) {
                item {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("还没有虚拟机", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "点右下角的加号，选一个 ROM 建一台",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(devices, key = { it.id }) { device ->
                val runtime = runtimes[device.id] ?: VmRuntime()
                VmCard(
                    device = device,
                    runtime = runtime,
                    romTitle = appState.exactRomOf(device)?.title,
                    onClick = { navigator.push(Route.VmManage(device.id)) },
                    onRename = { renameTarget = device },
                    onManage = { navigator.push(Route.VmManage(device.id)) },
                    onLaunch = { VmActions.launch(context, appState, device, runtime) },
                    onStop = { stopTarget = device },
                )
            }
        }
    }

    stopTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { stopTarget = null },
            title = { Text("停止 " + device.name + "？") },
            text = { Text("将结束这台虚拟机的当前运行。") },
            confirmButton = {
                TextButton(onClick = {
                    stopTarget = null
                    appState.stop(device.id)
                }) { Text("停止", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { stopTarget = null }) { Text("取消") } },
        )
    }

    renameTarget?.let { device ->
        RenameDialog(
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
 * 上部标题（粗体）/ 副标题 / 运行详情 → 细线 → 下部状态胶囊 + 更多菜单 + 启动按钮。
 */
@Composable
private fun VmCard(
    device: VmDevice,
    runtime: VmRuntime,
    romTitle: String?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onManage: () -> Unit,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .vmBootingFx(active = runtime.busy && !runtime.running),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        onClick = onClick,
    ) {
        // 上部：标题（粗体）+ 副标题 + 运行详情
        Column(
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(device.name, style = MaterialTheme.typography.titleMediumEmphasized)
            Text(
                text = buildString {
                    append(VmActions.deviceSubtitle(device, romTitle))
                    if (device.legacy) append(" · 已有数据")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (runtime.detail.isNotBlank()) {
                Text(
                    text = runtime.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            color = MaterialTheme.colorScheme.outline,
        )
        // 下部：状态胶囊 + 更多菜单 + 启动按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatePill(runtime = runtime)
            Spacer(modifier = Modifier.weight(1f))

            // 菜单以这张卡的「更多」按钮为锚点（Box 包住按钮 + popup，与记录页筛选一致）
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(AppIcons.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("管理") },
                        onClick = {
                            menuExpanded = false
                            onManage()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    if (VmActions.canStop(runtime)) {
                        DropdownMenuItem(
                            text = { Text("停止虚拟机", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onStop()
                            },
                        )
                    }
                }
            }
            Button(onClick = onLaunch) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = AppIcons.Play,
                    contentDescription = null,
                )
                Text(VmActions.launchLabel(runtime))
            }
        }
    }
}

/** 状态胶囊：复用记录页的 [CapsuleTag]，运行中/正在准备/异常用彩色，可启动用中性色。 */
@Composable
private fun StatePill(runtime: VmRuntime) {
    val tone = VmActions.tone(runtime)
    val color = when (tone) {
        VmActions.Tone.Error -> MaterialTheme.colorScheme.error
        VmActions.Tone.Active -> MaterialTheme.colorScheme.primary
        VmActions.Tone.Busy -> MaterialTheme.colorScheme.tertiary
        VmActions.Tone.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    CapsuleTag(
        text = VmActions.stateLabel(runtime),
        containerColor = color.copy(alpha = 0.15f),
        contentColor = color,
    )
}

@Composable
internal fun RenameDialog(
    device: VmDevice,
    onDismiss: () -> Unit,
    onConfirm: (String) -> RenameResult,
) {
    var name by remember(device.id) { mutableStateOf(device.name) }
    var error by remember(device.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名虚拟机") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    singleLine = true,
                    label = { Text("名称") },
                )
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!VmActions.canSubmit(name)) {
                        error = "名字不能为空，且最长 " + VmRegistry.MAX_NAME_LENGTH + " 个字"
                    } else {
                        val result = onConfirm(name)
                        if (result !is RenameResult.Renamed) error = VmActions.renameMessage(result)
                    }
                },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}