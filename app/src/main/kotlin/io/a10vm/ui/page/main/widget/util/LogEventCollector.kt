// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 VerseBox contributors
package io.a10vm.ui.page.main.widget.util

import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.a10vm.ui.page.main.settings.preferred.about.AboutEvent
import io.a10vm.ui.page.main.settings.preferred.about.AboutViewModel

/**
 * 关于页的一次性事件收集器。
 *
 * 与 InstallerX 的 LogEventCollector 同名同位置：页面只发事件，落地动作（分享、提示）在这里。
 */
@Composable
fun LogEventCollector(viewModel: AboutViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is AboutEvent.OpenLogShare -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享日志"))
                }

                is AboutEvent.ShareLogFailed -> Toast.makeText(context, event.error, Toast.LENGTH_SHORT).show()

                else -> Unit
            }
        }
    }
}
