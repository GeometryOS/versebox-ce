// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package io.a10vm.ui.page.main.settings.preferred.about

sealed interface AboutAction {
    data object PerformUpdate : AboutAction
    data class SetEnableFileLogging(val enable: Boolean) : AboutAction
    data object ShareLog : AboutAction
}
