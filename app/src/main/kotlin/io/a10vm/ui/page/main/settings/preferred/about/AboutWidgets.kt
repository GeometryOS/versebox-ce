// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.settings.preferred.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.core.device.model.Level
import io.a10vm.core.env.AppConfig
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported

/**
 * 关于页顶部的状态卡（Material 3 分支）。
 *
 * 内容是「应用自己是谁」：图标 / 名字 / 版本 / 构建等级 / 有没有新版本。
 * 与 InstallerX 的 StatusWidget 同一个位置与同一个职责，只是去掉了安装器的提权状态。
 */
@Composable
fun StatusWidget(viewModel: AboutViewModel, useBlur: Boolean) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val levelText = when (AppConfig.LEVEL) {
        Level.STABLE -> "Stable release"
        Level.PREVIEW -> "Preview"
        Level.UNSTABLE -> "Beta"
    }
    val backdrop = rememberMaterial3BlurBackdrop(useBlur && isRuntimeShaderSupported())

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .verseBoxMaterial3BlurEffect(backdrop),
        colors = CardDefaults.cardColors(
            containerColor = backdrop.getMaterial3AppBarColor(),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            uiState.appIcon?.let { icon ->
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text("VerseBox", style = MaterialTheme.typography.titleLarge)
                Text(
                    levelText + " · " + AppConfig.VERSION_NAME + " (" + AppConfig.VERSION_CODE + ")",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (uiState.hasUpdate) {
                    Text(
                        "有可用更新：" + uiState.remoteVersion,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 「正在获取更新」指示器。VerseBox 不做在线更新检查，所以这里永远不显示；
 * 保留这个入口是为了让关于页的代码与 InstallerX 保持同形，将来接更新时不用改页面。
 */
@Composable
fun UpdateLoadingIndicator(backdrop: LayerBackdrop?, viewModel: AboutViewModel) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    if (!uiState.hasUpdate) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .verseBoxMaterial3BlurEffect(backdrop)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("正在检查更新…", style = MaterialTheme.typography.bodySmall)
    }
}
