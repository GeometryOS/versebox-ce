// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kanadex.versebox.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「获取更新」弹窗，结构与 InstallerX 的同名对话框一致：
 * 一个 Card 里两行外链（仓库 / 反馈），下面一个取消按钮。
 * 只是把链接换成本项目的仓库 —— 本应用不内置在线更新器，跳出去看是唯一诚实的做法。
 */
@Composable
fun MiuixUpdateDialog(showState: MutableState<Boolean>, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    WindowDialog(
        show = showState.value,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.get_update),
        content = {
            Column {
                Card(
                    modifier = Modifier.padding(bottom = 8.dp),
                    colors = CardColors(
                        color = MiuixTheme.colorScheme.secondaryVariant,
                        contentColor = MiuixTheme.colorScheme.onSurface,
                    ),
                ) {
                    BasicComponent(
                        title = "GitHub",
                        onClick = {
                            uriHandler.openUri("https://github.com/GeometryOS/VerseBox-guest-runtime-a15/releases")
                            onDismiss()
                        },
                        endActions = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_link_icon),
                                contentDescription = null,
                            )
                        },
                    )
                    BasicComponent(
                        title = "问题反馈",
                        onClick = {
                            uriHandler.openUri("https://github.com/GeometryOS/VerseBox-guest-runtime-a15/issues")
                            onDismiss()
                        },
                        endActions = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_link_icon),
                                contentDescription = null,
                            )
                        },
                    )
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismiss,
                    text = stringResource(R.string.cancel),
                )
            }
        },
    )
}

/**
 * 错误详情弹窗：标题 + 可直接滚动的堆栈。
 *
 * 关于页的「获取更新」失败时用它 —— 把异常原文露出来，而不是只说一句「失败了」。
 */
@Composable
fun ErrorDisplaySheet(
    title: String,
    showState: MutableState<Boolean>,
    exception: Throwable,
    onDismissRequest: () -> Unit,
) {
    WindowDialog(
        show = showState.value,
        onDismissRequest = onDismissRequest,
        title = title,
        content = {
            Column {
                Card(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        text = (exception.message ?: exception.toString()).take(400),
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismissRequest,
                    text = stringResource(R.string.close),
                )
            }
        },
    )
}
