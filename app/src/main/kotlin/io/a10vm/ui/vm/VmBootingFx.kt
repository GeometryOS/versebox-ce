// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.vm

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

/**
 * 启动中的虚拟机卡片动效：小幅平移 + 轻微透明度呼吸（“羽化”）。
 *
 * 只在 [active]（BUSY）时才组合无限动画；空闲分支直接返回原 Modifier，
 * 不创建任何动画对象，对滚动和功耗零影响。位移用 graphicsLayer，
 * 不触发重排/重绘子树。
 */
@Composable
fun Modifier.vmBootingFx(active: Boolean): Modifier {
    if (!active) return this
    val density = LocalDensity.current
    val amplitudePx = with(density) { 4.dp.toPx() }
    val transition = rememberInfiniteTransition(label = "vmBooting")
    val phase by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "vmBootingPhase",
    )
    return graphicsLayer {
        translationX = phase * amplitudePx
        alpha = 0.86f + 0.14f * (phase + 1f) / 2f
    }
}
