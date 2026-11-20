// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 VerseBox contributors
package io.a10vm.ui.page.main.settings.preferred.theme

import androidx.compose.ui.graphics.Color
import io.a10vm.domain.settings.model.preferences.PredictiveBackAnimation
import io.a10vm.domain.settings.model.preferences.PredictiveBackExitDirection
import io.a10vm.domain.settings.model.preferences.theme.PaletteStyle
import io.a10vm.domain.settings.model.preferences.theme.ThemeColorSpec
import io.a10vm.domain.settings.model.preferences.theme.ThemeMode

/** 主题页的意图。页面只发意图，写盘由 ViewModel 统一落在 AppPreferences。 */
sealed interface ThemeSettingsAction {
    data class ChangeUseMiuix(val useMiuix: Boolean) : ThemeSettingsAction
    data class SetUseBlur(val enable: Boolean) : ThemeSettingsAction
    data class SetThemeMode(val mode: ThemeMode) : ThemeSettingsAction
    data class SetPaletteStyle(val style: PaletteStyle) : ThemeSettingsAction
    data class SetColorSpec(val spec: ThemeColorSpec) : ThemeSettingsAction
    data class SetUseDynamicColor(val use: Boolean) : ThemeSettingsAction
    data class SetUseMiuixMonet(val use: Boolean) : ThemeSettingsAction
    data class SetUseAppleFloatingBar(val use: Boolean) : ThemeSettingsAction
    data class SetDynColorFollowPkgIcon(val follow: Boolean) : ThemeSettingsAction
    data class SetDynColorFollowPkgIconForLiveActivity(val follow: Boolean) : ThemeSettingsAction
    data class SetSeedColor(val color: Color, val isWallpaperSeedColor: Boolean) : ThemeSettingsAction
    data class ChangePreferSystemIcon(val preferSystemIcon: Boolean) : ThemeSettingsAction
    data class SetPredictiveBackAnimation(val animation: PredictiveBackAnimation) : ThemeSettingsAction
    data class SetPredictiveBackExitDirection(val direction: PredictiveBackExitDirection) : ThemeSettingsAction
}
