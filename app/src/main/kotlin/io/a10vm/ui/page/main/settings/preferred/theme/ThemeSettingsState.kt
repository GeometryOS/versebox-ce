// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 VerseBox contributors
package io.a10vm.ui.page.main.settings.preferred.theme

import android.os.Build
import androidx.compose.ui.graphics.Color
import io.a10vm.domain.settings.model.preferences.PredictiveBackAnimation
import io.a10vm.domain.settings.model.preferences.PredictiveBackExitDirection
import io.a10vm.domain.settings.model.preferences.theme.PaletteStyle
import io.a10vm.domain.settings.model.preferences.theme.ThemeColorSpec
import io.a10vm.domain.settings.model.preferences.theme.ThemeMode
import io.a10vm.ui.theme.material.PresetColors
import io.a10vm.ui.theme.material.RawColor

/**
 * 主题设置页面的视图状态。
 *
 * 字段与 InstallerX-Revived 的同名 State 一一对应：这样两边页面可以逐行对照，
 * 换主题引擎时不会有一边漏掉某个开关。
 */
data class ThemeSettingsState(
    val showMiuixUI: Boolean = false,
    val useBlur: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val colorSpec: ThemeColorSpec = ThemeColorSpec.SPEC_2025,
    val useDynamicColor: Boolean = true,
    val useMiuixMonet: Boolean = false,
    val useAppleFloatingBar: Boolean = false,
    val seedColor: Color = PresetColors[0].color,
    val availableColors: List<RawColor> = PresetColors,
    // VerseBox 还没有「按包图标取色」「实时活动」，这两个开关按原样保留结构、
    // 页面里从数据侧拿到的永远是 false，等真做了再接线（不留假开关）。
    val useDynColorFollowPkgIcon: Boolean = false,
    val useDynColorFollowPkgIconForLiveActivity: Boolean = false,
    val preferSystemIcon: Boolean = false,
    val showLiveActivity: Boolean = false,
    val predictiveBackAnimation: PredictiveBackAnimation = PredictiveBackAnimation.MIUIX,
    val predictiveBackExitDirection: PredictiveBackExitDirection = PredictiveBackExitDirection.ALWAYS_RIGHT,
)
