// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.settings.home

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.processlimit.ProcessLimitSupport
import io.a10vm.runtime.limits.ChildProcessLimit
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.miuixCardInset
import io.a10vm.ui.page.miuix.widgets.miuixSection
import io.a10vm.ui.theme.VerseBoxTheme
import io.a10vm.ui.verse.LocalAppState
import io.a10vm.ui.vm.VmActions
import me.kanadex.versebox.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 主页（Miuix 分支）。
 *
 * 版式照 InstallerX 的 MiuixStatusGrid：**一张大状态卡 + 一行两张数字卡**，没有别的装饰。
 * - 状态卡：安卓 12 进程限制（InstallerX 那张「未设置默认安装程序」卡的同款解剖结构）；
 * - 数字卡：ROM / 虚拟机，只放标题与数字，高度用 IntrinsicSize.Min 对齐。
 *   ROM 的数字是**总数**（内置 + 本地 + 旧格式），与 ROM 管理列表的行数一致；标题不能写「内置 ROM」，
 *   否则本地导入的 ROM 会被算进一个自称「内置」的数字里（docs/rom-package/06 §1.2）。
 *
 * 所有间距 / 左右边距都来自 [MiuixSettingsScaffold] + [miuixSection] + [miuixCardInset]，
 * 本文件不再手写任何横向 padding（12dp 卡片边距由版式层统一给）。
 */
@Composable
fun MiuixHomePage(
    enableBlur: Boolean,
    title: String,
    vmCount: Int,
    outerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onNavigateToVms: () -> Unit,
) {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val appState = LocalAppState.current
    val backupState by me.kanadex.geometryos.backup.LocalBackup.current.state.collectAsStateWithLifecycle()
    val devices by appState.devices.collectAsStateWithLifecycle()
    val runtimes by appState.runtimes.collectAsStateWithLifecycle()
    val limit by appState.childLimit.collectAsStateWithLifecycle()
    // ROM 数量只有一份口径：ROM 总数（内置 + 本地 + 旧格式），点进去就是列表的行数。
    // 以前读的是 availableRoms（只管「已解包、可直接启动」那一档），新装应用的卡片因此显示 0。
    val romTotal by appState.romTotal.collectAsStateWithLifecycle()

    MiuixSettingsScaffold(
        title = title,
        enableBlur = enableBlur,
        outerPadding = outerPadding,
    ) {
        item {
            MiuixStatusGrid(
                modifier = Modifier.miuixCardInset(),
                romCount = romTotal,
                vmCount = devices.size,
                runningCount = VmActions.runningCount(runtimes),
                onProcessLimitClick = { navigator.push(Route.ProcessLimit) },
                onRomsClick = { navigator.push(Route.RomList()) },
                onVmsClick = onNavigateToVms,
                limit = limit,
            )
        }

        miuixSection {
            BasicComponent(title = "备份管理", summary = backupState.countLabel,
                onClick = { navigator.push(Route.BackupList()) })
        }

        // 想恢复分类小标题：miuixSection("运行环境") { ... } —— 标题横向 28dp 与卡片文字自动对齐。
        miuixSection {
            BasicComponent(title = "机型", summary = Build.MANUFACTURER + " " + Build.MODEL)
            BasicComponent(
                title = "系统",
                summary = "Android " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT,
            )
            BasicComponent(
                title = "支持的 ABI",
                summary = Build.SUPPORTED_ABIS.joinToString(" · "),
            )
        }

        miuixSection {
            BasicComponent(
                title = "了解 VerseBox-CE",
                summary = "开源虚拟机项目的仓库与文档",
                onClick = {
                    uriHandler.openUri("https://github.com/GeometryOS/VerseBox-guest-runtime-a15/")
                },
                endActions = {
                    Icon(painter = painterResource(id = R.drawable.ic_link_icon), contentDescription = null)
                },
            )
        }
    }
}

@Composable
private fun MiuixStatusGrid(
    romCount: Int,
    vmCount: Int,
    runningCount: Int,
    onProcessLimitClick: () -> Unit,
    onRomsClick: () -> Unit,
    onVmsClick: () -> Unit,
    limit: ChildProcessLimit,
    modifier: Modifier = Modifier,
) {
    val healthy = limit.ok
    // 1. 计算大状态卡的背景色和文字颜色：限制已解除就走正常容器色，不再一律红
    val containerColor = when {
        healthy && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        healthy && VerseBoxTheme.isDark -> androidx.compose.ui.graphics.Color(0xFF1A3825)
        healthy -> androidx.compose.ui.graphics.Color(0xFFDFFAE4)
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.errorContainer
        VerseBoxTheme.isDark -> androidx.compose.ui.graphics.Color(0xFF381A1A)
        else -> androidx.compose.ui.graphics.Color(0xFFFAEEEE)
    }

    val textContentColor = if (MiuixTheme.isDynamicColor) {
        if (healthy) MiuixTheme.colorScheme.onSecondaryContainer else MiuixTheme.colorScheme.onErrorContainer
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    val descTextColor = textContentColor.copy(alpha = 0.8f)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 大状态卡
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = containerColor),
            onClick = onProcessLimitClick,
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // 背景图标
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(50.dp, 38.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(170.dp),
                        imageVector = if (healthy) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        tint = when {
                            healthy && MiuixTheme.isDynamicColor ->
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)

                            healthy -> androidx.compose.ui.graphics.Color(0xFF36D167)
                            MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.error.copy(alpha = 0.8f)
                            else -> androidx.compose.ui.graphics.Color(0xFFD13636)
                        },
                        contentDescription = null,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = 16.dp),
                ) {
                    // Slot 1: 主标题
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = ProcessLimitSupport.homeTitle(limit),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textContentColor,
                    )
                    Spacer(Modifier.height(2.dp))

                    // Slot 2: 描述
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = ProcessLimitSupport.homeDescription(limit),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = descTextColor,
                    )

                    Spacer(Modifier.height(36.dp))

                    // Slot 3: 底部提示
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = ProcessLimitSupport.homeAction(limit),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = descTextColor,
                    )
                }
            }
        }

        // 两列数字小卡
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeStatCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                title = "ROM",
                value = romCount.toString(),
                onClick = onRomsClick,
            )
            HomeStatCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                title = "虚拟机",
                value = runningCount.toString() + " / " + vmCount,
                onClick = onVmsClick,
            )
        }
    }
}

@Composable
private fun HomeStatCard(modifier: Modifier = Modifier, title: String, value: String, onClick: () -> Unit) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = title,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}
