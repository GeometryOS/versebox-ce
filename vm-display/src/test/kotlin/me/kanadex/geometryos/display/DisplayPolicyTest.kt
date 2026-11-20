// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显示设置的纯策略单测：不需要设备、不需要 Robolectric。
 *
 * 这三条就是 docs/display-settings/03 里“可判定的判据”：
 * 1. 四个预设的文案与顺序；
 * 2. 真机分辨率**跟着宿主走**（换个宿主值，输出就变），不是硬编码；
 * 3. 只有会话在跑（运行/开机中/关机中）才需要重启。
 */
class DisplayPolicyTest {

    private val host = HostDisplay.of(widthPixels = 1080, heightPixels = 2340, densityDpi = 480)

    @Test
    fun presetsKeepDeclaredOrderAndLabels() {
        val options = buildDisplayOptions(VmDisplayMode.Default, host)
        assertEquals(6, options.size)
        assertEquals(listOf("480 X 800", "540 X 960", "720 X 1280", "1080 X 1920"),
            options.take(4).map { it.title })
        assertEquals(listOf("DPI 240", "DPI 240", "DPI 320", "DPI 480"),
            options.take(4).map { it.summary })
        assertEquals(VmDisplayTexts.NATIVE_PORTRAIT, options[4].title)
        assertEquals(VmDisplayTexts.NATIVE_LANDSCAPE, options[5].title)
        assertTrue(options[4].selected)
        assertFalse(options[5].selected)
    }

    @Test
    fun nativeResolutionsFollowTheHost() {
        assertEquals("1080 X 2340 DPI 480", VmDisplayMode.NativePortrait.summary(host))
        val landscape = VmDisplayMode.NativeLandscape.summary(host)
        assertEquals("2340 X 1080 DPI 480", landscape)
        // 换一台宿主：同一份“真机竖屏”设置必须给出新值，说明它没有被硬编码。
        val other = HostDisplay.of(widthPixels = 1440, heightPixels = 3168, densityDpi = 560)
        assertEquals("1440 X 3168 DPI 560", VmDisplayMode.NativePortrait.summary(other))
    }

    @Test
    fun tokensRoundTripAndUnknownFallsBack() {
        for (mode in VmDisplayMode.ordered) {
            assertEquals(mode.token, VmDisplayMode.parse(mode.token).token)
        }
        assertEquals(VmDisplayMode.Default.token, VmDisplayMode.parse("").token)
        assertEquals(VmDisplayMode.Default.token, VmDisplayMode.parse("garbage").token)
        assertEquals("preset:720x1280@320", VmDisplayMode.parse("preset:720x1280@320").token)
    }

    @Test
    fun restartOnlyNeededWhileTheSessionIsAlive() {
        assertFalse(DisplayChangePolicy.requiresRestart(running = false, busy = false, stopping = false))
        assertTrue(DisplayChangePolicy.requiresRestart(running = true, busy = false, stopping = false))
        assertTrue(DisplayChangePolicy.requiresRestart(running = false, busy = true, stopping = false))
        assertTrue(DisplayChangePolicy.requiresRestart(running = true, busy = false, stopping = true))
    }
}
