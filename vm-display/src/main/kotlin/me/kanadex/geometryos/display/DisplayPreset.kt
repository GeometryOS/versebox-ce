// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.display

/**
 * 分辨率预设，顺序就是列表顺序，不要重排，新增只往后加。
 * 加预设有同步改 DisplayGeometry 的合法范围。
 */
enum class DisplayPreset(val width: Int, val height: Int, val densityDpi: Int) {
    // 最小的可用竖屏，老 ROM 兼容性最好
    W480_H800(480, 800, 240),

    // qHD
    W540_H960(540, 960, 240),

    // 720p
    W720_H1280(720, 1280, 320),

    // 1080p 经典 16:9
    W1080_H1920(1080, 1920, 480);

    val geometry: DisplayGeometry get() = DisplayGeometry(width, height, densityDpi)

    // 持久化标识，形如 480x800@240，不含空格，便于 grep
    val key: String get() = width.toString() + "x" + height + "@" + densityDpi

    companion object {
        fun fromKey(key: String): DisplayPreset? = entries.firstOrNull { it.key == key }
    }
}
