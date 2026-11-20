// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.settings.home

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.TaskAlt
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.navigation.Route
import io.a10vm.ui.page.main.widget.setting.BaseWidget
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import io.a10vm.ui.processlimit.ProcessLimitSupport
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import io.a10vm.ui.verse.LocalAppState
import io.a10vm.ui.vm.VmActions
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.foundation.layout.plus

/**
 * 主页（Material 3 Expressive 分支）。
 *
 * 版式对齐 VerseBox 标准规范：
 * 由各个组件自身控制 16dp 的左右边距，避免和 LazyColumn 的 contentPadding 产生叠加。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomePage(
    useBlur: Boolean,
    title: String,
    outerPadding: PaddingValues,
    vmCount: Int,
    onNavigateToVms: () -> Unit,
) {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val appState = LocalAppState.current
    val devices by appState.devices.collectAsStateWithLifecycle()
    val runtimes by appState.runtimes.collectAsStateWithLifecycle()
    val limit by appState.childLimit.collectAsStateWithLifecycle()
    // ROM 数量只有一份口径：ROM 总数（内置 + 本地 + 旧格式），与 ROM 管理列表的行数一致。
    // 以前读的是 availableRoms（只管「已解包、可直接启动」那一档），新装应用的卡片因此显示 0。
    val romTotal by appState.romTotal.collectAsStateWithLifecycle()

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = rememberMaterial3BlurBackdrop(useBlur)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.verseBoxMaterial3BlurEffect(backdrop),
                title = {
                    Text(
                        text = title,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                },
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
            // 【修复点 1】：去除外层的 16dp padding，只保留系统级别的 inset 和外部传进来的 padding
            contentPadding = paddingValues + outerPadding,
        ) {
            // ---------- 大状态卡 ----------
            item {
                ProcessStatusCard(
                    // 【修复点 2】：给大卡单独设置左右 16dp 边距
                    modifier = Modifier.padding(horizontal = 16.dp),
                    title = ProcessLimitSupport.homeTitle(limit),
                    description = ProcessLimitSupport.homeDescription(limit),
                    bottomText = ProcessLimitSupport.homeAction(limit),
                    healthy = limit.ok,
                    onClick = { navigator.push(Route.ProcessLimit) },
                )
            }

            item { Spacer(modifier = Modifier.size(12.dp)) }

            // ---------- 数字小卡 ----------
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 【修复点 3】：给数字小卡所在的 Row 单独设置左右 16dp 边距
                        .padding(horizontal = 16.dp)
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        title = "ROM",
                        value = romTotal.toString(),
                        onClick = { navigator.push(Route.RomList()) },
                    )
                    StatCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        title = "虚拟机",
                        value = VmActions.vmCountLabel(runtimes, devices.size),
                        onClick = onNavigateToVms,
                    )
                }
            }

            // ---------- 运行环境 ----------
            item {
                SegmentedColumn(
                    title = "运行环境",
                ) {
                    item {
                        BaseWidget(
                            title = "机型",
                            description = Build.MANUFACTURER + " " + Build.MODEL,
                            iconPlaceholder = false,
                        )
                    }
                    item {
                        BaseWidget(
                            title = "系统",
                            description = "Android " + Build.VERSION.RELEASE +
                                    " · API " + Build.VERSION.SDK_INT,
                            iconPlaceholder = false,
                        )
                    }
                    item {
                        BaseWidget(
                            title = "支持的 ABI",
                            description = Build.SUPPORTED_ABIS.joinToString(" · "),
                            iconPlaceholder = false,
                        )
                    }
                }
            }

            // ---------- 了解更多 ----------
            item {
                SegmentedColumn(
                    title = "了解更多",
                ) {
                    item {
                        BaseWidget(
                            iconPlaceholder = false,
                            title = "了解 VerseBox-CE",
                            description = "开源虚拟机项目的仓库与文档",
                            onClick = {
                                uriHandler.openUri("https://github.com/GeometryOS/VerseBox-guest-runtime-a15/")
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------- 辅助组件 ----------

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    onClick: () -> Unit = {},
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProcessStatusCard(
    modifier: Modifier = Modifier, // 【修复点 4】：添加 modifier 参数以接受外部边距
    title: String,
    description: String,
    bottomText: String,
    healthy: Boolean,
    onClick: () -> Unit = {},
) {
    val containerColor = if (healthy) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (healthy) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    val icon = if (healthy) Icons.TwoTone.TaskAlt else Icons.TwoTone.Warning

    ElevatedCard(
        onClick = onClick,
        modifier = modifier, // 应用外部传入的 modifier
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .size(28.dp)
                    .padding(horizontal = 4.dp),
            )
            Column(
                modifier = Modifier.padding(start = 20.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = contentColor,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmallEmphasized,
                    color = contentColor,
                )
                if (bottomText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = bottomText,
                        style = MaterialTheme.typography.bodySmallEmphasized,
                        color = contentColor.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}