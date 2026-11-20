// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

import android.content.Context
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.LinearInterpolator
import io.a10vm.ui.BootLogText
import java.util.ArrayDeque

/**
 * 开机记录的滚动条目视图 —— **逐行移植** `_bin/old-ui-src/.../BootTerminalView.kt` 的绘制与滚动部分，
 * 只做两处替换：
 *
 * 1. 旧版自己画的面板底色 / 1px 描边 / 「● 实时开机记录」标题 / 分隔线**删掉**，
 *    这些外壳由两个分支用组件库的 Card + Row + HorizontalDivider 画（`09/01` §3）；
 * 2. 文字颜色不再写死绿色 / 珊瑚色，改由调用方从主题角色色传入（`09/01` §7 第 1 条）。
 *
 * **滚动、队列、裁切、alpha、追赶节奏一律照抄旧实现，不改数值、不改顺序**：
 *
 * | 项 | 旧值 |
 * | --- | --- |
 * | 待显示队列 | pending ≤ 48，满了丢最旧 |
 * | 已显示行 | rows ≤ 64 |
 * | 行高 | `paint.fontSpacing * 1.4`（位移的单位就是它） |
 * | 单步动画 | `ValueAnimator.ofFloat(lineHeight, 0)`，Linear，>16 行积压用 90 ms 否则 160 ms |
 * | 步间停顿 | 动画结束 `postDelayed(next, 45)` |
 * | 可见窗口 | `capacity = (bottom - top) / lineHeight`，画 `rows.takeLast(capacity + 1)` |
 * | 行的 y | `bottom - (visible.size - index - 1) * lineHeight + offset` |
 * | alpha | `100 + 155 * (index + 1) / visible.size`（旧 → 淡，新 → 亮） |
 * | 无障碍 | `areAnimatorsEnabled() == false` → 直接 `flush()`，内容完整 |
 * | 重置 | `clear()` 清空 pending / rows，不留上一轮残影 |
 *
 * 为什么用 View 而不是 Compose：这段动效的**每一帧只改一个 float 再 invalidate**，
 * 插入新行与把位移复位到一行高发生在同一次 `advance()` 里 —— Compose 里把这两件事拆到
 * 「状态更新」与「重组后的 LaunchedEffect」就必然差一帧，表现为上下横跳；
 * 文字画在 `clipRect` 之外的那一段还会从卡片内距的缝隙里露出来。与其在 Compose 里重新发明一遍，
 * 不如把已经验证过的绘制代码原样接进来（`09/02` §1：旧实现是唯一依据）。
 */
internal class BootLogTerminalView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = 10.5f * resources.displayMetrics.scaledDensity
    }
    private val rows = ArrayDeque<String>()
    private val pending = ArrayDeque<String>()
    private val lineHeight = paint.fontSpacing * 1.4f
    private var offset = 0f
    private var active = false
    private var scroll: ValueAnimator? = null
    private val next = Runnable { advance() }

    /** 主题角色色：正常行 / 错误行。由 Compose 侧在每次重组时同步进来。 */
    var normalColor: Int = Color.rgb(112, 240, 158)
        set(value) { field = value; invalidate() }
    var errorColor: Int = Color.rgb(255, 154, 128)
        set(value) { field = value; invalidate() }

    init {
        contentDescription = "开机记录，自动向上滚动"
    }

    fun clear() {
        stop()
        rows.clear()
        pending.clear()
        invalidate()
    }

    fun append(lines: List<String>) {
        lines.forEach { if (pending.size == 48) pending.removeFirst(); pending.addLast(it) }
        if (!active || !ValueAnimator.areAnimatorsEnabled()) flush() else advance()
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value || !ValueAnimator.areAnimatorsEnabled()) { stop(); flush() } else advance()
    }

    private fun addRow(line: String) {
        if (rows.size == 64) rows.removeFirst()
        rows.addLast(line)
    }

    private fun advance() {
        if (!active || scroll != null || pending.isEmpty()) return
        if (!ValueAnimator.areAnimatorsEnabled()) { flush(); return }
        removeCallbacks(next)
        addRow(pending.removeFirst())
        scroll = ValueAnimator.ofFloat(lineHeight, 0f).apply {
            duration = if (pending.size > 16) 90 else 160
            interpolator = LinearInterpolator()
            addUpdateListener { offset = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    scroll = null
                    offset = 0f
                    if (active) postDelayed(next, 45)
                }
            })
            start()
        }
    }

    private fun stop() {
        scroll?.removeAllListeners()
        scroll?.cancel()
        scroll = null
        offset = 0f
        removeCallbacks(next)
    }

    private fun flush() {
        stop()
        while (pending.isNotEmpty()) addRow(pending.removeFirst())
        invalidate()
    }

    override fun onDetachedFromWindow() {
        setActive(false)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 外壳（底色 / 描边 / 标题 / 分隔线）在 Compose 侧画，这里只画行：
        // 文本区从 0 到 height，横向内距由 Card 的 insideMargin 承担，所以不再有
        // 「文字从透明缝隙里露出来」的裁切问题。
        val bottom = height - paint.descent()
        val capacity = (bottom / lineHeight).toInt().coerceAtLeast(1)
        val visible = rows.toList().takeLast(capacity + 1)
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        visible.forEachIndexed { index, line ->
            paint.color = if ("ERROR" in line) errorColor else normalColor
            paint.alpha = (100 + 155 * (index + 1) / visible.size).coerceAtMost(255)
            val text = TextUtils.ellipsize(
                BootLogText.format(line),
                paint,
                width.toFloat().coerceAtLeast(1f),
                TextUtils.TruncateAt.END,
            )
            canvas.drawText(text.toString(), 0f, bottom - (visible.size - index - 1) * lineHeight + offset, paint)
        }
        paint.alpha = 255
        canvas.restore()
    }
}
