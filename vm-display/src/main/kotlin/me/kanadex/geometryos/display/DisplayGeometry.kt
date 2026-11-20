// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.display

/**
 * 一台虚拟机的显示几何，横向像素、纵向像素和每英寸像素密度。
 *
 * 这三个整数是宿主渲染器和客体之间唯一的显示契约。渲染器按宽高建离屏帧缓冲并上报，
 * 客体按 DPI 算 dp；只改一边，画面会被拉伸或者字体大小不对。
 *
 * 这里不抛异常，历史数据也要能读进来，合法性由 isValid 判断。
 */
data class DisplayGeometry(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
) {
    // 大标题，用户要求用大写的 X
    val title: String get() = width.toString() + " X " + height

    // 小标题里的密度片段
    val densityLabel: String get() = "DPI " + densityDpi

    // 管理页那一行的小标题
    val summary: String get() = title + " " + densityLabel

    val isValid: Boolean
        get() = width in MIN_SIZE..MAX_SIZE &&
            height in MIN_SIZE..MAX_SIZE &&
            densityDpi in MIN_DPI..MAX_DPI

    companion object {
        // 宽高下限。比 320x480 更小的系统没有可用性，也进不了预设
        const val MIN_SIZE = 320
        const val MAX_SIZE = 7680

        // DPI 范围比 ldpi 到 xxxhdpi 再放宽一档，防止有人把 dp 当 dpi 填
        const val MIN_DPI = 120
        const val MAX_DPI = 960

        // 渲染器编译期的默认几何，只在配置缺失或非法时兜底
        val DEFAULT = DisplayGeometry(width = 1080, height = 2160, densityDpi = 480)

        fun of(width: Int, height: Int, densityDpi: Int): DisplayGeometry? =
            DisplayGeometry(width, height, densityDpi).takeIf { it.isValid }
    }
}
