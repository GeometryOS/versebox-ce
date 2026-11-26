// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
//
// 第一屏的对话框（Material 3 分支）：容器就是 M3 AlertDialog，不自造卡片与遮罩。
package io.a10vm.ui.boot

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.a10vm.ui.page.main.widget.setting.BlockingLoadingIndicator
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * 第一屏的两张对话框（Material 3 分支）。
 *
 * 形状照 `page/main/widget/dialog/LimitDialog.kt`：正文（含链接）走 AlertDialog 的 `text` 槽，
 * 两个出口走 confirmButton / dismissButton 槽，不自造按钮行、不自造内距。
 * 与 miuix 侧的唯一区别是链接样式必须显式给——M3 的 Text 不会自动给 LinkAnnotation 上色。
 */
@Composable
fun Material3BootDialogs(state: BootUiState, onAction: (BootAction) -> Unit) {
    state.romGate?.let { gate ->
        AlertDialog(
            onDismissRequest = { onAction(BootAction.RespondToGate(gate, false)) },
            title = { Text(gate.title) },
            text = { Text(gate.message) },
            confirmButton = {
                TextButton(onClick = { onAction(BootAction.RespondToGate(gate, true)) }) { Text(gate.confirmText) }
            },
            dismissButton = gate.dismissText?.let { label ->
                { TextButton(onClick = { onAction(BootAction.RespondToGate(gate, false)) }) { Text(label) } }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    state.fault?.let { fault ->
        BootAlertDialog(
            title = BootTexts.faultTitle(fault),
            body = BootTexts.faultMessage(fault),
            confirmText = BootTexts.faultConfirm(fault),
            dismissText = BootTexts.faultDismiss(fault),
            onOpenRecord = { onAction(BootAction.OpenRecord) },
            onConfirm = { onAction(BootAction.ConfirmFault) },
            onDismiss = { onAction(BootAction.DismissFault) },
        )
    }

    state.alert?.let { alert ->
        BootAlertDialog(
            title = alert.title,
            body = alert.message,
            confirmText = alert.confirmText,
            dismissText = alert.dismissText,
            onOpenRecord = { onAction(BootAction.OpenRecord) },
            onConfirm = { onAction(BootAction.ShareSnapshot) },
            onDismiss = { onAction(BootAction.DismissAlert) },
        )
    }

    BlockingLoadingIndicator(visible = state.busy, text = state.busyText, backdrop = null)

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = { onAction(BootAction.ConsumeMessage) },
            title = { Text(state.messageTitle) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { onAction(BootAction.ConsumeMessage) }) {
                    Text("知道了")
                }
            },
        )
    }
}

/** 一张 M3 AlertDialog：正文 + 链接 + 两个动作槽（消极在左、积极在右）。 */
@Composable
private fun BootAlertDialog(
    title: String,
    body: String,
    confirmText: String,
    dismissText: String,
    onOpenRecord: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                buildAnnotatedString {
                    append(body)
                    append('\n')
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "boot_view_record",
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                            linkInteractionListener = { onOpenRecord() },
                        ),
                    ) {
                        append(BootTexts.LINK_VIEW_RECORD)
                    }
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}