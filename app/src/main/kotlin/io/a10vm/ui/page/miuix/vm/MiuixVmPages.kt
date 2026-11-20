// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.vm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.runtime.RenameResult
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.icons.AppMiuixIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.miuix.widgets.MiuixBackButton
import io.a10vm.ui.page.miuix.widgets.MiuixHintTextField
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.MiuixConfirmDialog
import io.a10vm.ui.page.miuix.widgets.MiuixStatusCard
import io.a10vm.ui.page.miuix.widgets.miuixCardInset
import io.a10vm.ui.page.miuix.widgets.miuixSection
import io.a10vm.ui.theme.getMiuixAppBarColor
import io.a10vm.ui.theme.rememberMiuixBlurBackdrop
import io.a10vm.ui.theme.verseBoxMiuixBlurEffect
import io.a10vm.ui.vm.CreateVmAction
import io.a10vm.ui.vm.VmActions
import me.kanadex.geometryos.display.VmDisplayTexts
import me.kanadex.geometryos.display.android.DisplaySettings
import io.a10vm.ui.vm.rememberCreateVmViewModel
import io.a10vm.ui.vm.startAndOpen
import io.a10vm.ui.verse.LocalAppState
import io.a10vm.ui.verse.VmRuntime
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog


/**
 * 新建虚拟机（Miuix 分支）。
 *
 * 版式对齐安装器的编辑配置页：
 * - 左上角「关闭 ×」，右上角「保存 ✓」（创建）；
 * - 名称输入框裸露在页面最上方（不在卡片里），聚焦时描 2dp primary 边框；
 * - 安卓版本是独立的一行入口，跳到选择页选完回来，本页不铺一列 ROM；
 * - 输入中的侧滑返回会被未保存确认拦住（路由层已关侧滑，这里再兜一层返回键）。
 */
