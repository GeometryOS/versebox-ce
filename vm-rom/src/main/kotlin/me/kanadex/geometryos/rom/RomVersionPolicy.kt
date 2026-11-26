// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

/**
 * 一个可选的 ROM 版本，版本管理页只画它，不判断谁新谁旧。
 * 身份是 id、版本号、来源、内容指纹四项。同版本号的内置和本地是两条，
 * 内容不同的两份同版本也是两条，只比 key 会把它们合成一条。
 */
data class RomVersionEntry(
    val key: String,
    val id: String,
    val version: String,
    val displayName: String,
    val source: RomSource,
    val apiLevel: Int,
    // 已经解包，能真的开机。占位包和没解包的内置包都是 false
    val available: Boolean,
    /**
     * 包还在，只是还没解包。它和 available 的区别决定启动闸门的行为，
     * 没解包不等于缺失，启动链会带进度解出来，不该当成当前版本不在。
     */
    val extractable: Boolean = true,
    // 内容指纹，同版本号但内容不同时用它区分
    val payloadSha256: String = "",
    // 占位包，列表里能看到，但必须导入完整 ROM 才能选
    val placeholder: Boolean = false,
) {
    val nameWithVersion: String get() = RomTexts.nameWithVersion(displayName, version)

    /**
     * 列表身份。同版本两行（来源不同或内容不同）的列表 key 和动作定位都认它，
     * 只到 romKey 会撞成同一个 id，那时列表只剩一行，切换也就没有目标了
     */
    val identity: RomIdentity get() = RomIdentity(source, key, payloadSha256)
    val rowId: String get() = identity.rowId

    // 这一条的身份，切换判据和实例绑定都要带全
    val ref: RomRef get() = RomRef(key = key, source = source, payloadSha256 = payloadSha256)
}

/**
 * 一台虚拟机用的是哪一份的完整身份。key 只说哪个系统的哪一版，
 * 同版本号的另一份靠内容指纹区分，来源用来回答列表里哪一行在生效
 */
data class RomRef(
    val key: String,
    val source: RomSource? = null,
    val payloadSha256: String = "",
) {
    val id: String get() = RomKeys.idOf(key)
    val version: String get() = RomKeys.versionOf(key)
    val isEmpty: Boolean get() = key.isEmpty()
}

// 一次切换的性质，决定弹哪种对话框
enum class RomSwitch {
    // 同一个身份，什么都不用做
    Same,

    // 同 id，版本更高
    Upgrade,

    // 同 id，版本更低，必须警告
    Downgrade,

    // 同 id，同版本号但内容不同，必须警告
    SameVersionDifferentPayload,

    // 同 id，内容相同但来源不同，也是真切换，要确认
    SameVersionDifferentSource,

    // 不同 id，也就是跨安卓版本，当前不支持
    DifferentSeries,
}

// 这台虚拟机该不该提示更新、还开不开得起来
sealed interface RomVersionDecision {
    // 当前选择可用，也没有更新的版本
    data object UpToDate : RomVersionDecision

    /**
     * 当前选择可用，同 id 下有版本号更高的版本。只有严格更高才算更新，
     * 同版本号的另一份包不是更新，只在版本管理页里可选
     */
    data class UpdateAvailable(
        val current: RomVersionEntry,
        val latest: RomVersionEntry,
    ) : RomVersionDecision

    // 当前选择已经不可用，但同 id 下有能用的版本，必须换，否则开不起来
    data class CurrentMissing(
        val selectedKey: String,
        val fallback: RomVersionEntry?,
    ) : RomVersionDecision

    // 当前选择不可用，同 id 下也没有能用的版本，只能导入
    data class Unavailable(val selectedKey: String) : RomVersionDecision
}

/**
 * 版本管理的唯一判据。版本管理页、启动闸门、列表卡片的更新提示、ROM 列表的选择模式
 * 都只读这里，四处各写一份必然漂移。
 * 只在同 id 内比较版本，跨 id 是换系统不是降级；最新指同 id 里版本号最大的；
 * 可用性优先于版本号；判是不是同一份要带内容指纹和来源，不能只看 key。
 */
object RomVersionPolicy {

    // 同一 id 的全部版本，按版本号倒序，旧格式没有版本号排最后
    fun series(entries: List<RomVersionEntry>, id: String): List<RomVersionEntry> =
        entries.filter { it.id == id }
            .sortedWith { left, right ->
                RomKeys.compareVersions(right.version, left.version)
                    .takeIf { it != 0 }
                    ?: left.key.compareTo(right.key)
            }

    // 同 id 里版本号最大的一条，不管能不能用
    fun latest(entries: List<RomVersionEntry>, id: String): RomVersionEntry? = series(entries, id).firstOrNull()

    // 同 id 里能用且版本号最大的一条
    fun latestAvailable(entries: List<RomVersionEntry>, id: String): RomVersionEntry? =
        series(entries, id).firstOrNull { it.available }

    /**
     * 把 ref 解析成具体条目，按身份找，不按 key 猜。
     * 指纹和来源都对上就是它；只有指纹对上、来源不同也算；旧实例没指纹时取同 key 第一条；
     * 指纹对不上返回 null，这时这一份真的不在了，必须让用户重选。
     */
    fun match(entries: List<RomVersionEntry>, ref: RomRef): RomVersionEntry? {
        if (ref.key.isEmpty()) return null
        val sameKey = entries.filter { it.key == ref.key }
        if (sameKey.isEmpty()) return null
        if (ref.payloadSha256.isEmpty()) {
            return sameKey.firstOrNull { ref.source == null || it.source == ref.source } ?: sameKey.first()
        }
        val samePayload = sameKey.filter { it.payloadSha256.isNotEmpty() && it.payloadSha256 == ref.payloadSha256 }
        if (samePayload.isEmpty()) return null
        return samePayload.firstOrNull { ref.source == null || it.source == ref.source } ?: samePayload.first()
    }

