// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 VerseBox contributors
package io.a10vm.ui.page.main.settings.preferred.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import io.a10vm.domain.settings.model.preferences.ThemeState
import io.a10vm.ui.settings.AppPreferences
import io.a10vm.ui.theme.material.PresetColors
import io.a10vm.ui.theme.material.RawColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题页的状态与动作入口。
 *
 * 唯一的写入目标是 [AppPreferences]（本应用设置表的唯一写入口），
 * 所以 Miuix 与 Material 3 两套页面共用这一个 ViewModel —— 与 InstallerX 一致。
 */
class ThemeSettingsViewModel(private val preferences: AppPreferences) {
    private val _state = MutableStateFlow(convert(preferences.theme.value))
    val state: StateFlow<ThemeSettingsState> = _state.asStateFlow()

    private fun convert(t: ThemeState) = ThemeSettingsState(
        showMiuixUI = t.useMiuix,
        useBlur = t.useBlur,
        themeMode = t.themeMode,
        paletteStyle = t.paletteStyle,
        colorSpec = t.colorSpec,
        useDynamicColor = t.useDynamicColor,
        useMiuixMonet = t.useMiuixMonet,
        useAppleFloatingBar = t.useAppleFloatingBar,
        seedColor = Color(t.seedColor),
        availableColors = PresetColors.map { RawColor(it.key, it.color) },
        predictiveBackAnimation = t.predictiveBackAnimation,
        predictiveBackExitDirection = t.predictiveBackExitDirection,
    )

    private fun refresh() {
        _state.value = convert(preferences.theme.value)
    }

    fun dispatch(action: ThemeSettingsAction) {
        when (action) {
            is ThemeSettingsAction.ChangeUseMiuix -> preferences.setUseMiuix(action.useMiuix)
            is ThemeSettingsAction.SetUseBlur -> preferences.setUseBlur(action.enable)
            is ThemeSettingsAction.SetThemeMode -> preferences.setThemeMode(action.mode)
            is ThemeSettingsAction.SetPaletteStyle -> preferences.setPaletteStyle(action.style)
            is ThemeSettingsAction.SetColorSpec -> preferences.setColorSpec(action.spec)
            is ThemeSettingsAction.SetUseDynamicColor -> preferences.setUseDynamicColor(action.use)
            is ThemeSettingsAction.SetUseMiuixMonet -> preferences.setUseMiuixMonet(action.use)
            is ThemeSettingsAction.SetUseAppleFloatingBar -> preferences.setUseAppleFloatingBar(action.use)
            is ThemeSettingsAction.SetSeedColor -> preferences.setSeedColor(action.color.toArgb())
            is ThemeSettingsAction.SetPredictiveBackAnimation -> preferences.setPredictiveBack(action.animation)
            is ThemeSettingsAction.SetPredictiveBackExitDirection -> preferences.setPredictiveBackDirection(action.direction)
            // VerseBox 侧暂未实现的开关：结构保留、不改状态，绝不假装写成功。
            is ThemeSettingsAction.SetDynColorFollowPkgIcon,
            is ThemeSettingsAction.SetDynColorFollowPkgIconForLiveActivity,
            is ThemeSettingsAction.ChangePreferSystemIcon,
            -> Unit
        }
        refresh()
    }
}

@Composable
fun rememberThemeSettingsViewModel(): ThemeSettingsViewModel {
    val context = LocalContext.current
    return remember(context) { ThemeSettingsViewModel(AppPreferences.get(context)) }
}
