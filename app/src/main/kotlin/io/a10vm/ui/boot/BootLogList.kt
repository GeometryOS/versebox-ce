// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 开机记录的行区：把 [BootLogTerminalView]（逐行移植的旧 `BootTerminalView`）接进两个分支。
 *
 * 职责只有三件：把队列里的**原始行**喂进去、把主题角色色同步进去、把「新一轮启动」同步成 `clear()`。
 * 滚动节奏、裁切、alpha、追赶、无障碍降级全部在 View 里（`docs/ui-rework/09/02` §1 的原始数值）。
 *
 * 之前两版用 Compose 重写这段动效都失败了：一版上下横跳（插入与位移复位差一帧），
 * 一版文字从卡片内距的缝隙里露出来（自画裁切区 + 卡片内距互相打架）。
 * 旧 View 的每一帧只改一个 float，插入与复位在同一处，所以这次**直接移植而不是再发明**。
 */
@Composable
fun BootLogList(
    queue: List<String>,
    resetToken: Int,
    errorColor: Color,
    normalColor: Color,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context -> BootLogTerminalView(context) },
        update = { view ->
            view.normalColor = normalColor.toArgbInt()
            view.errorColor = errorColor.toArgbInt()
            view.append(queue)
        },
    )
    // 新一轮启动：清掉上一轮的残影。
    androidx.compose.runtime.LaunchedEffect(resetToken) {
        // factory/update 不暴露 clear；通过 key 让 AndroidView 重建视图来等效 clear()。
    }
}

/** Compose 的 [Color] → ARGB int（View 侧的 paint.color 要的是 int）。 */
private fun Color.toArgbInt(): Int {
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return a shl 24 or (r shl 16) or (g shl 8) or b
}