@Composable
fun MiuixCreateVmPage(useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val appState = LocalAppState.current
    val viewModel = rememberCreateVmViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 选中项的唯一真相是 AppState.createVmRomId，由 CreateVmViewModel 自己订阅；
    // 页面不再转发一次（docs/ui-rework/02 §3：UI 只渲染 + 发 Action）。

    MiuixSettingsScaffold(
        title = "新建虚拟机",
        enableBlur = useBlur,
        closeButton = true,
        imePadding = true,
        topBarActions = {
            IconButton(onClick = { viewModel.dispatch(CreateVmAction.Create) }) {
                Icon(imageVector = AppMiuixIcons.Ok, contentDescription = "保存")
            }
        },
    ) {
        item {
            MiuixHintTextField(
                value = state.name,
                onValueChange = { viewModel.dispatch(CreateVmAction.SetName(it)) },
                labelText = "名称",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 12.dp),
            )
        }
        item {
            SmallTitle("ROM")
            Card(modifier = Modifier.miuixCardInset()) {
                BasicComponent(
                    title = "ROM",
                    // 摘要由逻辑层拼好：`Android 15 V1.0.0 · 内置`（含版本号与来源）。
                    summary = state.romSummary.ifBlank { "点击选择要安装的系统" },
                    onClick = { navigator.push(Route.RomList(pickForCreate = true)) },
                )
            }
        }
        state.error?.let {
            item {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }

    // 创建成功 → 跳管理页
    LaunchedEffect(state.createdId) {
        val id = state.createdId ?: return@LaunchedEffect
        viewModel.dispatch(CreateVmAction.ConsumeCreated)
        navigator.pop()
        navigator.push(Route.VmManage(id))
    }
}
/**
 * 虚拟机管理（Miuix 分支）。
 *
 * 版式：
 * 1. 顶部状态卡 —— 与主页「子系统限制」卡同一解剖，直接反映 运行中 / 正在准备 / 未运行 / 操作未完成；
 * 2. 卡片与列表之间的**强调色主按钮** —— 未运行=启动虚拟机，运行中=进入桌面，忙碌=禁用；
 * 3. 下面才是分组的操作列表（停止 / 检查 / 测试桌面 / 重命名 / 导入 / 相机麦克风 / 记录 / 删除）。
 *
 * 可用性统一由 [VmActions] 判断，间距由 [MiuixSettingsScaffold] + [miuixSection] 统一给。
 */
@Composable
fun MiuixVmManagePage(id: String, useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appState = LocalAppState.current
    val devices by appState.devices.collectAsStateWithLifecycle()
    val runtimes by appState.runtimes.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.id == id }
    val runtime = runtimes[id] ?: VmRuntime()

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
        if (spec == null) me.kanadex.geometryos.rom.RomTexts.VERSION_SUMMARY_NONE
        else me.kanadex.geometryos.rom.RomTexts.versionSummary(
            spec.name,
            me.kanadex.geometryos.rom.RomKeys.versionOf(spec.id),
        )
    }
    val tone = VmActions.tone(runtime)
    // 管理页大状态卡与主页「子系统限制」卡同款解剖：容器色随状态走。
    val statusContainer = when (tone) {
        VmActions.Tone.Error -> MiuixTheme.colorScheme.errorContainer
        VmActions.Tone.Active -> MiuixTheme.colorScheme.primaryContainer
        VmActions.Tone.Busy -> MiuixTheme.colorScheme.tertiaryContainer
        VmActions.Tone.Idle -> MiuixTheme.colorScheme.secondaryContainer
    }
    val statusContent = when (tone) {
        VmActions.Tone.Error -> MiuixTheme.colorScheme.onErrorContainer
        VmActions.Tone.Active -> MiuixTheme.colorScheme.onPrimaryContainer
        VmActions.Tone.Busy -> MiuixTheme.colorScheme.onTertiaryContainer
        VmActions.Tone.Idle -> MiuixTheme.colorScheme.onSecondaryContainer
    }
    val statusDetail = when (tone) {
        VmActions.Tone.Error -> "上一次操作没有走完。可以先去「查看运行记录」看卡在哪，或直接重新启动。"
        VmActions.Tone.Active -> "虚拟机正在运行。关掉窗口也能从这里重新进入桌面。"
        VmActions.Tone.Busy -> "正在准备启动，窗口会自动弹出来，稍等一下就好。"
        VmActions.Tone.Idle -> "还没有启动。点下面的蓝色按钮启动这台虚拟机。"
    } + if (runtime.detail.isBlank()) "" else "\n" + runtime.detail

    val primaryLabel = VmActions.launchLabel(runtime) + "虚拟机"
    // 分辨率这一行的小标题：由策略库按「这台虚拟机的模式 + 宿主真实几何」算出来，
    // 页面不拼字符串、不读 DisplayMetrics（docs/display-settings/02 §2）。
    val displaySummary = remember(device?.id, device?.displayMode) {
        device?.let { DisplaySettings.summaryOf(context, it) }.orEmpty()
    }

    MiuixSettingsScaffold(
        title = device?.name ?: "虚拟机",
        enableBlur = useBlur,
        showBackButton = true,
    ) {
        // 1. 状态大卡：主页同款解剖（背景大图标 + 标题 + 说明），容器色随状态区分
        item {
            MiuixStatusCard(
                modifier = Modifier.miuixCardInset(),
                title = VmActions.stateLabel(runtime),
                description = statusDetail,
                containerColor = statusContainer,
                contentColor = statusContent,
                iconTint = statusContent,
                actionText = "系统：" + (romTitle ?: device?.romId ?: "未知"),
                onClick = { device?.let { VmActions.launch(context, appState, it, runtime) } },
            )
        }

        // 2. 卡片与列表之间的强调色主按钮
        item {
            Button(
                modifier = Modifier
                    .miuixCardInset()
                    .fillMaxWidth(),
                onClick = { device?.let { VmActions.launch(context, appState, it, runtime) } },
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = primaryLabel, style = MiuixTheme.textStyles.button)
            }
        }

        // 3. 「显示」分组：位于启动主按钮与「运行」之间（docs/display-settings/02 §2）。
        miuixSection(VmDisplayTexts.GROUP) {
            BasicComponent(
                title = VmDisplayTexts.TITLE,
                summary = displaySummary,
                onClick = { navigator.push(Route.VmDisplay(id)) },
            )
        }

        // 4. 操作分组。「运行」的第一行固定是 ROM 版本管理（docs/rom-package/14-版本管理/01 §2）。
        miuixSection("运行") {
            BasicComponent(
                title = me.kanadex.geometryos.rom.RomTexts.VERSION_ENTRY_TITLE,
                summary = romVersionSummary,
                // 入口必须是**直接 push 一个 NavPage**（与「分辨率」逐项相同），
                // 不许走 AppState 请求：请求路径为了回到 Main 会先 popUntil 再 push，
                // 用户看到的是硬切、没有转场（docs/ui-rework/01 §1.1 第 3 条）。
                onClick = { navigator.push(Route.VmRomVersion(id)) },
            )
            BasicComponent(
                title = "检查运行环境",
                summary = "诊断 GPU / 输入 / 存储是否就绪",
                enabled = VmActions.canInspect(runtime),
                onClick = { appState.inspect(id) },
            )
            BasicComponent(
                title = "测试桌面",
                summary = "只验证桌面能不能起来",
                enabled = VmActions.canInspect(runtime),
                onClick = { appState.checkDesktop(id) },
            )
            BasicComponent(
                title = "重命名",
                summary = "只改显示名，不移动数据目录",
                onClick = { showRename = true },
            )
        }

        if (VmActions.canStop(runtime)) miuixSection("电源") {
            BasicComponent(
                title = "停止虚拟机",
                titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                summary = "结束本次运行",
                onClick = { confirmStop = true },
            )
        }

        miuixSection("应用与文件") {
            BasicComponent(
                title = "导入应用到这台虚拟机",
                summary = "从宿主已装应用里挑几个装进来",
                enabled = VmActions.canImport(runtime),
                onClick = {
                    val activity = context as? android.app.Activity
                    if (activity != null) {
                        io.a10vm.ui.showInstalledAppsDialog(activity) { packages ->
                            appState.importApps(id, packages)
                        }
                    }
                },
            )
            BasicComponent(
                title = "安装 APK",
                summary = "把宿主上的 APK 装进虚拟机",
                enabled = VmActions.canImport(runtime),
                onClick = { pickApk.launch(arrayOf("application/vnd.android.package-archive")) },
            )
            BasicComponent(
                title = "导入图片、视频和文件",
                summary = "拷进虚拟机共享目录",
                enabled = VmActions.canImport(runtime),
                onClick = { pickFiles.launch(arrayOf("*/*")) },
            )
        }

        miuixSection("相机、声音与网络") {
            BasicComponent(
                title = "开启麦克风",
                summary = "允许客体录音",
                enabled = VmActions.canImport(runtime),
                onClick = { appState.enableMicrophone(id) },
            )
            BasicComponent(
                title = "开启相机",
                summary = "允许客体拍照",
                enabled = VmActions.canImport(runtime),
                onClick = { appState.enableCamera(id) },
            )
            BasicComponent(
                title = "设置手机 Wi-Fi",
                summary = "网络不通时先看宿主连接",
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }

        miuixSection("记录与删除") {
            BasicComponent(
                title = "查看运行记录",
                summary = "只看这台虚拟机的记录",
                onClick = {
                    appState.openRecordsFor(id)
                    navigator.popUntil { it is Route.Main }
                },
            )
            BasicComponent(
                title = "删除这台虚拟机",
                summary = "数据目录会被一并删除，不可恢复",
                enabled = VmActions.canDelete(runtime),
                onClick = { confirmDelete = true },
            )
        }
    }

    if (showRename && device != null) {
        MiuixRenameDialog(
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

    MiuixConfirmDialog(
        show = confirmStop,
        title = "停止虚拟机？",
        message = "将结束这台虚拟机的当前运行。",
        confirmText = "停止",
        onConfirm = {
            confirmStop = false
            appState.stop(id)
        },
        onDismissRequest = { confirmStop = false },
    )

    // 删除确认：唯一容器是库的 WindowDialog（经 MiuixConfirmDialog 薄封装），
    // 不再自己拼 Card + TextButton —— 那种写法没有 dim、返回键与按钮等宽语义
    // （docs/ui-rework/01 §5.1 / §5.6）。
    MiuixConfirmDialog(
        show = confirmDelete && device != null,
        title = device?.let { "删除 " + it.name } ?: "删除虚拟机？",
        message = "这台虚拟机的数据目录会被一并删除，无法恢复。",
        confirmText = "删除",
        onConfirm = {
            val target = device
            confirmDelete = false
            if (target != null) {
                appState.delete(target.id)
                navigator.pop()
            }
        },
        onDismissRequest = { confirmDelete = false },
    )
}