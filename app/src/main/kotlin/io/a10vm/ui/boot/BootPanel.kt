// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 两个 UI 分支**共用**的三块视觉（`docs/ui-rework/09/01` §3「轨道动画双分支共用同一个 composable」，
 * `09/02` §4「双分支行高、节奏、alpha 曲线完全一致」）。
 *
 * 这里只用 foundation 的排版原语（Box / Column / Canvas / graphicsLayer），
 * 不引入任何一方的 Text、Card —— 那些由各自的页面按本分支组件库渲染。
 */

/** 轨道动画直径：`09/02` §5 规定固定 128dp，不随屏幕缩放。 */
val BootOrbitSize: Dp = 128.dp

/**
 * 卡片内容内距。与 `MiuixVmCard` 的卡片内距、miuix `BasicComponent` 的原生内距同为 16dp，
 * 所以第一屏的卡片文字与全应用其它卡片文字落在同一条竖线上（`03` §1）。
 */
val BootCardPadding: Dp = 16.dp

/**
 * 品牌字标的字体：Archivo Black（SIL Open Font License 1.1，来源与授权见
 * `docs/ui-rework/09-启动页与运行观测/08-品牌字标与授权.md`）。
 */
val BootWordmarkFont: FontFamily = FontFamily(Font(me.kanadex.versebox.R.font.archivo_black))


/**
 * 轨道动画：两圈环 + 一段 80° 圆弧 + 两颗卫星点，4.2 秒一圈。
 *
 * 几何与节奏**逐项照旧**（`09/02` §5）：颜色改成由调用方从主题角色色传入，
 * 这样深色/浅色与换色都能跟着走，而不再是页面里的十六进制常量。
 */
@Composable
fun BootOrbit(
    ringColor: Color,
    arcColor: Color,
    satelliteColor: Color,
    /**
     * logo 的颜色。
     *
     * 传进来而不是写死：字形资源是**单色**的（`drawable/ic_tile_icon`，与桌面图标的前景层同一个字形），
     * 品牌色由 `@color/ic_launcher_glyph` 提供——这样"应用图标"与"开机第一屏的 logo"
     * 永远是同一个字形、同一个颜色，不会各自漂移（2026-11-15：第一屏曾经指着旧的
     * `drawable/ic_launcher`，那是 A10VM 时代的暗底方块图，和桌面图标已经不是同一个东西了）。
     */
    logoColor: Color,
    logo: Painter,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "boot-orbit")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "boot-orbit-angle",
    )

    Box(modifier = modifier.size(BootOrbitSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.width * 0.37f
            drawCircle(ringColor, radius, Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
            drawCircle(ringColor, radius * 0.8f, Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
            rotate(angle, Offset(cx, cy)) {
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 80f,
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
                drawCircle(arcColor, 4.dp.toPx(), Offset(cx, cy - radius))
                drawCircle(satelliteColor, 3.dp.toPx(), Offset(cx, cy + radius))
            }
        }
        Image(
            painter = logo,
            contentDescription = null,
            colorFilter = ColorFilter.tint(logoColor),
            modifier = Modifier.size(50.dp),
        )
    }
}