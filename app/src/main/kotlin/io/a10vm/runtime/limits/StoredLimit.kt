// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.runtime.limits

/** 结论的来源。自动检测与手动检测对「未观测到」的解释不同，必须分开存。 */
enum class LimitOrigin {
    /** App 启动时后台自动跑的。 */
    AUTO,

    /** 用户点卡片手动跑的。 */
    MANUAL,

    /** 从库里读出来的历史结论（本次还没跑过）。 */
    RESTORED,
}

/**
 * 一次检测结论 + 来源 + 抑制位（**判定规则的唯一来源**，两套 UI 分支都只读它）。
 *
 * 判据只有三条，全部来自需求原文：
 *
 * 1. [limited]：系统**真的回收了子进程**。这是唯一可置信的「被限制」证据。
 * 2. [trulyNormal]：`NotNeeded`，或**手动**检测得到的 `Relaxed`。只有它能清 [muted]；
 *    自动检测的「未观测到」是 3 秒超时误判，既不算解除，也不当基准、不落盘 ——
 *    它只是「这次没看到」，卡片照实显示，但判据不采信。
 * 3. [shouldAlert]：**没被抑制时，每次进 App 检测到 [limited] 都弹**（每次启动都会自动检测一次；
 *    同一次进入里不会连弹两张）；点了「不再提示」就静默，直到出现 [trulyNormal] 把抑制位清掉 ——
 *    之后限制再生效才重新弹（「真解除 → 又上锁」的路径）。
 */
data class StoredLimit(
    val limit: ChildProcessLimit = ChildProcessLimit.Unknown("尚未检测"),
    val origin: LimitOrigin = LimitOrigin.RESTORED,
    val at: Long = 0L,
    /** 用户点过「不再提示」；只在出现 [trulyNormal] 时清掉。 */
    val muted: Boolean = false,
) {

    /** 系统确实回收了子进程：唯一可置信的「被限制」证据。 */
    val limited: Boolean get() = limit is ChildProcessLimit.Limited

    /** 真正解除（唯一能清抑制位的结论）。 */
    val trulyNormal: Boolean
        get() = when (limit) {
            ChildProcessLimit.NotNeeded -> true
            is ChildProcessLimit.Relaxed -> origin == LimitOrigin.MANUAL
            else -> false
        }

    /** 可以落盘、可以当跃迁基准的结论（自动检测的「未观测到」不在内）。 */
    val confident: Boolean get() = limited || trulyNormal

    /**
     * 这次是否应该弹对话框。
     *
     * - 只有拿到「系统真的回收了子进程」这条证据才弹：3 秒乐观值（`Relaxed`）不是证据，
     *   否则没限制的机器一启动就会弹一张假的限制提示（两者在首页卡片上都显示「未见限制」）；
     * - 没点「不再提示」时**每次进 App 都弹**（每次启动都会自动检测一次）；已弹过就不再弹，
     *   由 [alreadyAlerted] 挡住同一次进入里的第二轮检测（进二级页会自动再跑一次），
     *   所以不会连弹两张；
     * - 「不再提示」期间 [muted] 为 true，一律不弹；只有出现 [trulyNormal] 才清抑制位，
     *   之后限制再次生效才会重新弹（「解除之后又被上锁」的路径）。
     */
    fun shouldAlert(alreadyAlerted: Boolean = false): Boolean =
        limited && !muted && !alreadyAlerted
}
