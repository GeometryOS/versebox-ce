// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

import me.kanadex.geometryos.rom.RomTone

/**
 * 第一屏与它两个对话框的**全部人话文案**。
 *
 * 为什么文案在这里而不是页面里（`docs/ui-rework/09/01` §4、`00` §3 第 3 条）：
 * 页面一旦开始拼中文句子，两个 UI 分支就会各拼一份，改一处漏一处。
 * 这里的每个函数都是纯函数，没有 Compose、没有 Context。
 */
object BootTexts {
    /** 还没有任何日志时的默认提示（不是「正在等待第一条运行记录」那种用启动页遮挡的说法）。 */
    const val STAGE_IDLE = "正在准备启动…"

    /** 正在关机：由用户主动发起，所以第一屏重新出现并说明在干什么。 */
    const val STAGE_STOPPING = "正在关机…"

    /** 主提示：启动 / 关机两种前缀都在逻辑层拼好，页面不拼中文（09/01 §4）。 */
    fun headline(vmName: String, stopping: Boolean): String =
        (if (stopping) "正在关机 " else "正在启动 ") + vmName

    /** 日志面板标题行左侧的说明。 */
    const val PANEL_TITLE = "实时开机记录"

    /** 「查看运行记录」链接（低频动作，权重必须低于主流程）。 */
    const val LINK_OPEN_RECORD = "查看运行记录 ›"

    /** 弹窗里的链接文本：走同一条定位链路，不是直接进详情。 */
    const val LINK_VIEW_RECORD = "查看这次运行的记录 ›"

    /** 分享快照时的主题行。 */
    const val SNAPSHOT_SUBJECT = "VerseBox 运行日志快照"

    /** 运行时没给原因时的兜底一句话（B 类与 C4 共用）。 */
    const val FAILURE_UNKNOWN = "运行环境意外退出"

    const val ACTION_DISMISS = "忽略"
    const val ACTION_SHARE = "分享日志快照"
    const val ACTION_REPAIR = "去修复"
    const val ACTION_IMPORT_ROM = "导入这个 ROM"
    const val ACTION_BACK_HOME = "返回首页"
    const val ACTION_RECHECK = "再查一次"

    /** 管理胶囊的无障碍文案（外观保持现状，只恢复拖动，见 `09/01` §6）。 */
    const val MANAGE = "回到管理页"

    /**
     * 「已等待 N 秒」的语义档位：<15 s 低强调、15–45 s 警示、>45 s 错误色（`09/01` §4）。
     *
     * 阈值写在这里 = 唯一来源；页面不许自己写 15 / 45。
     */
    fun waitTone(seconds: Int): RomTone = when {
        seconds > 45 -> RomTone.Error
        seconds >= 15 -> RomTone.Warning
        else -> RomTone.Neutral
    }

    fun waitText(seconds: Int): String = "已等待 " + seconds + " 秒"

    fun stageOrIdle(stage: String): String = stage.ifBlank { STAGE_IDLE }

    /** 日志面板标题行右侧：与状态胶囊同一份秒数，避免两处数字不一致。 */
    fun panelMeta(seconds: Int): String = waitText(seconds)

    // ------------------------------------------------------------------ C 类对话框

    fun faultTitle(fault: BootFault): String = when (fault) {
        BootFault.NoInstance -> "打不开这台虚拟机"
        is BootFault.RomMissing -> "ROM 原包不在了"
        is BootFault.RomDamaged -> "ROM 文件不完整"
        is BootFault.Other -> "启动没有完成"
    }

    fun faultMessage(fault: BootFault): String = when (fault) {
        BootFault.NoInstance -> "启动入口没有带上虚拟机编号，或者这台虚拟机已经被删除。"
        is BootFault.RomMissing ->
            "找不到 " + fault.vmName + " 用的 ROM（" + fault.romId + "）。" +
                "原包不在本机，重新解包也无从下手，需要重新导入这个 ROM。"
        is BootFault.RomDamaged -> buildString {
            val count = fault.problems.size
            if (count > 0) {
                append("系统文件缺失 ").append(count).append(" 处，例如 ").append(fault.problems.first())
            } else {
                append("解包目录与清单对不上")
            }
            append('\n')
            if (fault.canRepair) {
                append("可以自动修复，不需要重新安装虚拟机。")
            } else {
                append("原包已经不在本机，无法自动修复，需要重新导入这个 ROM。")
            }
        }
        is BootFault.Other -> fault.reason
    }

    /** 积极动作的按钮文字；C3 但原包不在时降级成「导入这个 ROM」。 */
    fun faultConfirm(fault: BootFault): String = when (fault) {
        BootFault.NoInstance -> ACTION_BACK_HOME
        is BootFault.RomMissing -> ACTION_IMPORT_ROM
        is BootFault.RomDamaged -> if (fault.canRepair) ACTION_REPAIR else ACTION_IMPORT_ROM
        is BootFault.Other -> ACTION_SHARE
    }

    /** 消极动作：只有「结论已定、用户只能知道」之外的情况才给「忽略」。 */
    fun faultDismiss(fault: BootFault): String = if (fault is BootFault.NoInstance) "关闭" else ACTION_DISMISS

    // ------------------------------------------------------------------ B 类弹窗

    /**
     * 「虚拟机运行异常」：必须明确告诉用户「画面没被盖住」，否则用户会以为是启动页又盖了一层。
     */
    fun alert(reason: String): BootAlert = BootAlert(
        title = "虚拟机运行异常",
        message = buildString {
            append("画面已停止更新：").append(reason.ifBlank { FAILURE_UNKNOWN })
            append('\n')
            append("可以查看这次运行的记录，或先忽略。")
        },
        linkText = LINK_VIEW_RECORD,
        dismissText = ACTION_DISMISS,
        confirmText = ACTION_SHARE,
    )

    /** 启动链上发现解包不完整 → 自动发起修复时的一句话（不需要用户点「去修复」）。 */
    fun autoRepair(romTitle: String): String = "ROM 解包不完整，正在为你自动修复：" + romTitle

    // ---------------------------------------------- ROM 闸门（启动页弹窗）

    const val GATE_NEED_VERSION_TITLE = "需要先选一个 ROM 版本"

    const val GATE_OPEN_VERSION = "确定"

    /** 关掉弹窗（不做任何动作，也不拦着用户回上一层）。 */
    const val GATE_IGNORE = "忽略"

    const val GATE_CONTINUE = "继续启动"

    /** 当前版本不在了，但同系列里有更新的一版。 */
    fun gateMissingWithNewer(current: String, newer: String): String =
        "这台虚拟机原来用的 " + current + " 已经不在了。" +
            newer + " 是更新的版本，去版本管理里选它就能继续使用。"

    /** 当前版本不在了，同系列里也没有更新版（只能重选或导入）。 */
    fun gateMissing(current: String): String =
        "这台虚拟机原来用的 " + current + " 已经不在了，请到版本管理里选一个可用版本；" +
            "列表是空的就先导入对应的 ROM 包。"

    /** 有更新版本可用（可选）。 */
    fun gateUpdate(current: String, latest: String): String =
        "现在是 " + current + "，已经有更新的 " + latest + "。" +
            "可以先去版本管理里切换，或者继续用当前这一份启动。"

    /** 打包失败 / 分享面板起不来时的一句话（走 Snackbar，不弹第二个对话框）。 */
    const val SNAPSHOT_PACKING = "正在整理日志快照…"
    const val SNAPSHOT_FAILED = "日志快照打包失败，请稍后重试"
    const val SHARE_FAILED = "无法打开分享面板"
    const val RECORD_MISSING = "这条记录已被清理"
}