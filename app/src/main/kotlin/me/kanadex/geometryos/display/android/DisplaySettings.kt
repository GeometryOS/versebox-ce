// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.display.android

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import io.a10vm.runtime.VmDevice
import me.kanadex.geometryos.display.DisplayGeometry
import me.kanadex.geometryos.display.HostDisplay
import me.kanadex.geometryos.display.VmDisplayMode

/**
 * 显示设置的**适配层**：把 Android 的事实（真机几何）与库里的标识（每台虚拟机的模式）
 * 拼成 :vm-display 需要的两个输入。
 *
 * 分层（`docs/display-settings/03` §2）：
 * - 判定与文案在 `:vm-display`（纯 JVM，可单测）；
 * - 事实（本机像素/DPI、库里存了什么）在这里读；
 * - 页面只拿 [DisplayGeometry] 与 `VmDisplayOption`，不认识 DisplayMetrics。
 */
object DisplaySettings {

    /**
     * 宿主真实几何。
     *
     * 「真机分辨率」必须是**读出来的**：`maximumWindowMetrics` 给的是整块屏幕
     * （含状态栏/导航栏），`resources.displayMetrics` 只给当前窗口；
     * 分辨率设置要的是前者。API 30 以下退回 `getRealMetrics`。
     */
    fun hostDisplay(context: Context): HostDisplay {
        val densityDpi = context.resources.displayMetrics.densityDpi
        val windowManager = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return HostDisplay.of(bounds.width(), bounds.height(), densityDpi)
            }
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return HostDisplay.of(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
        return HostDisplay.FALLBACK
    }

    /** 库里存的模式（空串 = 真机分辨率竖屏，见 [VmDisplayMode.parse]）。 */
    fun modeOf(device: VmDevice): VmDisplayMode = VmDisplayMode.parse(device.displayMode)

    /** 管理页那一行的小标题：`1080 X 2340 DPI 480`。 */
    fun summaryOf(context: Context, device: VmDevice): String =
        modeOf(device).resolve(hostDisplay(context)).summary

    /** 这台虚拟机**本次运行**要用的几何。渲染器与客体属性都从这里取值。 */
    fun geometryOf(context: Context, device: VmDevice): DisplayGeometry {
        val geometry = modeOf(device).resolve(hostDisplay(context))
        // 库被手改/历史垃圾值时 resolve 仍可能给出非法值：渲染器那边还有一道 sanitize，
        // 这里先回默认值，保证 `NativeWindow` 拿到的一定是合法几何。
        return if (geometry.isValid) geometry else DisplayGeometry.DEFAULT
    }
}
