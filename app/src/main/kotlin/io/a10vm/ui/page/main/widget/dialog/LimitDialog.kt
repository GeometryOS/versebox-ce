// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.widget.dialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import io.a10vm.ui.processlimit.ProcessLimitSupport

/**
 * 子进程限制提示（Material 3 分支）。
 *
 * 完全按 M3 [AlertDialog] 的形状写：正文（含链接）走 text 槽，两个出口走
 * confirmButton / dismissButton 槽，不自造按钮行、不自造内距。
 *
 * 与 Miuix 侧的唯一区别是链接样式必须显式给：M3 的 Text 不会自动给 LinkAnnotation 上色。
 * 正文里的链接是唯一入口，按钮区不再重复「去解决」。
 */
@Composable
fun LimitDialog(
    show: Boolean,
    onOpenFix: () -> Unit,
    onDismiss: () -> Unit,
    onMute: () -> Unit,
) {
    // M3 的 AlertDialog 没有 show 参数：必须自己守住开关，否则一进页面就弹。
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ProcessLimitSupport.ALERT_TITLE) },
        text = {
            Text(
                buildAnnotatedString {
                    append(ProcessLimitSupport.ALERT_BODY)
                    append('\n')
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "process_limit_fix",
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                            linkInteractionListener = { onOpenFix() },
                        ),
                    ) {
                        append(ProcessLimitSupport.ALERT_LINK)
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onMute) { Text(ProcessLimitSupport.ALERT_MUTE) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
