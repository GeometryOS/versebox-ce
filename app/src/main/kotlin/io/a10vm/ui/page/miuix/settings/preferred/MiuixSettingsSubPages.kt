// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.settings.preferred

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.miuixSection
import io.a10vm.ui.settings.SettingsBackup
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 网络设置（Miuix）。 */
@Composable
fun MiuixNetworkPage(useBlur: Boolean) {
    val context = LocalContext.current

    MiuixSettingsScaffold(
        title = "网络设置",
        enableBlur = useBlur,
        showBackButton = true,
    ) {
        miuixSection("虚拟机网络") {
            BasicComponent(
                title = "网络模式",
                summary = "虚拟机与宿主共用联网：宿主能上网，虚拟机就能上网",
            )
            BasicComponent(
                title = "打开系统 Wi-Fi 设置",
                summary = "网络不通时先确认宿主连接",
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
    }
}

/** 实验室（Miuix）：暂时是空的。 */
@Composable
fun MiuixLabPage(useBlur: Boolean) {
    MiuixSettingsScaffold(
        title = "实验室",
        enableBlur = useBlur,
        showBackButton = true,
    ) {
        miuixSection {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "还没有实验性功能",
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = "这一页是预留给还在验证的能力的，目前没有开关。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
