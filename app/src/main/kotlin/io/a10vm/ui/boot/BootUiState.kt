// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

import me.kanadex.geometryos.rom.RomTone

/**
 * 第一屏的四个阶段，与 `docs/ui-rework/09/03` §1 的三态矩阵一一对应：
 *
 * | 值 | 对应文档 | 含义 |
 * | --- | --- | --- |
 * | [Booting] | A 类 | 启动中，尚无画面 |
 * | [Ready] | — | 已经出过首帧（第一屏应当消失，不再遮挡） |
 * | [NoPicture] | B 类 | 曾有画面，之后停止更新（保留最后一帧 + 弹窗） |
 * | [Failed] | C 类 | 起不来 |
 * | [Stopping] | — | 出过画面之后被停止：显示「正在关机」，停完自动退出窗口 |
 */
enum class BootPhase { Booting, Ready, NoPicture, Failed, Stopping }

/**
 * C 类（起不来）的四个子判据（`docs/ui-rework/09/03` §2）。
 *
 * 只装事实，不装文案：文案由 [BootTexts] 生成，页面一个字都不拼。
 */
sealed interface BootFault {
    /** C1：入口没传参 / 查不到实例。 */
    data object NoInstance : BootFault

    /** C2：原包缺失（ROM 登记里查不到，或目录被删）。 */
    data class RomMissing(val vmName: String, val romId: String) : BootFault

    /**
     * C3：解包内容损坏 / 校验失败。
     *
     * @param key ROM 主键（`<id>@<version>`）
     * @param rowId 要修复的**那一行**（`<来源>:<romKey>@<sha8>`）。
     *        同版本可能存在内置/本地两行、或同来源的两份内容：只给 key 会去修另一份，
     *        用户看到"修完了但还是老样子"（P5/P3 的同一类错误）。
     * @param problems 清单比对出的缺失条目（数据层给，最多 64 条）
     * @param canRepair 原包还在不在——决定有没有「去修复」这个动作
     */
    data class RomDamaged(
        val key: String,
        val rowId: String,
        val romTitle: String,
        val problems: List<String>,
        val canRepair: Boolean,
    ) : BootFault

    /** C4：其它启动失败（有 ERROR 事件但无 C1–C3 特征）。 */
    data class Other(val reason: String) : BootFault
}

/**
 * 启动闸门在**启动页**上的弹窗内容（2026-11-12 用户要求）。
 *
 * 三件事：这一次为什么起不来（或为什么建议换版本）、两个出口的文字。
 * 页面只画这几个字符串；判定在 [BootViewModel]，动作在 [BootAction]。
 */
data class RomGate(
    val title: String,
    val message: String,
    /** 「去版本管理」——非破坏性跳转：管理页 → 版本管理页。 */
    val openVersionText: String,
    /** 「继续启动」——只有"有更新版本但仍可启动"时才有；null = 没有这个出口。 */
    val continueText: String? = null,
)

/**
 * B 类「虚拟机运行异常」弹窗的全部文案（`docs/ui-rework/09/03` §3）。
 *
 * 形态参照 `ui/processlimit/ProcessLimitAlert.kt`：标题 + 两句正文 + 一段链接文本 +
 * 「忽略 / 分享日志快照」两个动作。页面只渲染这几个字符串。
 */
data class BootAlert(
    val title: String,
    val message: String,
    val linkText: String,
    val dismissText: String,
    val confirmText: String,
)

/**
 * 第一屏的**唯一**视图状态。字段里没有 Compose 类型（`docs/ui-rework/02` §1），
 * 也没有 Context / File / Intent（`09/05` §4）。
 */
