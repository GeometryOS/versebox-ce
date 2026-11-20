// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.processlimit

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import io.a10vm.runtime.limits.ChildProcessLimit
import io.a10vm.runtime.limits.ChildProcessLimitProbe
import io.a10vm.runtime.limits.ProbePhase

/**
 * 解除进程限制的**唯一内容来源**。
 *
 * Miuix 分支与 Material 3 分支的页面只负责画：文案、档位、检测逻辑全部读这里，
 * 不会再出现"两套页面两份文案、改一处忘一处"（以前 Miuix 与 M3 各存了一份 enum，
 * 步骤条数和措辞都不一样）。
 */
object ProcessLimitSupport {

    /** VMOS 助手（Android 12/13 必须用它跑一遍无线调试配对）。 */
    const val VMOS_HELPER = "https://1815286808.mshare.123pan.cn/123pan/nv7tVv-97cKh"

    /** 鸿蒙 / 华为机型的 ADB 解除流程。 */
    const val HARMONY_GUIDE = "https://s.shanchen.com/help/29.html"

    /**
     * 状态卡标题。
     *
     * "正在检测"**只在还没有结论的时候**（[ProbePhase.pending]）出现，最多 3 秒；
     * 结论一出来就换成实测结果，后台的持续观察由 [statusDetail] 说明。
     */
    fun statusTitle(limit: ChildProcessLimit, phase: ProbePhase): String = when {
        phase.pending -> "正在检测"
        limit is ChildProcessLimit.NotNeeded -> "子进程限制已解除"
        limit is ChildProcessLimit.Relaxed -> "目前未观察到"
        limit is ChildProcessLimit.Limited -> "子进程受限"
        else -> "尚未检测"
    }

    /** 状态卡副标题：只写这次实测看到的事实，以及还在后台做什么。 */
    fun statusDetail(
        limit: ChildProcessLimit,
        phase: ProbePhase,
        forked: Int,
    ): String = when {
        phase == ProbePhase.Forking -> "正在启动子进程 $forked/${ChildProcessLimitProbe.FORK_COUNT}…"
        phase == ProbePhase.Settling -> "${ChildProcessLimitProbe.FORK_COUNT} 个子进程已就绪，正在确认系统是否回收…"

        limit is ChildProcessLimit.NotNeeded -> "Android 11 及以下没有 phantom process 上限。"
        limit is ChildProcessLimit.Relaxed -> limit.detail + "。后台仍在观察，若系统随后清理会立刻改判。"
        limit is ChildProcessLimit.Limited -> limit.detail + "。按下面的方案解除后重新检测。"
        limit is ChildProcessLimit.Unknown -> limit.detail
        else -> ""
    }

    /** 状态卡的操作文字：它同时是"现在能不能点"的唯一说明。 */
    fun statusAction(phase: ProbePhase): String = when {
        phase.pending -> "正在检测…"
        else -> "点这里重新检测"
    }

    /** 实测方法：一句话说明判据，避免用户以为在"读开关"。 */
    const val WHY = "判定方式：启动 " + ChildProcessLimitProbe.FORK_COUNT +
        " 个只休眠的子进程（phantom process），若被系统清理则说明上限生效（AOSP 默认 " +
        ChildProcessLimitProbe.SYSTEM_CAP + "）。"

    /** 限制生效时的后果，一句话。 */
    const val CONSEQUENCE = "上限生效时，客体系统的大量进程会被按超限逐个清理，表现为卡死或黑屏。"

    /** 顶部提示卡文案。 */
    const val TIP = "解除限制只影响本应用的子进程配额，可随时还原。"

    /**
     * 判定为「系统在限制子进程」时那张对话框的文案 —— 两套 UI 分支共用这一份。
     *
     * 弹窗时机（可置信的受限证据、抑制位、真实解除）全部由 StoredLimit.shouldAlert 决定，
     * 这里只有文字，页面不做任何判断。
     */
    const val ALERT_TITLE = "系统在限制子进程"

    const val ALERT_BODY = "系统会按上限回收本应用的子进程，虚拟机可能黑屏或卡死。"

    /** 正文里的蓝色链接（唯一入口，按钮区不再放重复的「去解决」）。 */
    const val ALERT_LINK = "查看解除方法"

