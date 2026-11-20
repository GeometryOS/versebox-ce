// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.display

/**
 * 一台虚拟机选中的显示模式，只有两类。
 *
 * 真机分辨率不写死像素，每次解析取宿主的真实 metrics，库里只存我是真机竖屏这件事；
 * 预设的几何是常量，库里存 preset:480x800@240。持久化的字符串只有这个文件认识，
 * 不认识的值一律回落到默认值，历史数据和手改过的库不能让页面崩。
 */
sealed interface VmDisplayMode {

    // 库里存的那串标识
    val token: String

    // 按当前宿主几何算出要下发给渲染器和客体的三个整数
    fun resolve(host: HostDisplay): DisplayGeometry

    // 列表大标题
    fun title(host: HostDisplay): String

    // 列表小标题
    fun summary(host: HostDisplay): String

    // 真机分辨率，竖屏
    data object NativePortrait : VmDisplayMode {
        override val token: String get() = "native:portrait"
        override fun resolve(host: HostDisplay): DisplayGeometry = host.portrait
        override fun title(host: HostDisplay): String = VmDisplayTexts.NATIVE_PORTRAIT
        override fun summary(host: HostDisplay): String = host.portrait.summary
    }

    // 真机分辨率，横屏
    data object NativeLandscape : VmDisplayMode {
        override val token: String get() = "native:landscape"
        override fun resolve(host: HostDisplay): DisplayGeometry = host.landscape
        override fun title(host: HostDisplay): String = VmDisplayTexts.NATIVE_LANDSCAPE
        override fun summary(host: HostDisplay): String = host.landscape.summary
    }

    // 固定预设
    data class Preset(val preset: DisplayPreset) : VmDisplayMode {
        override val token: String get() = "preset:" + preset.key
        override fun resolve(host: HostDisplay): DisplayGeometry = preset.geometry
        override fun title(host: HostDisplay): String = preset.geometry.title
        override fun summary(host: HostDisplay): String = preset.geometry.densityLabel
    }

    companion object {
        // 新虚拟机和老数据的默认值是真机竖屏。库里存空串就等于它，升库不用回填
        val Default: VmDisplayMode = NativePortrait

        // 列表顺序，四个预设在前，真机竖屏和真机横屏在后
        val ordered: List<VmDisplayMode> =
            DisplayPreset.entries.map { Preset(it) } + listOf(NativePortrait, NativeLandscape)

        fun parse(token: String?): VmDisplayMode {
            val value = token?.trim().orEmpty()
            if (value.isEmpty()) return Default
            if (value == NativePortrait.token) return NativePortrait
            if (value == NativeLandscape.token) return NativeLandscape
            if (value.startsWith("preset:")) {
                DisplayPreset.fromKey(value.removePrefix("preset:"))?.let { return Preset(it) }
            }
            return Default
        }
    }
}

// 列表里的一行，页面只渲染，不自己拼字符串
data class VmDisplayOption(
    val mode: VmDisplayMode,
    val title: String,
    val summary: String,
    val selected: Boolean,
)

// 把当前选中和宿主几何翻译成整页的行，两套 UI 共用，页面里不会出现第二套拼法
fun buildDisplayOptions(selected: VmDisplayMode, host: HostDisplay): List<VmDisplayOption> =
    VmDisplayMode.ordered.map { mode ->
        VmDisplayOption(
            mode = mode,
            title = mode.title(host),
            summary = mode.summary(host),
            selected = mode.token == selected.token,
        )
    }

/**
 * 这次改动什么时候生效。会话在跑、正在开机、正在关机都要重启，干净空闲不用。
 * 放在这里是因为一个 if 写两遍，两个分支迟早对开机中算不算运行给出不同答案。
 */
object DisplayChangePolicy {
    fun requiresRestart(running: Boolean, busy: Boolean, stopping: Boolean): Boolean =
        running || busy || stopping
}