data class BootUiState(
    val vmName: String = "",
    val romLine: String = "",
    val phase: BootPhase = BootPhase.Booting,
    /** 数据层给的「是否出过首帧」：页面不许用「Surface 存在」之类去猜（`09/03` §1）。 */
    val hadFirstFrame: Boolean = false,
    /** 状态胶囊那行字，来自 [io.a10vm.ui.BootLogText.message]。 */
    val stageLine: String = "",
    /** 「正在启动 X」/「正在关闭 X」：主提示的前半句由逻辑层给，页面不拼。 */
    val headline: String = "",
    /** 「已等待 N 秒」：卡住多久的唯一体感指标。 */
    val waitSeconds: Int = 0,
    val waitTone: RomTone = RomTone.Neutral,
    /**
     * 累计的开机日志行（去重后的原始行，旧 → 新）。
     *
     * 滚动节奏、可见窗口、alpha 全部在 [BootLogTerminalView] 里（逐行移植的旧 `BootTerminalView`），
     * 这里只给**原始行**；Compose 侧只做「把新行喂进去」。
     */
    val queue: List<String> = emptyList(),
    /** 队列被清空的次数：变了就让 UI 重建终端视图（重新启动 / 新会话）。 */
    val resetToken: Int = 0,
    /** 系统动画是否开启；关掉时 UI 必须一次性把队列铺完（09/02 §1 的无障碍降级）。 */
    val animateLog: Boolean = true,
    /** 正在关闭（出过画面之后被 stop）：第一屏重新出现并显示「正在关机」。 */
    val stopping: Boolean = false,
    /** 关闭已经完成：宿主 Activity 应当把窗口收回到上层页面，而不是停在那里。 */
    val exitRequested: Boolean = false,
    val fault: BootFault? = null,
    /** ROM 闸门的弹窗（没选版本 / 版本不在 / 有更新版本）；null = 没有要说的。 */
    val romGate: RomGate? = null,
    val alert: BootAlert? = null,
    /** 本次操作在记录中心的行号；「查看这次运行的记录」用它定位。 */
    val recordId: Long? = null,
    val busy: Boolean = false,
    val busyText: String = "",
    /** 一次性提示，页面读过即发 [BootAction.ConsumeMessage]。 */
    val message: String? = null,
    /** 一次性提示的标题（默认「提示」；分享快照失败时才用快照主题）。 */
    val messageTitle: String = "提示",
) {
    /**
     * 第一屏要不要占满屏。
     *
     * - 没出过首帧（A/C 类）→ 占屏；
     * - 出过首帧（B 类）→ **让出画面**，绝不遮挡最后一帧（`09/03` §5 第 1 条）；
     * - 正在关闭 → 占屏并显示「正在关机」（这是用户主动发起的转移，不是异常）。
     */
    val showsFirstScreen: Boolean get() = !hadFirstFrame || stopping
}

/** 第一屏能发生的全部动作。页面只发它，业务一律在状态层/数据层。 */
sealed interface BootAction {
    /** 「查看运行记录」/ 弹窗里的链接：走 09/05 的定位链路（主页 → 记录 tab → 滚动 → 闪烁）。 */
    data object OpenRecord : BootAction

    /** 抓四个日志片段打包成 zip 并交给系统分享面板。 */
    data object ShareSnapshot : BootAction

    /**
     * C 类对话框的积极动作。
     *
     * 具体是哪一件事（返回首页 / 导入 ROM / 去修复 / 分享快照）由 [BootFault] 的类型决定，
     * **页面不判断类型**（`docs/ui-rework/02` §2：换分支还要重写的判断就是逻辑）。
     */
    data object ConfirmFault : BootAction

    /** 关掉 C 类对话框，停在第一屏继续观察。 */
    data object DismissFault : BootAction

    // ---- ROM 闸门（启动页弹窗）的三个出口 ----
    /** 非破坏性跳转：管理页 → 版本管理页（由宿主 Activity 处理，因为要发 Intent）。 */
    data object OpenRomVersion : BootAction

    /** 「继续启动」：跳过版本提示，直接发 START（宿主 Activity 处理）。 */
    data object ContinueStart : BootAction

    /** 闸门弹窗被关掉（只清 UI，不做任何动作）。 */
    data object DismissRomGate : BootAction

    /** 关掉 B 类弹窗（保留最后一帧，不重启、不停实例）。 */
    data object DismissAlert : BootAction

    /** 在当前窗口内重新走一次启动流程（不重建 Activity）。 */
    data object Restart : BootAction

    /** 手动再判一次起不来的原因。 */
    data object Recheck : BootAction

    data object ConsumeMessage : BootAction
}