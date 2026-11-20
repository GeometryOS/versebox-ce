// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.vm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.runtime.RenameResult
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.main.widget.setting.NavigationItemWidget
import io.a10vm.ui.page.main.widget.setting.BaseItemContainer
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import io.a10vm.ui.vm.CreateVmAction
import io.a10vm.ui.vm.rememberCreateVmViewModel
import me.kanadex.geometryos.display.VmDisplayTexts
import me.kanadex.geometryos.display.android.DisplaySettings
import me.kanadex.geometryos.rom.RomKeys
import me.kanadex.geometryos.rom.RomTexts
import io.a10vm.ui.vm.VmActions
import io.a10vm.ui.verse.LocalAppState
import io.a10vm.ui.verse.VmRuntime
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.ui.platform.LocalFocusManager
import io.a10vm.ui.page.main.widget.setting.BaseWidget
/**
 * 新建虚拟机（Material 3 分支）。
 *
 * 版式对齐安装器的编辑配置页：顶栏返回，右下角「保存」FAB；
 * 名称输入框带 Edit 前导图标（BaseItemContainer 包着，位于第一组），
 * 安卓版本是独立一行入口，跳到选择页选完回来。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CreateVmPage(useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val appState = LocalAppState.current
    val focusManager = LocalFocusManager.current
    val viewModel = rememberCreateVmViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 选中项的唯一真相是 AppState.createVmRomId，由 CreateVmViewModel 自己订阅；
    // 页面不再转发一次（docs/ui-rework/02 §3：UI 只渲染 + 发 Action）。

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = rememberMaterial3BlurBackdrop(useBlur)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusManager.clearFocus() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.verseBoxMaterial3BlurEffect(backdrop),
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text("新建虚拟机") },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(AppIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backdrop.getMaterial3AppBarColor(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    scrolledContainerColor = backdrop.getMaterial3AppBarColor(),
                ),
            )
        },
        floatingActionButton = {
            SmallExtendedFloatingActionButton(
                modifier = Modifier.padding(bottom = 16.dp),
                icon = { Icon(AppIcons.Save, contentDescription = "保存") },
                text = { Text("保存") },
                onClick = { viewModel.dispatch(CreateVmAction.Create) },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
            contentPadding = paddingValues + PaddingValues(bottom = 96.dp),
        ) {
            item {
                SegmentedColumn(title = "新建虚拟机") {
                    item {
                        BaseItemContainer {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp, horizontal = 16.dp),
                                    leadingIcon = {
                                        Icon(imageVector = AppIcons.Edit, contentDescription = null)
                                    },
                                    label = { Text("名称（可留空，默认 虚拟机 N）") },
                                    value = state.name,
                                    onValueChange = { viewModel.dispatch(CreateVmAction.SetName(it)) },
                                    singleLine = true,
                                )
                            }
                        }
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Android,
                            title = "ROM",
                            // 摘要由逻辑层拼好：`Android 15 V1.0.0 · 内置`（含版本号与来源）。
                            description = state.romSummary.ifBlank { "点击选择要安装的系统" },
                            onClick = { navigator.push(Route.RomList(pickForCreate = true)) },
                        )
                    }
                }
            }
            state.error?.let {
                item {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    // 创建成功 → 跳管理页
    LaunchedEffect(state.createdId) {
        val id = state.createdId ?: return@LaunchedEffect
        viewModel.dispatch(CreateVmAction.ConsumeCreated)
        navigator.pop()
        navigator.push(Route.VmManage(id))
    }
}

/** 虚拟机管理（Material 3 分支）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VmManagePage(id: String, useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appState = LocalAppState.current
    val devices by appState.devices.collectAsStateWithLifecycle()
    val runtimes by appState.runtimes.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.id == id }
    val runtime = runtimes[id] ?: VmRuntime()
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = rememberMaterial3BlurBackdrop(useBlur)

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { appState.installApk(id, it.toString()) }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) appState.importFiles(id, uris.map { it.toString() })
    }
    var showRename by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val romTitle = appState.exactRomOf(device)?.title
    // 版本管理入口的摘要：这台虚拟机现在用的是哪一版（由逻辑层拼，页面不判 ROM 状态）。
    val romVersionSummary = remember(device?.id, device?.romId) {
        val spec = appState.exactRomOf(device)
        if (spec == null) RomTexts.VERSION_SUMMARY_NONE
        else RomTexts.versionSummary(spec.name, RomKeys.versionOf(spec.id))
    }
    val tone = VmActions.tone(runtime)
    val statusContainer = when (tone) {
        VmActions.Tone.Error -> MaterialTheme.colorScheme.errorContainer
        VmActions.Tone.Active -> MaterialTheme.colorScheme.primaryContainer
        VmActions.Tone.Busy -> MaterialTheme.colorScheme.tertiaryContainer
        VmActions.Tone.Idle -> MaterialTheme.colorScheme.secondaryContainer
    }
    val statusContent = when (tone) {
        VmActions.Tone.Error -> MaterialTheme.colorScheme.onErrorContainer
        VmActions.Tone.Active -> MaterialTheme.colorScheme.onPrimaryContainer
        VmActions.Tone.Busy -> MaterialTheme.colorScheme.onTertiaryContainer
        VmActions.Tone.Idle -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val statusIconTint = when (tone) {
        VmActions.Tone.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val statusDetail = when (tone) {
        VmActions.Tone.Error -> "上一次操作没有走完。可以先去「查看运行记录」看卡在哪，或直接重新启动。"
        VmActions.Tone.Active -> "虚拟机正在运行。关掉窗口也能从这里重新进入桌面。"
        VmActions.Tone.Busy -> "正在准备启动，窗口会自动弹出来，稍等一下就好。"
        VmActions.Tone.Idle -> "还没有启动。点下面的蓝色按钮启动这台虚拟机。"
    } + if (runtime.detail.isBlank()) "" else "\n" + runtime.detail
    val primaryLabel = VmActions.launchLabel(runtime) + "虚拟机"
    // 分辨率这一行的小标题由策略库算（模式 + 宿主真实几何），页面不拼字符串。
    val displaySummary = remember(device?.id, device?.displayMode) {
        device?.let { DisplaySettings.summaryOf(context, it) }.orEmpty()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.verseBoxMaterial3BlurEffect(backdrop),
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(device?.name ?: "虚拟机") },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(AppIcons.ArrowBack, contentDescription = "返回")
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
            modifier = Modifier
                .fillMaxSize()
                .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
        ) {
            // 1. 状态大卡：主页同款解剖（背景大图标 + 标题 + 说明），容器色随状态区分
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = statusContainer,
                        contentColor = statusContent,
                    ),
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // 背景大图标，与主页状态卡同款解剖
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .offset(50.dp, 38.dp),
                            contentAlignment = Alignment.BottomEnd,
                        ) {
                            Icon(
                                imageVector = if (tone == VmActions.Tone.Error) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                                contentDescription = null,
                                tint = statusIconTint.copy(alpha = 0.35f),
                                modifier = Modifier.size(170.dp),
                            )
                        }
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = VmActions.stateLabel(runtime),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = statusDetail,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = statusContent.copy(alpha = 0.8f),
                            )
                            Spacer(Modifier.height(36.dp))
                            Text(
                                text = "系统：" + (romTitle ?: device?.romId ?: "未知"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = statusContent.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            // 2. 卡片与列表之间的强调色主按钮
            item {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    onClick = { device?.let { VmActions.launch(context, appState, it, runtime) } },
                ) {
                    Text(primaryLabel)
                }
            }

            // 3. 「显示」分组：位于启动主按钮与「运行」之间（docs/display-settings/02 §2）。
            item {
                SegmentedColumn(title = VmDisplayTexts.GROUP) {
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Display,
                            title = VmDisplayTexts.TITLE,
                            description = displaySummary,
                            onClick = { navigator.push(Route.VmDisplay(id)) },
                        )
                    }
                }
            }

            // 4. 操作分组。「运行」的第一行固定是 ROM 版本管理（docs/rom-package/14-版本管理/01 §2）：
            //    它是"换系统"的入口，排在诊断类动作之前。
            item {
                SegmentedColumn(title = "运行") {
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Android,
                            title = RomTexts.VERSION_ENTRY_TITLE,
                            description = romVersionSummary,
                            // 与 Miuix 分支、与「分辨率」页同一条路径：直接 push 一个 NavPage，走标准转场。
                            onClick = { navigator.push(Route.VmRomVersion(id)) },
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.BugReport,
                            title = "检查运行环境",
                            description = "诊断 GPU / 输入 / 存储是否就绪",
                            onClick = { appState.inspect(id) },
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Launcher,
                            title = "测试桌面",
                            description = "只验证桌面能不能起来",
                            onClick = { appState.checkDesktop(id) },
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Edit,
                            title = "重命名",
                            description = "只改显示名，不移动数据目录",
                            onClick = { showRename = true },
                        )
                    }
                }
            }

            item {
                if (VmActions.canStop(runtime)) SegmentedColumn(title = "电源") {
                    item {
                        BaseWidget(
                            icon = AppIcons.Stop,
                            iconColor = MaterialTheme.colorScheme.error,
                            title = "停止虚拟机",
                            titleStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.error),
                            description = "结束本次运行",
                            onClick = { confirmStop = true },
                        )
                    }
                }
            }

            item {
                SegmentedColumn(title = "导入与权限") {
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.InstallPackageSource,
                            title = "安装 APK",
                            description = "把宿主上的 APK 装进虚拟机",
                            onClick = { pickApk.launch(arrayOf("application/vnd.android.package-archive")) },
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Download,
                            title = "导入文件",
                            description = "把宿主文件拷进虚拟机共享目录",
                            onClick = { pickFiles.launch(arrayOf("*/*")) },
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Launcher,
                            title = "导入已安装应用",
                            description = "从宿主已装应用里挑几个装进来",
                            onClick = {
                                val activity = context as? android.app.Activity
                                if (activity != null) {
                                    io.a10vm.ui.showInstalledAppsDialog(activity) { packages ->
                                        appState.importApps(id, packages)
                                    }
                                }
                            },
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Permission,
                            title = "开启麦克风 / 相机",
                            description = "允许客体录音与拍照",
                            onClick = {
                                appState.enableMicrophone(id)
                                appState.enableCamera(id)
                            },
                        )
                    }
                }
            }

            item {
                SegmentedColumn(title = "记录与删除") {
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Info,
                            title = "查看运行记录",
                            description = "只看这台虚拟机的记录",
                            onClick = {
                                appState.openRecordsFor(id)
                                navigator.popUntil { it is Route.Main }
                            },
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Delete,
                            title = "删除这台虚拟机",
                            description = "数据目录会被一并删除，需要再确认一次",
                            onClick = { confirmDelete = true },
                        )
                    }
                }
            }
        }
    }

    if (showRename && device != null) {
        RenameDialog(
            device = device,
            onDismiss = { showRename = false },
            onConfirm = { newName ->
                val result = appState.rename(device.id, newName)
                appState.recordRename(device, result is RenameResult.Renamed, VmActions.renameMessage(result))
                if (result is RenameResult.Renamed) showRename = false
                result
            },
        )
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("停止虚拟机？") },
            text = { Text("将结束这台虚拟机的当前运行。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    appState.stop(id)
                }) { Text("停止", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("取消") } },
        )
    }

    if (confirmDelete && device != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 " + device.name) },
            text = { Text("这台虚拟机的数据目录会被一并删除，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        appState.delete(device.id)
                        navigator.pop()
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}