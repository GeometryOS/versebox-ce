// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
//
// 第一屏的对话框（Miuix 分支）：容器全部来自白名单，不自造 Card + 遮罩。
package io.a10vm.ui.boot

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import io.a10vm.ui.page.miuix.widgets.MiuixBlockingLoadingDialog
import io.a10vm.ui.page.miuix.widgets.MiuixConfirmDialog
import io.a10vm.ui.page.miuix.widgets.MiuixNoticeDialog

/**
 * 第一屏的两张对话框 + 打包中的阻塞提示（Miuix 分支）。
 *
 * 形态**照 `ui/processlimit/ProcessLimitAlert.kt`**：同一副 [MiuixConfirmDialog] 骨架
 * （同一个 miuix WindowDialog、同一段内距、同一排双等宽按钮），只换文案与动作；
 * 正文里的链接是「查看这次运行的记录」的唯一入口，按钮区不再重复放。
 */
@Composable
fun MiuixBootDialogs(state: BootUiState, onAction: (BootAction) -> Unit) {
    state.romGate?.let { gate ->
        gate.dismissText?.let { dismiss ->
            MiuixConfirmDialog(
                show = true,
                title = gate.title,
                message = gate.message,
                confirmText = gate.confirmText,
                dismissText = dismiss,
                onConfirm = { onAction(BootAction.RespondToGate(gate, true)) },
                onDismissRequest = { onAction(BootAction.RespondToGate(gate, false)) },
            )
        } ?: MiuixNoticeDialog(
            show = true,
            title = gate.title,
            message = gate.message,
            confirmText = gate.confirmText,
            onDismissRequest = { onAction(BootAction.RespondToGate(gate, true)) },
        )
    }

    state.fault?.let { fault ->
        MiuixConfirmDialog(
            show = true,
            title = BootTexts.faultTitle(fault),
            message = bootLinkMessage(BootTexts.faultMessage(fault)) { onAction(BootAction.OpenRecord) },
            confirmText = BootTexts.faultConfirm(fault),
            dismissText = BootTexts.faultDismiss(fault),
            onConfirm = { onAction(BootAction.ConfirmFault) },
            onDismissRequest = { onAction(BootAction.DismissFault) },
        )
    }

    state.alert?.let { alert ->
        MiuixConfirmDialog(
            show = true,
            title = alert.title,
            message = bootLinkMessage(alert.message) { onAction(BootAction.OpenRecord) },
            confirmText = alert.confirmText,
            dismissText = alert.dismissText,
            onConfirm = { onAction(BootAction.ShareSnapshot) },
            onDismissRequest = { onAction(BootAction.DismissAlert) },
        )
    }

    state.message?.let { message ->
        MiuixNoticeDialog(
            show = true,
            title = state.messageTitle,
            message = message,
            confirmText = "知道了",
            onDismissRequest = { onAction(BootAction.ConsumeMessage) },
        )
    }

    MiuixBlockingLoadingDialog(visible = state.busy, text = state.busyText)
}

/**
 * 正文 + 一段可点链接。
 *
 * `styles = null`：miuix 的 Text 会自动给 LinkAnnotation 套 primary + 下划线，
 * 与 ProcessLimitAlert 的写法逐字一致（不自己上色，避免两处链接两种样式）。
 */
private fun bootLinkMessage(body: String, onClick: () -> Unit) = buildAnnotatedString {
    append(body)
    append('\n')
    withLink(
        LinkAnnotation.Clickable(
            tag = "boot_view_record",
            styles = null,
            linkInteractionListener = { onClick() },
        ),
    ) {
        append(BootTexts.LINK_VIEW_RECORD)
    }
}