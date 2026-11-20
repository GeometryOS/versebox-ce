// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.vm

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.content.Intent
import io.a10vm.runtime.RomSpec
import io.a10vm.runtime.VmDevice
import io.a10vm.runtime.VmService
import io.a10vm.ui.FullscreenActivity

/**
 * 打开一台虚拟机的显示窗口（真正的 Activity，要独占 Surface）。
 *
 * 这一段是从旧实现原样恢复的：MainActivity / ManagementDashboard 时代就是
 * 先 startActivity(FullscreenActivity) 再发 VmService.START。
 * 顺序不能反 —— 只发 START 不开窗口，用户看到的就是「点了没反应 / 停在正在准备」。
 */
/**
 * 把这个虚拟机的窗口任务从最近任务里**彻底移除**。
 *
 * 只在"用户停了它"之后调用：那时窗口已经没有任何内容，留着卡片等于留一个幽灵。
 * 与 `openDesktop` 的任务查找同一套锚点（component + data），找不到就当已移除。
 */
fun removeTask(context: Context, device: VmDevice) {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val component = android.content.ComponentName(context, FullscreenActivity::class.java)
    manager.appTasks
        .filter { task ->
            val base = task.taskInfo?.baseIntent ?: return@filter false
            base.component == component && base.data?.lastPathSegment == device.id
        }
        .forEach { runCatching { it.finishAndRemoveTask() } }
}

fun openDesktop(context: Context, device: VmDevice, rom: RomSpec?) {
    val identity = Uri.Builder().scheme("versebox").authority("vm").appendPath(device.id).build()
    val component = android.content.ComponentName(context, FullscreenActivity::class.java)
    val tasks = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks
    val existing = tasks.firstOrNull { task ->
        val base = task.taskInfo?.baseIntent
        base?.component == component && base.data == identity
    }
    if (existing != null && runCatching { existing.moveToFront() }.isSuccess) return
    context.startActivity(
        Intent(context, FullscreenActivity::class.java).apply {
            data = identity
            putExtra(VmService.EXTRA_INSTANCE, device.id)
            putExtra("name", device.name)
            putExtra("romId", rom?.id)
            putExtra("romName", rom?.name)
            putExtra("androidVersion", rom?.androidVersion)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        },
    )
}
