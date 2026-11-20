// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.display

/**
 * 真机自己的显示几何，由 app 的适配层读一次系统 metrics 传进来。
 *
 * 这个模块是纯 JVM，一 import android 就再也不能用单测证明换算是对的。
 * 真机永远是短边和长边，竖屏横屏只是把这两个值换个顺序。
 */
data class HostDisplay(
    val shortSide: Int,
    val longSide: Int,
    val densityDpi: Int,
) {
    // 竖屏，短边做宽
    val portrait: DisplayGeometry
        get() = DisplayGeometry(width = minOf(shortSide, longSide), height = maxOf(shortSide, longSide), densityDpi = densityDpi)

    // 横屏，长边做宽
    val landscape: DisplayGeometry
        get() = DisplayGeometry(width = maxOf(shortSide, longSide), height = minOf(shortSide, longSide), densityDpi = densityDpi)

    val isValid: Boolean get() = portrait.isValid

    companion object {
        // 读不到真实 metrics 时用渲染器的默认值兜底，至少能开机
        val FALLBACK = HostDisplay(
            shortSide = DisplayGeometry.DEFAULT.width,
            longSide = DisplayGeometry.DEFAULT.height,
            densityDpi = DisplayGeometry.DEFAULT.densityDpi,
        )

        fun of(widthPixels: Int, heightPixels: Int, densityDpi: Int): HostDisplay =
            HostDisplay(
                shortSide = minOf(widthPixels, heightPixels),
                longSide = maxOf(widthPixels, heightPixels),
                densityDpi = densityDpi,
            ).takeIf { it.isValid } ?: FALLBACK
    }
}
