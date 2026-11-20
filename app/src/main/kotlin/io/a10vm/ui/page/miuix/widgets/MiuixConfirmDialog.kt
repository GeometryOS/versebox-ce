// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 确认对话框：标题由 [WindowDialog] 自己画，正文是一行说明，下面两个等宽按钮。
 */
@Composable
fun MiuixConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    dismissText: String = "取消",
) = MiuixConfirmDialog(
    show = show,
    title = title,
    message = AnnotatedString(message),
    confirmText = confirmText,
    onConfirm = onConfirm,
    onDismissRequest = onDismissRequest,
    dismissText = dismissText,
)

/**
 * 同一副骨架，正文换成 [AnnotatedString]（正文里要内嵌可点击链接时用这个重载）。
 *
 * 内距、按钮等宽、右侧 primary 色都由这里给，调用方只换正文，**不再自造第二套间距**。
 */
@Composable
fun MiuixConfirmDialog(
    show: Boolean,
    title: String,
    message: AnnotatedString,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    dismissText: String = "取消",
) {
    WindowDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        title = title,
    ) {
        Column {
            Text(
                text = message,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    onClick = onDismissRequest,
                    text = dismissText,
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm,
                    text = confirmText,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/**
 * 单按钮告知框：与 [MiuixConfirmDialog] **同一副骨架**（同一个 [WindowDialog] + 同一段内距 +
 * 同一个 primary 文字按钮），只是没有「取消」——给「结论已经定了、用户只能知道」的场合用
 * （例如 ROM 检查发现问题但原包已不在，此时唯一动作是重新导入）。
 *
 * 为什么不做成 `MiuixConfirmDialog(confirmText = "知道了")`：那会给一个不存在的选择配一个
 * 「取消」按钮，用户点了也分不清是取消检查还是取消修复。
 */
@Composable
fun MiuixNoticeDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onDismissRequest: () -> Unit,
) {
    WindowDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        title = title,
    ) {
        Column {
            Text(
                text = message,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(24.dp))
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismissRequest,
                text = confirmText,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
