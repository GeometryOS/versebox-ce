// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.settings.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.miuixSection
import io.a10vm.ui.records.rememberLogViewModel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 日志原文预览（Miuix 分支）。
 *
 * 读文件、截断、打包分享都在 LogViewModel 里；页面只渲染文本并提供分享入口。
 */
@Composable
fun MiuixRecordLogPage(recordId: Long, path: String, useBlur: Boolean) {
    val viewModel = rememberLogViewModel(recordId, path)
    val state by viewModel.state.collectAsStateWithLifecycle()

    MiuixSettingsScaffold(
        title = path,
        enableBlur = useBlur,
        showBackButton = true,
        topBarActions = {
            IconButton(
                enabled = state.canShare,
                onClick = { viewModel.share() },
            ) {
                Icon(imageVector = AppIcons.Share, contentDescription = "分享这条记录")
            }
        },
    ) {
        miuixSection {
            Column(modifier = Modifier.padding(16.dp)) {
                    MiuixText(
                        text = state.text,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    state.message?.let { message ->
                        MiuixText(
                            modifier = Modifier.padding(top = 8.dp),
                            text = message,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error,
                        )
                }
            }
        }
    }
}
