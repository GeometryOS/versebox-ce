// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package io.a10vm.ui.page.main.settings.preferred.about

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 关于页的视图状态。字段与 InstallerX 的同名 State 对齐，去掉了本工程没有的提权授权器字段。
 */
data class AboutState(
    val hasUpdate: Boolean = false,
    val remoteVersion: String = "",
    val enableFileLogging: Boolean = false,
    val appIcon: ImageBitmap? = null,
)