    // 只给 key 的旧调用，等价于一个有 key 没有来源和指纹的引用
    fun current(entries: List<RomVersionEntry>, selectedKey: String): RomVersionEntry? =
        match(entries, RomRef(selectedKey))

    fun newerVersion(entries: List<RomVersionEntry>, ref: RomRef): RomVersionEntry? =
        if (ref.isEmpty) null else series(entries, ref.id).firstOrNull {
            !it.placeholder && (it.available || it.extractable) &&
                RomKeys.compareVersions(it.version, ref.version) > 0
        }

    fun isSelectable(entry: RomVersionEntry?): Boolean =
        entry != null && !entry.placeholder && (entry.available || entry.extractable)

    // 这台虚拟机现在该提示更新、强制换版本，还是不可用
    fun decision(entries: List<RomVersionEntry>, ref: RomRef): RomVersionDecision {
        if (ref.key.isEmpty()) return RomVersionDecision.Unavailable("")
        val id = ref.id
        val series = series(entries, id)
        val selected = match(entries, ref)
        /**
         * 先看真能开机的，再看能解包的。只看 available 会把内置包还没解包误判成什么都没有，
         * 用户看到的是请先导入 ROM 包，而包其实就在安装包里。
         */
        val best = latestAvailable(entries, id)
            ?: series.firstOrNull { it.extractable && !it.placeholder }
        if (selected == null) {
            // 这一份连包都没有了，版本被删或内容被换掉
            return if (best == null) RomVersionDecision.Unavailable(ref.key)
            else RomVersionDecision.CurrentMissing(ref.key, best)
        }
        if (!isSelectable(selected)) {
            // 包在，但不可用也解不出来，同样只能去版本管理
            return if (best == null) RomVersionDecision.Unavailable(ref.key)
            else RomVersionDecision.CurrentMissing(ref.key, best)
        }
        /**
         * 没解包但包还在，不算当前版本不在，交给启动链的修复步骤解出来。
         * 同版本号的另一份包不算更新，版本号没变，只在版本管理页里可选，这里只认严格更高。
         */
        return newerVersion(series, ref)?.let { RomVersionDecision.UpdateAvailable(selected, it) }
            ?: RomVersionDecision.UpToDate
    }

    // 只给 romKey 的旧调用
    fun decision(entries: List<RomVersionEntry>, selectedKey: String): RomVersionDecision =
        decision(entries, RomRef(selectedKey))

    /**
     * 这次切换的性质，决定弹不弹警告。判据是两条 entry，不是两个 key
     * 同 key 不同内容、同 key 不同来源都是真切换。from 为 null 表示还没绑定 ROM，那是选一个
     */
    fun switchKind(from: RomVersionEntry?, to: RomVersionEntry): RomSwitch {
        if (from == null) return RomSwitch.Upgrade
        if (from.key != to.key) {
            if (from.id != to.id) return RomSwitch.DifferentSeries
            val order = RomKeys.compareVersions(to.version, from.version)
            return when {
                order > 0 -> RomSwitch.Upgrade
                order < 0 -> RomSwitch.Downgrade
                // 版本号写法和数值可能不同（1.0 和 1.0.0），算同版本，继续比内容和来源
                else -> identitySwitch(from, to)
            }
        }
        return identitySwitch(from, to)
    }

    private fun identitySwitch(from: RomVersionEntry, to: RomVersionEntry): RomSwitch = when {
        !samePayload(from, to) -> RomSwitch.SameVersionDifferentPayload
        !sameSource(from, to) -> RomSwitch.SameVersionDifferentSource
        else -> RomSwitch.Same
    }

    // 两条是不是同一份。key、来源、内容指纹都要一样
    fun sameIdentity(left: RomVersionEntry, right: RomVersionEntry): Boolean =
        left.key == right.key && sameSource(left, right) && samePayload(left, right)

    /**
     * 内容是否相同。有一边指纹为空时不能断言不同，那会给出假的警告；
     * 空指纹只出现在旧格式和占位包，它们的切换以来源为准。
     */
    private fun samePayload(candidate: RomVersionEntry, selected: RomVersionEntry): Boolean {
        val a = candidate.payloadSha256
        val b = selected.payloadSha256
        if (a.isEmpty() || b.isEmpty()) return true
        return a == b
    }

    private fun sameSource(candidate: RomVersionEntry, selected: RomVersionEntry): Boolean =
        candidate.source == selected.source

    // 只有同 id 允许切换，跨安卓版本是换系统
    fun canSwitch(fromKey: String, toKey: String): Boolean =
        fromKey.isNotEmpty() && toKey.isNotEmpty() &&
            RomKeys.idOf(fromKey) == RomKeys.idOf(toKey)

    /**
     * 要警告的三种：降级、同版本换另一份内容、同版本换另一个来源。
     * 后两种换的是列表里另一个独立 ROM，行不同就是版本不同。
     */
    fun requiresWarning(kind: RomSwitch): Boolean =
        kind == RomSwitch.Downgrade ||
            kind == RomSwitch.SameVersionDifferentPayload ||
            kind == RomSwitch.SameVersionDifferentSource

    /**
     * 改完版本要不要重启才生效。会话在跑、正在开机、正在关机都要重启，
     * 干净空闲就不用，下次启动自然生效。
     */
    fun requiresRestart(running: Boolean, busy: Boolean, stopping: Boolean): Boolean =
        running || busy || stopping
}
