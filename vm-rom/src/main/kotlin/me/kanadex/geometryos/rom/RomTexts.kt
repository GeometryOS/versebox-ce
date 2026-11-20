// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import java.util.Locale

/**
 * ROM 列表、对话框与版本管理页上给用户看的全部文案。
 *
 * 输入是 RomRow 与版本决策，输出是界面字符串；这里只做措辞，不做状态判断，
 * 判断在 RomVersionPolicy。两个 UI 分支共用这一份，页面不许再拼第二份。
 */
object RomTexts {

    fun bytes(value: Long): String {
        if (value <= 0L) return "0 MB"
        val mb = value / 1024.0 / 1024.0
        if (mb < 1.0) return String.format(Locale.ROOT, "%.0f KB", value / 1024.0)
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.0f MB", mb)
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0)
    }

    // 名称后面接版本号，旧格式没有版本号就只给名称
    fun nameWithVersion(name: String, version: String): String {
        val base = name.ifBlank { "ROM" }
        if (version.isBlank() || version == RomKeys.LEGACY_VERSION) return base
        return base + " V" + version
    }

    fun title(row: RomRow): String = row.displayName.ifBlank { row.id }

    fun titleWithVersion(row: RomRow): String = nameWithVersion(title(row), row.version)

    fun description(row: RomRow): String = row.description.ifBlank { row.displayName.ifBlank { row.id } }

    // 一行元信息，写版本、解包占用和被几台虚拟机使用
    fun meta(row: RomRow): String {
        val parts = mutableListOf<String>()
        if (row.version.isNotBlank() && row.version != RomKeys.LEGACY_VERSION) parts += row.version
        when (row.state) {
            RomState.Ready -> {
                if (row.source == RomSource.Legacy) parts += "跟随安装包"
                else if (row.extractedBytes > 0L) parts += "已解包 " + bytes(row.extractedBytes)
                else parts += "已解包"
            }

            RomState.Extracting -> parts += "正在解包…"
            RomState.Pending -> parts += "尚未解包（首次使用会自动解包）"
            RomState.Failed -> parts += "解包失败"
            RomState.Missing -> parts += "解包目录已丢失"
        }
        if (row.usedByVms > 0) parts += row.usedByVms.toString() + " 台虚拟机使用"
        return parts.joinToString(" · ")
    }

    // 来源一个徽章，状态或 API 一个徽章
    fun badges(row: RomRow): List<RomBadge> {
        val list = mutableListOf<RomBadge>()
        list += when (row.source) {
            RomSource.Builtin -> RomBadge("内置", RomTone.Primary)
            RomSource.Imported -> RomBadge("本地", RomTone.Success)
            RomSource.Legacy -> RomBadge("旧格式", RomTone.Warning)
        }
        list += when (row.state) {
            RomState.Failed -> RomBadge("解包失败", RomTone.Error)
            RomState.Missing -> RomBadge("ROM 缺失", RomTone.Error)
            RomState.Extracting -> RomBadge("解包中", RomTone.Neutral)
            else -> RomBadge("API " + row.apiLevel, RomTone.Neutral)
        }
        return list
    }

    // 整行的色档，解包失败或目录丢失的标红
    fun tone(row: RomRow): RomTone = when (row.state) {
        RomState.Failed, RomState.Missing -> RomTone.Error
        RomState.Extracting -> RomTone.Primary
        RomState.Pending -> RomTone.Warning
        RomState.Ready -> if (row.source == RomSource.Legacy) RomTone.Warning else RomTone.Neutral
    }

    // 状态说明，失败时带上原因
    fun stateDetail(row: RomRow): String? = when (row.state) {
        RomState.Ready -> null
        RomState.Pending -> "点「新建」会自动解包，也可以先在上面导入本地 ROM 包。"
        RomState.Extracting -> "正在解包，请稍候。"
        RomState.Failed -> row.failure ?: "解包失败，可以清理解包缓存后重试。"
        RomState.Missing -> row.failure ?: "解包目录不存在了，请清理缓存后重新解包。"
    }

    // 一行摘要，给只画一行的版式用
    fun summary(row: RomRow): String =
        (badges(row).map { it.text } + meta(row)).filter { it.isNotBlank() }.joinToString(" · ")

    fun item(row: RomRow, selected: Boolean = false): RomItem {
        // 选中只看调用方给的那一行，同 key 的内置与本地是两份
        val active = selected
        // 原包还在就能重新解出来，与来源无关
        val needsExtract = !row.isReady && row.hasArchive && !row.placeholder
        return RomItem(
            key = row.key,
            rowId = row.rowId,
            title = titleWithVersion(row),
            description = description(row) + (stateDetail(row)?.let { "\n" + it } ?: ""),
            meta = meta(row),
            badges = badges(row),
            tone = tone(row),
            canDelete = row.canDelete,
            canClearCache = row.canClearCache,
            canCheck = row.canCheck,
            canRepair = row.canRepair,
            canUse = row.isReady || needsExtract,
            needsExtract = needsExtract,
            apiLevel = row.apiLevel,
            selected = active,
            summary = summary(row),
        )
    }

    // 对话框文案

    fun deleteMessage(row: RomRow): String {
        val head = "删除 " + titleWithVersion(row) + "？将释放 " +
            bytes(if (row.extractedBytes > 0L) row.extractedBytes else row.payloadBytes) + "。"
        if (row.usedByVms <= 0) return head
        val names = row.usedByNames.joinToString("、")
        return head + "使用它的 " + row.usedByVms + " 台虚拟机（" + names +
            "）将无法启动，数据仍然保留，重新导入同一 ROM 后可恢复。"
    }

    fun clearCacheMessage(row: RomRow): String =
        "清理 " + titleWithVersion(row) + " 的解包缓存？将释放 " +
            bytes(row.extractedBytes) + "，安装包里的 ROM 不受影响，下次使用会重新解包。"

    // 解包进行到第几个文件
    fun progress(progress: RomProgress): String =
        if (progress.total <= 1) progress.stage
        else progress.stage + " " + progress.done.toString() + "/" + progress.total

    fun imported(row: RomRow): String = "已导入 " + titleWithVersion(row)

    fun duplicate(row: RomRow): String = titleWithVersion(row) + " 已经在列表里了"

    fun deleted(row: RomRow): String = "已删除 " + titleWithVersion(row)

    fun cacheCleared(row: RomRow): String = "已清理 " + title(row) + " 的解包缓存"

    // 检查和重新解包

    fun checking(): String = "正在检查解包内容…"

    fun checkHealthy(report: RomCheckReport): String =
        "解包内容完整（" + report.total + " 个文件）"

    fun checkProblems(report: RomCheckReport): String {
        val example = report.firstProblem ?: return "解包内容不完整"
        return "解包内容有 " + report.problems.size + " 处缺失，例如 " + example
    }

    // 原包不在了，只能让用户重新导入
    fun checkNoArchive(): String = "原包不在了，无法自动修复，请重新导入这个 ROM"

    // 只告知、不询问的对话框标题
    fun checkTitle(row: RomRow?): String = row?.let { titleWithVersion(it) } ?: "ROM 检查"

    fun repairTitle(): String = "重新解包？"

    fun repairMessage(report: RomCheckReport): String =
        checkProblems(report) + "。可以从原始 ROM 包重新解出一份，大约需要几十秒；" +
            "这台 ROM 上已有的虚拟机会在下一次启动时按新内容补齐。"

    // 手动重新解包的确认文案，内置包与本地包共用这一句
    fun repairPromptMessage(row: RomRow): String =
        "将从原始 ROM 包重新解出一份 " + titleWithVersion(row) + "，大约需要几十秒。" +
            "这台 ROM 上已有的虚拟机会在下一次启动时按新内容补齐，实例数据不受影响。"

    // 重新解包进行中，进度接在这一句后面
    fun repairing(): String = "正在重新解包…"

    // 第一次解包进行中，和重新解包分开说
    fun extracting(): String = "正在解包…"

    fun repaired(row: RomRow?): String =
        "已重新解包 " + (row?.let { titleWithVersion(it) } ?: "ROM")

    fun extractedNow(row: RomRow?): String =
        "已解包 " + (row?.let { titleWithVersion(it) } ?: "ROM")

    // 版本管理

    const val VERSION_ENTRY_TITLE = "ROM 版本管理"

    const val VERSION_PAGE_TITLE = "ROM 版本管理"

    const val VERSION_LATEST_TITLE = "使用最新版本"

    const val MISSING_VERSION_TITLE = "当前版本已经不在了"

    // 版本页顶部说明，讲清原来用哪一版、现在能怎么办
    fun missingVersionSummary(current: String, newer: String?): String =
        if (newer != null) {
            "这台虚拟机原来用的 " + current + " 已经不在安装包里了。" +
                "列表里的 " + newer + " 是更新的版本，选它就能继续使用。"
        } else {
            "这台虚拟机原来用的 " + current + " 已经不在列表里了。" +
                "请在下面选一个可用版本；列表是空的就先导入对应的 ROM 包。"
        }

    // 版本列表的分组标题，用 ROM 自己的名字
    fun versionGroupTitle(name: String): String = name.ifBlank { "可选版本" }

    fun versionSummary(name: String, version: String): String = nameWithVersion(name, version)

    const val VERSION_SUMMARY_NONE = "还没有选择 ROM"

    const val VERSION_LATEST_ALREADY = "已经是这个 ROM 最新的版本"

    fun versionLatestSwitchTo(latest: RomVersionEntry): String =
        "切换到 " + latest.nameWithVersion

    const val VERSION_LATEST_UNAVAILABLE = "同系列没有可用的版本，请先导入 ROM 包"

    const val VERSION_CURRENT_BADGE = "正在使用"

    fun versionReady(entry: RomVersionEntry): String =
        "Android " + entry.apiLevel + " · " + sourceLabel(entry.source)

    // 还没解包，切换时会先解包
    fun versionNeedsExtract(entry: RomVersionEntry): String =
        versionReady(entry) + " · 尚未解包（切换时会先解包）"


    const val VERSION_PLACEHOLDER = "占位版本：安装包里只有自述，需要导入完整 ROM 才能使用"

    fun sourceLabel(source: RomSource): String = when (source) {
        RomSource.Builtin -> "内置"
        RomSource.Imported -> "本地"
        RomSource.Legacy -> "旧格式"
    }

    const val SWITCH_SAME_VERSION_TITLE = "切换到另一个 ROM？"

    fun switchSameVersionMessage(from: RomVersionEntry, to: RomVersionEntry): String =
        "将改用 " + to.nameWithVersion + "（" + sourceLabel(to.source) + "）。" +
            "它在列表里是独立的一份 ROM，版本号与当前相同（" + from.version + "）；" +
            "已安装的应用与数据保留。"

    const val DOWNGRADE_TITLE = "降级到旧版本？"

    fun downgradeMessage(from: RomVersionEntry, to: RomVersionEntry): String =
        "将从 " + from.nameWithVersion + " 换成 " + to.nameWithVersion + "。降级后系统版本更旧，" +
            "已经在这个系统里创建的应用数据可能不兼容，数据仍然保留但可能读不出来。"

    const val CROSS_SERIES_TITLE = "不能跨安卓版本切换"

    fun crossSeriesMessage(from: RomVersionEntry?, to: RomVersionEntry): String =
        "这台虚拟机现在用的是 " + (from?.nameWithVersion ?: "另一个系列") + "，" +
            "而 " + to.nameWithVersion + " 属于不同的系统系列。" +
            "版本管理只支持同系列之间切换；换系统请新建一台虚拟机。"

    const val RESTART_TITLE = "重启这台虚拟机？"

    const val RESTART_MESSAGE = "ROM 版本已经记下来了，需要重新开机才会用新的系统启动。"

    const val RESTART_CONFIRM = "重启"

    const val RESTART_LATER = "稍后"

    const val IN_PROGRESS = "正在进行中，请稍候..."

    const val SAVE_FAILED = "写入失败，版本没有改变，请重试"

    const val STOP_TIMEOUT = "关机没有在预期时间内结束，版本已经记下来了，下次启动会生效"

    fun switched(to: RomVersionEntry): String =
        "已切换到 " + to.nameWithVersion + " · " + sourceLabel(to.source)

    // 启动被版本闸门拦住时的说明
    fun forcedUpdateMessage(decision: RomVersionDecision): String = when (decision) {
        is RomVersionDecision.CurrentMissing ->
            "这台虚拟机原来用的 " + RomKeys.idOf(decision.selectedKey) + " 版本已经不在了。" +
                (decision.fallback?.let { "可以切换到 " + it.nameWithVersion + " 继续使用。" }
                    ?: "请先导入这个系列的 ROM 包。")

        is RomVersionDecision.Unavailable ->
            "这台虚拟机没有可用的 ROM（" + decision.selectedKey + "），请先导入 ROM 包。"

        else -> ""
    }

    const val UPDATE_TITLE = "有新版本可用"

    // 可选更新的提示，说清现在用哪一版、能换到哪一版、继续启动会怎样
    fun updateMessage(decision: RomVersionDecision.UpdateAvailable): String =
        "现在用 " + decision.current.nameWithVersion + "，可换到 " +
            decision.latest.nameWithVersion + "。继续启动仍用当前版本。"

    const val FORCED_TITLE = "需要先选一个 ROM 版本"

    const val UPDATE_CONFIRM = "去版本管理"


    const val UPDATE_LATER = "继续启动"

    // 列表页

    const val LIST_TITLE_MANAGE = "ROM 管理"

    const val LIST_TITLE_PICK = "选择 ROM"

    const val PICK_ACTION = "选择"

    const val PICKED_ACTION = "已选"

    fun pickUnavailable(row: RomItem): String =
        if (row.badges.any { it.tone == RomTone.Error }) {
            titleText(row) + " 现在不能用，请先处理它的问题（检查 / 重新导入）。"
        } else {
            titleText(row) + " 还没有解包，回到 ROM 管理里点「重新解包」再选它。"
        }

    private fun titleText(row: RomItem): String = row.title.ifBlank { "这个 ROM" }

    fun updateBadge(decision: RomVersionDecision): RomBadge? = when (decision) {
        is RomVersionDecision.UpdateAvailable -> RomBadge("可更新 " + decision.latest.version, RomTone.Primary)
        is RomVersionDecision.CurrentMissing -> RomBadge("需要更新", RomTone.Error)
        is RomVersionDecision.Unavailable -> RomBadge("ROM 缺失", RomTone.Error)
        RomVersionDecision.UpToDate -> null
    }
}