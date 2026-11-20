// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import io.a10vm.ui.processlimit.ProcessLimitSupport

/**
 * 子进程限制提示（Miuix 分支）。
 *
 * 外壳**就是 [MiuixConfirmDialog]**（同一副骨架 = 虚拟机页「停止虚拟机？」那张），
 * 只把正文换成带链接的 [AnnotatedString]：
 * - 链接样式留空（styles = null），miuix 的 Text 会自动套 MiuixTheme.colorScheme.primary + 下划线；
 * - 因此按钮区不再放「去解决」——正文里的链接就是唯一入口，避免同一个动作出现两次。
 *
 * 两个按钮对应「取消」（dismiss，本次不再显示但下次异常仍会提示）与
 * 「不再提示」（抑制到下次真正解除之后）。
 */
@Composable
fun MiuixLimitDialog(
    show: Boolean,
    onOpenFix: () -> Unit,
    onDismiss: () -> Unit,
    onMute: () -> Unit,
) {
    MiuixConfirmDialog(
        show = show,
        title = ProcessLimitSupport.ALERT_TITLE,
        message = buildAnnotatedString {
            append(ProcessLimitSupport.ALERT_BODY)
            append('\n')
            withLink(
                LinkAnnotation.Clickable(
                    tag = "process_limit_fix",
                    styles = null,
                    linkInteractionListener = { onOpenFix() },
                ),
            ) {
                append(ProcessLimitSupport.ALERT_LINK)
            }
        },
        confirmText = ProcessLimitSupport.ALERT_MUTE,
        onConfirm = onMute,
        onDismissRequest = onDismiss,
    )
}
