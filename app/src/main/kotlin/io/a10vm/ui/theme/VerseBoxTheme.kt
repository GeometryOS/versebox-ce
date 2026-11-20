// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 VerseBox contributors
package io.a10vm.ui.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.core.view.WindowCompat
import io.a10vm.domain.settings.model.preferences.ThemeState
import io.a10vm.domain.settings.model.preferences.theme.PaletteStyle
import io.a10vm.domain.settings.model.preferences.theme.ThemeColorSpec
import io.a10vm.domain.settings.model.preferences.theme.ThemeMode
import io.a10vm.ui.theme.material.animateAsState
import io.a10vm.ui.theme.material.dynamicColorScheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec as MiuixColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle as MiuixPaletteStyle

private val LocalIsDark = staticCompositionLocalOf { false }
private val LocalPaletteStyle = staticCompositionLocalOf { PaletteStyle.Expressive }
private val LocalThemeColorSpec = staticCompositionLocalOf { ThemeColorSpec.SPEC_2025 }
private val LocalSeedColor = staticCompositionLocalOf { Color.Unspecified }
private val LocalThemeMode = staticCompositionLocalOf { ThemeMode.SYSTEM }
private val LocalUseMiuixMonet = staticCompositionLocalOf { false }
private val LocalUseDynamicColor = staticCompositionLocalOf { false }

val LocalVerseBoxColorScheme = staticCompositionLocalOf<ColorScheme> { error("No ColorScheme provided") }

/**
 * 主题读取口，与 InstallerX-Revived 的 VerseBoxTheme 一一对应：
 * 一个 object 提供只读的当前主题角色色，一个 composable 负责生成它。
 */
object VerseBoxTheme {
    val colorScheme: ColorScheme
        @Composable @ReadOnlyComposable
        get() = LocalVerseBoxColorScheme.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalIsDark.current

    val seedColor: Color
        @Composable @ReadOnlyComposable
        get() = LocalSeedColor.current

    val paletteStyle: PaletteStyle
        @Composable @ReadOnlyComposable
        get() = LocalPaletteStyle.current

    val colorSpec: ThemeColorSpec
        @Composable @ReadOnlyComposable
        get() = LocalThemeColorSpec.current

    val themeMode: ThemeMode
        @Composable @ReadOnlyComposable
        get() = LocalThemeMode.current

    val useMiuixMonet: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalUseMiuixMonet.current

    val useDynamicColor: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalUseDynamicColor.current
}

/** 由 ThemeState 驱动的入口（应用壳用这个）。 */
@Composable
fun VerseBoxTheme(state: ThemeState, content: @Composable () -> Unit) {
    VerseBoxTheme(
        useMiuix = state.useMiuix,
        themeMode = state.themeMode,
        paletteStyle = state.paletteStyle,
        colorSpec = state.colorSpec,
        useDynamicColor = state.useDynamicColor,
        useMiuixMonet = state.useMiuixMonet,
        seedColor = Color(state.seedColor),
        content = content,
    )
}

@Composable
fun VerseBoxTheme(
    useMiuix: Boolean,
    themeMode: ThemeMode,
    paletteStyle: PaletteStyle,
    colorSpec: ThemeColorSpec,
    useDynamicColor: Boolean,
    useMiuixMonet: Boolean,
    seedColor: Color,
    content: @Composable () -> Unit,
) {
    // 切 UI 引擎时保留内容节点身份：连接、滚动位置、Pager 状态都不重建。
    val preservedContent = remember {
        movableContentOf<@Composable () -> Unit> { targetContent ->
            targetContent()
        }
    }

    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val keyColor = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        colorResource(id = android.R.color.system_accent1_500)
    } else {
        seedColor
    }

    // 1. 先按规范生成基础配色
    val baseColorScheme = remember(keyColor, isDark, paletteStyle, colorSpec) {
        dynamicColorScheme(
            keyColor = keyColor,
            isDark = isDark,
            style = paletteStyle,
            colorSpec = colorSpec,
        )
    }

    // 2. 再包一层颜色过渡，切主题时不闪
    val animatedColorScheme = baseColorScheme.animateAsState()

    CompositionLocalProvider(
        LocalIsDark provides isDark,
        LocalPaletteStyle provides paletteStyle,
        LocalSeedColor provides seedColor,
        LocalVerseBoxColorScheme provides animatedColorScheme,
        LocalThemeMode provides themeMode,
        LocalUseMiuixMonet provides useMiuixMonet,
        LocalUseDynamicColor provides useDynamicColor,
        LocalThemeColorSpec provides colorSpec,
    ) {
        // Android 10 起系统会给导航栏强制加对比度底色，必须关掉，否则模糊底栏会出现一条灰带。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            NavigationBarContrastHandler()
        }

        if (useMiuix) {
            VerseBoxMiuixTheme(
                darkTheme = isDark,
                themeMode = themeMode,
                useDynamicColor = useDynamicColor,
                useMiuixMonet = useMiuixMonet,
                seedColor = seedColor,
                paletteStyle = paletteStyle,
                colorSpec = colorSpec,
            ) {
                preservedContent(content)
            }
        } else {
            VerseBoxMaterialExpressiveTheme(
                darkTheme = isDark,
                colorScheme = animatedColorScheme,
            ) {
                preservedContent(content)
            }
        }
    }
}

