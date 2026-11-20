// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.a10vm.runtime.VmDevice
import io.a10vm.ui.MainActivity

/**
 * 第一屏要发起的三个跨页面动作（逻辑层，不在 composable 里拼 Intent）。
 *
 * 三个动作的目标都是**已存在的** MainActivity（singleTask + CLEAR_TOP），
 * 所以从启动窗口回到主页不会新建一个主页，也不会把虚拟机窗口关掉。
 */
object BootLauncher {
    /** 「查看运行记录」：回家主页并请求切到记录 tab + 定位到某条记录（`09/05` §2）。 */
    fun openRecord(context: Context, vmId: String, recordId: Long?) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(MainActivity.EXTRA_FOCUS_RECORD_VM, vmId)
                recordId?.let { putExtra(MainActivity.EXTRA_FOCUS_RECORD, it) }
            },
        )
    }

    /**
     * C3 的「去修复」：进 ROM 管理并请求「定位到这一行 + 自动重解」，
     * 修完由 ROM 列表通过 [BootRestartBus] 让这台虚拟机重新启动（`09/04` §2）。
     */
    fun openRomRepair(context: Context, romKey: String, vmId: String, rowId: String? = null) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(MainActivity.EXTRA_FOCUS_ROM, romKey)
                putExtra(MainActivity.EXTRA_FOCUS_ROM_ROW, rowId)
                putExtra(MainActivity.EXTRA_ROM_REPAIR_VM, vmId)
            },
        )
    }

    /** C2 的「导入这个 ROM」：进 ROM 管理（导入入口就在那个页面的顶栏）。 */
    fun openRomList(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(MainActivity.EXTRA_OPEN_ROM_LIST, true)
            },
        )
    }

    /**
     * 启动页的「去版本管理」：非破坏性跳转。
     *
     * 落点是「虚拟机管理页 → ROM 版本管理页」（主壳负责先 push 管理页），
     * 所以用户从版本页按返回回到的是这台虚拟机的管理页，而不是主页。
     */
    fun openRomVersion(context: Context, vmId: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(MainActivity.EXTRA_ROM_VERSION, vmId)
            },
        )
    }

    /** C1 的「返回首页」。 */
    fun openHome(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
        )
    }

    /**
     * 把这台虚拟机的窗口任务提到前台（不新建一个窗口）。
     *
     * @return false = 任务已经被系统回收，调用方应改用 openDesktop 重新拉起
     */
    fun bringVmToFront(context: Context, device: VmDevice): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val component = ComponentName(context, io.a10vm.ui.FullscreenActivity::class.java)
        val task = manager.appTasks.firstOrNull { candidate ->
            val base = candidate.taskInfo?.baseIntent ?: return@firstOrNull false
            base.component == component && base.data?.lastPathSegment == device.id
        } ?: return false
        return runCatching { task.moveToFront() }.isSuccess
    }
}