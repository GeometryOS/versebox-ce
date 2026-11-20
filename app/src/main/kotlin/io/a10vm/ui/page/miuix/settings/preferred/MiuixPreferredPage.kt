// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.settings.preferred

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.a10vm.core.device.model.Level
import io.a10vm.core.env.AppConfig
import me.kanadex.versebox.R
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.miuix.widgets.MiuixConfirmDialog
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.MiuixSwitchWidget
import io.a10vm.ui.page.miuix.widgets.MiuixUpdateDialog
import io.a10vm.ui.page.miuix.widgets.miuixSection
import io.a10vm.ui.settings.AppPreferences
import io.a10vm.ui.settings.SettingsBackup
import io.a10vm.ui.settings.SettingsBackupAction
import io.a10vm.ui.settings.rememberSettingsBackupViewModel
import io.a10vm.ui.verse.rememberAppPreferences
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/**
 * 应用设置的二级页入口（主题 / 网络 / 备份 / 实验室 / 关于 / 许可）。
 *
 * 分类 = 一行 [miuixSection]；标题左右 28dp 与卡片文字自动对齐，分类间距由骨架的
 * 12dp 网格统一给，本文件不出现任何手写 padding / Spacer。
 */
@Composable
fun MiuixPreferredPage(
    enableBlur: Boolean,
    title: String,
    outerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val preferences: AppPreferences = rememberAppPreferences()
    val hideLauncherIcon by preferences.hideLauncherIcon.collectAsStateWithLifecycle()
    val collectRecords by preferences.collectRecords.collectAsStateWithLifecycle()
    val showVmBadge by preferences.showVmBadge.collectAsStateWithLifecycle()

    // 设置的导出/还原：状态与 IO 都在 ViewModel，页面只开系统文件选择器、只渲染结果。
    val backupViewModel = rememberSettingsBackupViewModel()
    val backupState by backupViewModel.state.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SettingsBackup.MIME_TYPE),
    ) { uri -> if (uri != null) backupViewModel.dispatch(SettingsBackupAction.ExportTo(uri)) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) backupViewModel.dispatch(SettingsBackupAction.RequestRestore(uri)) }

    // 「导出设置」→ 用逻辑层给的文件名打开系统文件选择器。
    LaunchedEffect(backupState.exportFileName) {
        val name = backupState.exportFileName ?: return@LaunchedEffect
        exportLauncher.launch(name)
    }
    LaunchedEffect(backupState.message) {
        val message = backupState.message ?: return@LaunchedEffect
        backupViewModel.dispatch(SettingsBackupAction.ConsumeMessage)
        snackbarHostState.showSnackbar(message)
    }

    var showUpdateDialog by remember { mutableStateOf(false) }

    MiuixSettingsScaffold(
        title = title,
        enableBlur = enableBlur,
        outerPadding = outerPadding,
    ) {
        miuixSection("外观") {
            BasicComponent(
                title = "主题设置",
                summary = "更改应用主题",
                onClick = { navigator.push(Route.Theme) },
            )
            MiuixSwitchWidget(
                title = "虚拟机数量角标",
                description = "显示已创建的虚拟机数量",
                checked = showVmBadge,
                onCheckedChange = { preferences.setShowVmBadge(it) },
            )
        }

        miuixSection("网络与后台") {
            BasicComponent(
                title = "网络设置",
                summary = "虚拟机网络模式与代理",
                onClick = { navigator.push(Route.Network) },
            )
            BasicComponent(
                title = "忽略电池优化",
                summary = if (isIgnoring(context)) "已忽略，虚拟机不会被后台杀掉" else "未忽略，长时间运行可能被系统冻结",
                onClick = { openBatterySettings(context) },
            )
        }

        miuixSection("实验性功能") {
            BasicComponent(
                title = "实验室",
                summary = "尚未开放的功能会先出现在这里",
                onClick = { navigator.push(Route.Lab) },
            )
        }

        miuixSection("备份与还原") {
            BasicComponent(
                title = "导出设置",
                summary = "存成 JSON 文件（用系统的文件选择器选位置）",
                enabled = !backupState.busy,
                onClick = { backupViewModel.dispatch(SettingsBackupAction.RequestExport) },
            )
            BasicComponent(
                title = "还原设置",
                summary = "从备份文件恢复，先校验再确认",
                enabled = !backupState.busy,
                onClick = {
                    restoreLauncher.launch(arrayOf(SettingsBackup.MIME_TYPE, "application/octet-stream", "text/plain"))
                },
            )
        }

        miuixSection("关于") {
            // 版本副标题：等级 + 版本号（与关于页同一套资源与常量），不是"这一项关于什么"的空话。
            val versionLabel = stringResource(
                when (AppConfig.LEVEL) {
                    Level.STABLE -> R.string.stable
                    Level.PREVIEW -> R.string.preview
                    Level.UNSTABLE -> R.string.unstable
                },
            )
            BasicComponent(
                title = "获取更新",
                summary = "到 GitHub 查看最新版本",
                onClick = { showUpdateDialog = true },
            )

            BasicComponent(
                title = "关于 VerseBox",
                summary = "$versionLabel ${AppConfig.VERSION_NAME}",
                onClick = { navigator.push(Route.About) },
            )
        }
    }

    // 还原确认：正文来自逻辑层（备份来自哪个版本、要写多少项），不是一句「确定吗」。
    backupState.preview?.let { preview ->
        MiuixConfirmDialog(
            show = true,
            title = "还原设置？",
            message = SettingsBackup.describe(preview),
            confirmText = "还原",
            onConfirm = { backupViewModel.dispatch(SettingsBackupAction.ConfirmRestore) },
            onDismissRequest = { backupViewModel.dispatch(SettingsBackupAction.DismissPreview) },
        )
    }

    if (showUpdateDialog) {
        val showState = remember { mutableStateOf(true) }
        MiuixUpdateDialog(showState = showState, onDismiss = { showUpdateDialog = false })
    }
}

private fun isIgnoring(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return true
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatterySettings(context: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