    /** 抑制位：点它之后，直到限制真正解除又重新生效前都不再提示。 */
    const val ALERT_MUTE = "不再提示"

    /** 列表末尾的脚注。 */
    const val FOOTNOTE = "检测结果只反映本次实测；改过系统设置后请重新检测。"

    /** 主页/设置页那张状态卡用的简短结论。 */
    fun homeTitle(limit: ChildProcessLimit): String = when (limit) {
        ChildProcessLimit.NotNeeded -> "系统未限制子进程"
        is ChildProcessLimit.Relaxed -> "未见子进程限制"
        is ChildProcessLimit.Limited -> "子进程限制未解除"
        is ChildProcessLimit.Unknown -> "子进程限制未检测"
    }

    /** 主页状态卡的说明与操作文字。 */
    fun homeDescription(limit: ChildProcessLimit): String = when (limit) {
        ChildProcessLimit.NotNeeded -> "Android 11 及以下没有 phantom process 上限。"
        is ChildProcessLimit.Relaxed -> "实测 ${
            ChildProcessLimitProbe.FORK_COUNT
        } 个子进程全部存活，超过 ${ChildProcessLimitProbe.SYSTEM_CAP} 的上限也没有被清理。"

        is ChildProcessLimit.Limited -> "目前的设备运行虚拟机会异常退出，因为Android 12 起每个应用最多32个子进程，一个完整的虚拟机系统远超这个数量"
        is ChildProcessLimit.Unknown -> limit.detail
    }

    fun homeAction(limit: ChildProcessLimit): String = when (limit) {
        ChildProcessLimit.NotNeeded, is ChildProcessLimit.Relaxed -> "查看详情"
        is ChildProcessLimit.Limited -> "查看解除方案"
        is ChildProcessLimit.Unknown -> "去检测"
    }

    /** Android 11 及以下没有这个限制。 */
    fun notNeeded(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    /** 打开系统「开发者选项」；部分 ROM 上不存在该入口，失败就静默。 */
    fun openDeveloperOptions(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 一个分机型方案。 */
    data class Plan(
        val title: String,
        val badge: String,
        val summary: String,
        val steps: List<String>,
        val linkLabel: String?,
        val link: String?,
        val developerOptions: Boolean = false,
    )

    /** 三档方案。 */
    fun plans(): List<Plan> = listOf(
        Plan(
            title = "Android 14 及以上",
            badge = "推荐",
            summary = "开发者选项里的「停止限制子进程」，打开即可，重启后依然生效",
            steps = listOf(
                "设置 → 关于本机 → 连续点「版本号」7 次，进入开发者模式",
                "开发者选项里找到「停止限制子进程」并打开",
                "如果是 ColorOS 系，再打开「停止权限监控」",
                "回到这里点「重新检查」，应显示已解除",
            ),
            linkLabel = "直接打开开发者选项",
            link = null,
            developerOptions = true,
        ),
        Plan(
            title = "Android 12 / 13",
            badge = "需要助手",
            summary = "这一档没有系统开关，必须用 VMOS 助手借无线调试把限制关掉",
            steps = listOf(
                "下载 VMOS 助手（下面的外链）并给它所有权限",
                "手机开发者选项里打开「无线调试」",
                "用配对码把助手和手机配对",
                "在助手里执行一次解除；不要用别的应用内置的「激活虚拟机」",
                "回来点「重新检查」",
            ),
            linkLabel = "下载 VMOS 助手",
            link = VMOS_HELPER,
        ),
        Plan(
            title = "华为 / 部分荣耀（鸿蒙 3+）",
            badge = "需要 ADB",
            summary = "这类机型要靠电脑执行一条 ADB 命令解除，助手帮不上",
            steps = listOf(
                "电脑装好 platform-tools，手机打开 USB 调试并授权",
                "按外链里的步骤执行对应的 adb 命令",
                "鸿蒙 4.2 及以上先确认「开发者选项」已解锁",
                "回来点「重新检查」",
            ),
            linkLabel = "打开鸿蒙解除教程",
            link = HARMONY_GUIDE,
        ),
    )
}
