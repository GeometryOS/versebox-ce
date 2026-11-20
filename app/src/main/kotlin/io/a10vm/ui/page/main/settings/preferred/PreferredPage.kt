// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.settings.preferred

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import io.a10vm.core.device.model.Level
import io.a10vm.core.env.AppConfig
import me.kanadex.versebox.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.main.widget.setting.NavigationItemWidget
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import io.a10vm.ui.page.main.widget.setting.SwitchWidget
import io.a10vm.ui.settings.SettingsBackup
import io.a10vm.ui.settings.SettingsBackupAction
import io.a10vm.ui.settings.rememberSettingsBackupViewModel
import io.a10vm.ui.verse.rememberAppPreferences
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop


/** 设置（Material 3 Expressive 分支）：全部 material3 组件，与 Miuix 分支同一套信息结构。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferredPage(
    useBlur: Boolean,
    sharedBackdrop: LayerBackdrop? = null,
    title: String,
    outerPadding: PaddingValues,
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = sharedBackdrop ?: rememberMaterial3BlurBackdrop(useBlur)
    val preferences = rememberAppPreferences()
    val showVmBadge by preferences.showVmBadge.collectAsStateWithLifecycle()

    // 导出/还原：状态与 IO 在 ViewModel，页面只开系统文件选择器、只渲染结果。
    val backupViewModel = rememberSettingsBackupViewModel()
    val backupState by backupViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SettingsBackup.MIME_TYPE),
    ) { uri -> if (uri != null) backupViewModel.dispatch(SettingsBackupAction.ExportTo(uri)) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) backupViewModel.dispatch(SettingsBackupAction.RequestRestore(uri)) }

    LaunchedEffect(backupState.exportFileName) {
        val name = backupState.exportFileName ?: return@LaunchedEffect
        exportLauncher.launch(name)
    }
    LaunchedEffect(backupState.message) {
        val message = backupState.message ?: return@LaunchedEffect
        backupViewModel.dispatch(SettingsBackupAction.ConsumeMessage)
        snackbarHostState.showSnackbar(message)
    }

    var showUpdate by remember { mutableStateOf(false) }

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
                bottom = outerPadding.calculateBottomPadding() + 12.dp,
            ),
        ) {
            item {
                SegmentedColumn(title = "外观") {
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Theme,
                            title = "主题设置",
                            description = "更改应用主题",
                            onClick = { navigator.push(Route.Theme) },
                        )
                    }
                    item {
                        SwitchWidget(
                            icon = AppIcons.Notification,
                            title = "虚拟机数量角标",
                            description = "显示已创建的虚拟机数量",
                            checked = showVmBadge,
                            onCheckedChange = { preferences.setShowVmBadge(it) },
                        )
                    }
                }
            }
            item {
                SegmentedColumn(title = "网络与后台") {
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Network,
                            title = "网络设置",
                            description = "虚拟机网络模式与代理",
                            onClick = { navigator.push(Route.Network) },
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.BatteryOptimization,
                            title = "忽略电池优化",
                            description = if (isIgnoring(context)) "已忽略，虚拟机不会被后台杀掉" else "未忽略，长时间运行可能被系统冻结",
                            onClick = { openBatterySettings(context) },
                        )
                    }
                }
            }
            item {
                SegmentedColumn(title = "实验性功能") {
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Lab,
                            title = "实验室",
                            description = "尚未开放的功能会先出现在这里",
                            onClick = { navigator.push(Route.Lab) },
                        )
                    }
                }
            }
            item {
                SegmentedColumn(title = "备份与还原") {
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Save,
                            title = "导出设置",
                            description = "存成 JSON 文件（用系统的文件选择器选位置）",
                            onClick = {
                                if (!backupState.busy) backupViewModel.dispatch(SettingsBackupAction.RequestExport)
                            },
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Download,
                            title = "还原设置",
                            description = "从备份文件恢复，先校验再确认",
                            onClick = {
                                if (!backupState.busy) {
                                    restoreLauncher.launch(
                                        arrayOf(SettingsBackup.MIME_TYPE, "application/octet-stream", "text/plain"),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            item {
                // 版本副标题：等级 + 版本号（与关于页同一套资源与常量），不是"这一项关于什么"的空话。
                val versionLabel = stringResource(
                    when (AppConfig.LEVEL) {
                        Level.STABLE -> R.string.stable
                        Level.PREVIEW -> R.string.preview
                        Level.UNSTABLE -> R.string.unstable
                    },
                )
                SegmentedColumn(title = "关于") {
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Update,
                            title = "获取更新",
                            description = "到 GitHub 查看最新版本",
                            onClick = { showUpdate = true },
                        )
                    }
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Info,
                            title = "关于 VerseBox",
                            description = "$versionLabel ${AppConfig.VERSION_NAME}",
                            onClick = { navigator.push(Route.About) },
                        )
                    }
                }
            }
        }
    }

    backupState.preview?.let { preview ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { backupViewModel.dispatch(SettingsBackupAction.DismissPreview) },
            title = { Text("还原设置？") },
            text = { Text(SettingsBackup.describe(preview)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { backupViewModel.dispatch(SettingsBackupAction.ConfirmRestore) },
                ) { Text("还原") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { backupViewModel.dispatch(SettingsBackupAction.DismissPreview) },
                ) { Text("取消") }
            },
        )
    }

    if (showUpdate) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showUpdate = false },
            title = { Text("获取更新") },
            text = { Text("最新版本发布在 GitHub，点下面的按钮过去看看。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showUpdate = false
                        uriHandler.openUri("https://github.com/GeometryOS/VerseBox-guest-runtime-a15/releases")
                    },
                ) { Text("GitHub") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showUpdate = false }) { Text("取消") }
            },
        )
    }
}

private fun isIgnoring(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return true
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