@Composable
fun VerseBoxMaterialExpressiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorScheme: ColorScheme,
    compatStatusBarColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (compatStatusBarColor) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as ComponentActivity).window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    // Material 3 Expressive：连 MotionScheme 都用 expressive()，这正是 MD3E 与普通 M3 的区别。
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
        content = content,
    )
}

@Composable
fun VerseBoxMiuixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode,
    useMiuixMonet: Boolean,
    useDynamicColor: Boolean = false,
    compatStatusBarColor: Boolean = true,
    seedColor: Color,
    paletteStyle: PaletteStyle,
    colorSpec: ThemeColorSpec,
    content: @Composable () -> Unit,
) {
    if (compatStatusBarColor) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as ComponentActivity).window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    val controller = if (useMiuixMonet) {
        // --- Miuix 自带取色引擎 ---
        val keyColor = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            colorResource(id = android.R.color.system_accent1_500)
        } else {
            seedColor
        }

        val colorSchemeMode = when (themeMode) {
            ThemeMode.SYSTEM -> ColorSchemeMode.MonetSystem
            ThemeMode.LIGHT -> ColorSchemeMode.MonetLight
            ThemeMode.DARK -> ColorSchemeMode.MonetDark
        }

        val style = when (paletteStyle) {
            PaletteStyle.TonalSpot -> MiuixPaletteStyle.TonalSpot
            PaletteStyle.Neutral -> MiuixPaletteStyle.Neutral
            PaletteStyle.Vibrant -> MiuixPaletteStyle.Vibrant
            PaletteStyle.Expressive -> MiuixPaletteStyle.Expressive
            PaletteStyle.Rainbow -> MiuixPaletteStyle.Rainbow
            PaletteStyle.FruitSalad -> MiuixPaletteStyle.FruitSalad
            PaletteStyle.Monochrome -> MiuixPaletteStyle.Monochrome
            PaletteStyle.Fidelity -> MiuixPaletteStyle.Fidelity
            PaletteStyle.Content -> MiuixPaletteStyle.Content
            PaletteStyle.Cmf -> MiuixPaletteStyle.Cmf
        }

        val colorSpecVersion = when (paletteStyle.effectiveSpec(colorSpec)) {
            ThemeColorSpec.SPEC_2026 -> MiuixColorSpec.Spec2026
            ThemeColorSpec.SPEC_2025 -> MiuixColorSpec.Spec2025
            ThemeColorSpec.SPEC_2021 -> MiuixColorSpec.Spec2021
        }

        remember(colorSchemeMode, keyColor, paletteStyle, colorSpecVersion, darkTheme) {
            ThemeController(
                colorSchemeMode = colorSchemeMode,
                keyColor = keyColor,
                paletteStyle = style,
                colorSpec = colorSpecVersion,
                isDark = darkTheme,
            )
        }
    } else {
        // --- Miuix 默认主题 ---
        val colorSchemeMode = when (themeMode) {
            ThemeMode.SYSTEM -> ColorSchemeMode.System
            ThemeMode.LIGHT -> ColorSchemeMode.Light
            ThemeMode.DARK -> ColorSchemeMode.Dark
        }

        remember(colorSchemeMode, darkTheme) {
            ThemeController(
                colorSchemeMode = colorSchemeMode,
                isDark = darkTheme,
            )
        }
    }

    MiuixTheme(
        controller = controller,
        content = content,
    )
}

@RequiresApi(Build.VERSION_CODES.Q)
@Composable
private fun NavigationBarContrastHandler() {
    val configuration = LocalConfiguration.current
    val activity = LocalActivity.current

    DisposableEffect(configuration) {
        val window = activity?.window
        window?.isNavigationBarContrastEnforced = false

        onDispose { /* 保持关闭即可 */ }
    }
}
