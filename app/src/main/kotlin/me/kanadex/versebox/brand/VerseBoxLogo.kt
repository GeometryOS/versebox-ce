// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.versebox.brand

import android.animation.ValueAnimator
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import me.kanadex.versebox.R

// Rust owns the shared animation timeline; Compose draws the mark and accessible brand text.
private const val INTRO_DURATION_MS = 2_200L
private const val MARK_WIDTH_DP = 92
// 下划线的圆头决定 SVG 的可见横向边界
private const val MARK_VISIBLE_INSET_FRACTION = (5.52f + 3.373333f * (4.5f - 1f)) / MARK_WIDTH_DP

private object NativeVerseBoxLogo {
    init {
        System.loadLibrary("versebox_kanadex_logo")
    }

    external fun frame(elapsedMs: Long, reducedMotion: Boolean): Long
    external fun offsets(elapsedMs: Long, reducedMotion: Boolean): Long
}

@Composable
fun VerseBoxLogo(modifier: Modifier = Modifier, nameColor: Color) {
    val reducedMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    var elapsedMs by remember(reducedMotion) { mutableLongStateOf(if (reducedMotion) INTRO_DURATION_MS else 0L) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            elapsedMs = INTRO_DURATION_MS
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        var elapsed = 0L
        while (elapsed < INTRO_DURATION_MS) {
            val now = withFrameNanos { it }
            elapsed = ((now - start) / 1_000_000L).coerceAtMost(INTRO_DURATION_MS)
            elapsedMs = elapsed
        }
    }
    val frame = remember(elapsedMs, reducedMotion) { NativeVerseBoxLogo.frame(elapsedMs, reducedMotion) }
    val offsets = remember(elapsedMs, reducedMotion) { NativeVerseBoxLogo.offsets(elapsedMs, reducedMotion) }
    val accent = colorResource(R.color.versebox_logo_accent)
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val brandFontSize = if (screenWidthDp <= 380) 31.5.sp else 37.5.sp
    Layout(
        modifier = modifier.fillMaxWidth().height(80.dp),
        content = {
            Canvas(Modifier.size(MARK_WIDTH_DP.dp, 64.dp)) {
                drawMark(
                    accent,
                    channel(frame, 1) / 255f,
                    channel(frame, 2) / 255f,
                    channel(frame, 5) / 255f,
                    channel(frame, 6) / 255f,
                    channel(offsets, 0).toFloat(),
                    channel(offsets, 1) - 128f,
                )
            }
            BasicText(
                text = stringResource(R.string.versebox_brand_name),
                style = TextStyle(
                    color = nameColor,
                    fontSize = brandFontSize,
                    fontWeight = FontWeight(650),
                    lineHeight = brandFontSize * 1.15f,
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val mark = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = 4.dp.roundToPx()
        val visibleInset = mark.width * MARK_VISIBLE_INSET_FRACTION
        val name = measurables[1].measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxWidth = (width - mark.width - gap + (2 * visibleInset).roundToInt()).coerceAtLeast(0),
            ),
        )
        val centeredMarkLeft = (width - mark.width) / 2f
        val finalMarkLeft = centeredMarkLeft - (gap + name.width + visibleInset) / 2f
        val markLeft = centeredMarkLeft + (finalMarkLeft - centeredMarkLeft) * (1f - channel(frame, 0) / 124f)
        val nameLeft = markLeft + mark.width + gap + channel(frame, 4).toFloat().dp.toPx()
        layout(width, height) {
            mark.place(markLeft.roundToInt(), (height - mark.height) / 2)
            name.placeWithLayer(nameLeft.roundToInt(), (height - name.height) / 2) {
                alpha = channel(frame, 3) / 255f
            }
        }
    }
}

private fun channel(frame: Long, index: Int): Int = ((frame ushr (index * 8)) and 0xff).toInt()

private fun DrawScope.drawMark(
    accent: Color,
    headAlpha: Float,
    underlineAlpha: Float,
    upperLid: Float,
    lowerLid: Float,
    headX: Float,
    underlineX: Float,
) {
    val sx = size.width / 92f
    val sy = size.height / 64f
    val head = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(RoundRect(Rect(svgX(5f) * sx, svgY(3f) * sy, svgX(19f) * sx, svgY(16f) * sy), CornerRadius(11.8067f * sx, 11.8067f * sy)))
        addRect(Rect(svgX(8.5f) * sx, svgY(7.5f) * sy, svgX(10.5f) * sx, svgY(9.5f) * sy))
        addRect(Rect(svgX(13.5f) * sx, svgY(7.5f) * sy, svgX(15.5f) * sx, svgY(9.5f) * sy))
    }
    translate(left = headX.dp.toPx()) {
        drawPath(head, accent.copy(alpha = headAlpha))
        for (left in listOf(8.2f, 13.2f)) {
            val x = svgX(left) * sx
            val y = svgY(7.2f) * sy
            val width = (svgX(left + 2.6f) - svgX(left)) * sx
            val height = (svgY(9.8f) - svgY(7.2f)) * sy
            if (upperLid > 0f) {
                drawRoundRect(accent, Offset(x, y), Size(width, height * upperLid), CornerRadius(1.3493f * sx * upperLid, 1.3493f * sy * upperLid))
            }
            if (lowerLid > 0f) {
                drawRoundRect(accent, Offset(x, y + height * (1f - lowerLid)), Size(width, height * lowerLid), CornerRadius(1.3493f * sx * lowerLid, 1.3493f * sy * lowerLid))
            }
        }
    }
    translate(left = underlineX.dp.toPx()) {
        drawLine(accent.copy(alpha = underlineAlpha), Offset(svgX(4.5f) * sx, svgY(19.5f) * sy), Offset(svgX(19.5f) * sx, svgY(19.5f) * sy), 6.7467f * sx, StrokeCap.Round)
    }
}

private fun svgX(x: Float): Float = 3.373333f * x + 5.52f
private fun svgY(y: Float): Float = 3.373333f * y - 7.636667f
