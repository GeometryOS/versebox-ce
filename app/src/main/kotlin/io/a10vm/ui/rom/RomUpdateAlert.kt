// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.rom

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.a10vm.ui.page.miuix.widgets.MiuixConfirmDialog
import io.a10vm.ui.verse.LocalAppState
import io.a10vm.ui.verse.RomUpdatePrompt
import me.kanadex.geometryos.rom.RomTexts

/**
 * 可选更新的提示宿主。启动时发现同系列有更新版本，问一句。
 * 需要先选版本那种情况不在这里，由 AppState.start 直接跳转。
 */
@Composable
fun RomUpdateAlertHost(useMiuix: Boolean) {
    val appState = LocalAppState.current
    var prompt by remember { mutableStateOf<RomUpdatePrompt?>(null) }

    // 一次性事件，读到就从 AppState 清掉，免得转屏或切 tab 又弹一遍
    LaunchedEffect(appState.romUpdatePrompt) {
        if (appState.romUpdatePrompt == null) return@LaunchedEffect
        prompt = appState.consumeRomUpdatePrompt()
    }

    val current = prompt ?: return
    val onGo = {
        prompt = null
        appState.openVmRomVersion(current.vmId)
    }

    val onLater = {
        prompt = null
        appState.resumeStart(current.vmId)
    }
    if (useMiuix) {
        MiuixConfirmDialog(
            show = true,
            title = current.title,
            message = current.message,
            confirmText = current.confirmText,
            dismissText = current.dismissText ?: RomTexts.UPDATE_LATER,
            onConfirm = onGo,
            onDismissRequest = onLater,
        )
    } else {
        AlertDialog(
            onDismissRequest = onLater,
            title = { Text(current.title) },
            text = { Text(current.message) },
            confirmButton = { TextButton(onClick = onGo) { Text(current.confirmText) } },
            dismissButton = {
                TextButton(onClick = onLater) { Text(current.dismissText ?: RomTexts.UPDATE_LATER) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}