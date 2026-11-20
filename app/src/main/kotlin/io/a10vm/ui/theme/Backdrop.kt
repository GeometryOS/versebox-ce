// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package io.a10vm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Remember a LayerBackdrop with a solid background to prevent alpha-blending artifacts.
 * @param enableBlur Whether the blur effect is globally enabled.
 * @return A LayerBackdrop instance if supported and enabled, null otherwise.
 */
@Composable
fun rememberMiuixBlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    // textureBlur 走的是 AGSL/RuntimeShader：有些机型 RenderEffect 报告支持、
    // 但 AGSL 一上屏就在 RenderThread 里 SIGSEGV（ColorOS 的真实案例）。两个都过才启用。
    if (!enableBlur || !isRenderEffectSupported() || !isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/**
 * Determine the app bar background color based on the Backdrop availability.
 * @return Transparent if Backdrop is active, otherwise the default surface color.
 */
@Composable
fun LayerBackdrop?.getMiuixAppBarColor(): Color = this?.let { Color.Transparent } ?: MiuixTheme.colorScheme.surface

/**
 * Apply a standard glassmorphism blur effect using Miuix Backdrop.
 * @param backdrop The LayerBackdrop providing the visual source.
 * @param enabled Whether the effect is locally enabled for this component.
 * @param blurRadius The radius of the Gaussian blur.
 * @param shape The clipping shape for the blurred area.
 */
@Composable
fun Modifier.verseBoxMiuixBlurEffect(
    backdrop: LayerBackdrop?,
    enabled: Boolean = true,
    blurRadius: Float = 25f,
    shape: Shape = RectangleShape,
): Modifier {
    // Return early if disabled or backdrop is unavailable
    if (!enabled || backdrop == null) return this

    // Grab the current theme surface color for blending
    val blendColor = MiuixTheme.colorScheme.surface.copy(alpha = 0.8f)

    return this.then(
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = blurRadius,
            colors = BlurColors(
                blendColors = listOf(
                    BlendColorEntry(color = blendColor),
                ),
            ),
        ),
    )
}

/**
 * Remember a LayerBackdrop for Material 3 with a surfaceContainer background
 * to prevent alpha-blending artifacts.
 *
 * @param enableBlur Whether the blur effect is globally enabled.
 * @return A LayerBackdrop instance if supported and enabled, null otherwise.
 */
@Composable
fun rememberMaterial3BlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    // Material 3 分支的模糊。之前的崩溃（RenderThread SIGSEGV）根因是**用法错**，不是驱动：
    // 把同一个 backdrop 同时挂在 Scaffold（含 TopAppBar）上又给 TopAppBar 做 textureBlur，
    // 会让 AGSL 采样到「包含自己」的子树，形成反馈并在上屏阶段炸掉。
    // 现在的约束：backdrop 只挂在「内容 LazyColumn」上，TopAppBar 只做 textureBlur、不参与捕获。
    // 见 docs/ui-rework/08-本轮交付-2026-11-03.md §10。
    if (M3_BLUR_ENABLED && enableBlur && isRenderEffectSupported() && isRuntimeShaderSupported()) {
        val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
        return rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    }
    return null
}

/** M3 分支的模糊总开关。见 [rememberMaterial3BlurBackdrop] 的说明与崩溃证据。 */
private const val M3_BLUR_ENABLED = true

/**
 * Determine the app bar background color for Material 3 based on the Backdrop availability.
 *
 * @return Transparent if Backdrop is active, otherwise the surfaceContainer color.
 */
@Composable
fun LayerBackdrop?.getMaterial3AppBarColor(): Color = this?.let { Color.Transparent } ?: MaterialTheme.colorScheme.surfaceContainer

/**
 * Apply a standard glassmorphism blur effect using Material 3 color schemes.
 *
 * @param backdrop The LayerBackdrop providing the visual source.
 * @param enabled Whether the effect is locally enabled for this component.
 * @param blurRadius The radius of the Gaussian blur.
 * @param shape The clipping shape for the blurred area.
 */
@Composable
fun Modifier.verseBoxMaterial3BlurEffect(
    backdrop: LayerBackdrop?,
    enabled: Boolean = true,
    blurRadius: Float = 25f,
    shape: Shape = RectangleShape,
): Modifier {
    // Return early if disabled or backdrop is unavailable
    if (!enabled || backdrop == null) return this

    // Grab the current M3 surfaceContainer color for blending with 80% opacity
    val blendColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f)

    return this.then(
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = blurRadius,
            colors = BlurColors(
                blendColors = listOf(
                    BlendColorEntry(color = blendColor),
                ),
            ),
        ),
    )
}
