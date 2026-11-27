// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package io.a10vm.ui.page.miuix.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

// 页面首帧的加载态，与 M3 的 PageLoadingIndicator 同构，指示器用 miuix 的 InfiniteProgressIndicator。
// 不遮罩、不拦返回；数据到了由页面整块换成列表或详情（docs/ui-rework/15）。
@Composable
fun MiuixPageLoading(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        InfiniteProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
fun MiuixBlockingLoadingDialog(visible: Boolean, text: String) {
    WindowDialog(show = visible) {
        BackHandler {
            // Block input while the operation is running.
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfiniteProgressIndicator()
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = text)
        }
    }
}
