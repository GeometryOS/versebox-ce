// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

// 包从哪来，以及能不能删，只看这里
enum class RomSource {
    // APK 里带的 zip 包，只能清解包缓存，包本身删不掉
    Builtin,

    // 用户自己导入的 zip 包，包和解包目录都能删
    Imported,

    // 旧格式，数据还在 APK 的 assets 里
    Legacy,
}

// ROM 的登记状态，UI 只认这五个
enum class RomState { Pending, Extracting, Ready, Failed, Missing }

/**
 * 现在能不能直接用这个 ROM，启动链和创建链共用这一个结论。
 * Ready 直接启动，NeedsExtract 原包还在、重新解出来就能用，Missing 连原包都没有，只能提示导入。
 */
enum class RomGate {
    Ready,
    NeedsExtract,
    Missing,
    ;

    companion object {
        /**
         * 由这一行的登记和解包检查结果给出结论。
         * row 为 null 表示列表里没有这一行，problems 为空表示解包体完整。
         */
        fun decide(row: RomRow?, problems: List<String>): RomGate = when {
            row == null -> Missing
            // 旧格式跟着 APK 走，没有可解包的派生层
            row.source == RomSource.Legacy -> Ready
            // 占位包没有 payload，只能导入完整包
            row.placeholder -> Missing
            row.isReady && problems.isEmpty() -> Ready
            row.hasArchive -> NeedsExtract
            else -> Missing
        }
    }
}

// 徽章和状态用的色档，页面把它映射成主题颜色
enum class RomTone { Neutral, Primary, Success, Warning, Error }

// 一行徽章，文字加色档
data class RomBadge(val text: String, val tone: RomTone)

// 一条 ROM 登记，字段都由数据层填，UI 不自己算
data class RomRow(
    val key: String,
    val id: String,
    val version: String,
    val displayName: String,
    val description: String,
    val androidVersion: String,
    val apiLevel: Int,
    val source: RomSource,
    val state: RomState,
    val failure: String? = null,
    val payloadBytes: Long = 0L,
    val archiveBytes: Long = 0L,
    val extractedBytes: Long = 0L,
    val libMode: String = "apk",
    val installedAt: Long = 0L,
    val lastUsedAt: Long? = null,
    val usedByVms: Int = 0,
    val usedByNames: List<String> = emptyList(),
    // 包内容指纹，同 key 不同内容也算另一份
    val payloadSha256: String = "",
    // 占位包只有自述和清单，得让用户导入完整包才能用
    val placeholder: Boolean = false,
    /**
     * 列表身份，由来源、romKey 和内容指纹拼成。
     * 同版本的内置与本地、同来源导入两次都是不同的行，只用 romKey 会撞 key，动作会打错行。
     */
    val rowId: String = source.name.lowercase() + ":" + key +
        (if (payloadSha256.isEmpty()) "" else "@" + RomLayout.payloadTag(payloadSha256)),
    // 已解包目录里那一份的指纹，没有解包目录就是空
    val extractedSha256: String = "",
    /**
     * 原包还在。内置包看 zip，本地包看 archives 里那一份。
     * 解包目录被删之后能不能重新解出来，全靠它。
     */
    val hasArchive: Boolean = false,
) {
    // 能不能拿来新建或启动虚拟机
    val isReady: Boolean get() = state == RomState.Ready

    // 只有本地导入的包能整包删，内置的只能清缓存，旧格式跟着 APK 走
    val canDelete: Boolean get() = source == RomSource.Imported

    // 内置包能清掉已经解出来的那部分，下次用会重新解
    val canClearCache: Boolean get() = source == RomSource.Builtin && state != RomState.Pending

    // 能不能检查解包内容。内置和本地同一条链，旧格式没有派生层，不检查
    val canCheck: Boolean get() = source != RomSource.Legacy && !placeholder

    // 能不能从原包重解一份。不是旧格式，且原包还在
    val canRepair: Boolean get() = source != RomSource.Legacy && hasArchive && !placeholder

    // 磁盘上解包的就是这一份。没有解包目录或指纹对不上，都算不是
    val isActivePayload: Boolean get() =
        isReady && payloadSha256.isNotEmpty() && payloadSha256 == extractedSha256
}

/**
 * 一次解包检查的结论，只覆盖派生层。
 * 只比对清单里的存在性和字节数，全量哈希太慢，内容对不对由解包时的逐条校验保证。
 */
data class RomCheckReport(
    val key: String,
    // 清单里检查过的条目数
    val total: Int,
    // 有问题的相对路径，最多 64 条
    val problems: List<String>,
    // 原包还在不在，决定能不能自动修
    val canRepair: Boolean,
) {
    // 解包内容和清单一致
    val healthy: Boolean get() = problems.isEmpty()

    // 第一条问题，写文案时举例用
    val firstProblem: String? get() = problems.firstOrNull()
}

/**
 * 页面直接画的一行，文案已经由逻辑层拼好。
 * 页面只画标题、说明、元信息和徽章，把点击翻译成 Action。
 */
data class RomItem(
    // 运行期主键，切换版本和解包都认它
    val key: String,
    // 列表身份，列表的 key 和所有行内动作都认它
    val rowId: String,
    val title: String,
    val description: String,
    val meta: String,
    val badges: List<RomBadge>,
    val tone: RomTone,
    val canDelete: Boolean,
    val canClearCache: Boolean,
    val canCheck: Boolean,
    // 能不能从原包重新解出来
    val canRepair: Boolean,
    val canUse: Boolean,
    // 能用，但要先解包
    val needsExtract: Boolean,
    val apiLevel: Int,
    // 这一行是不是当前选择，由逻辑层判定
    val selected: Boolean = false,
    // 单行摘要，给只画一行的版式用
    val summary: String = "",
)

// 解包或导入的进度
data class RomProgress(val stage: String, val done: Int, val total: Int) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

// ROM 层的失败，消息是一句人话
class RomException(message: String, cause: Throwable? = null) : Exception(message, cause)

// 用户主动取消，不算失败，不写审计也不改状态
class RomCancelled : Exception("已取消")