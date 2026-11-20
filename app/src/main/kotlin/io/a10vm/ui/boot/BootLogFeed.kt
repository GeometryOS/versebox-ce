// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

/**
 * 开机日志的**去重与节奏数值**（`_bin/old-ui-src/.../BootTerminalView.kt` 的原始数值）。
 *
 * 运行时每次都会把整段 `bootLines` 发过来（可能是累计，也可能只是尾部），所以必须按内容去重；
 * 真正的队列、行数上限、滚动动画都在 `BootLogTerminalView` 里（与旧实现逐行一致）。
 */
internal object BootLogFeed {
    /** 已见行的记忆上限：启动日志单轮最多几百行。 */
    private const val SeenLimit = 512

    /**
     * 从一段（可能累计、可能只有尾部的）原始行里挑出**没见过的**。
     *
     * @return 顺序保持原序，只含新增；没有新增返回空列表。
     */
    fun fresh(lines: List<String>, seen: LinkedHashSet<String>): List<String> {
        val result = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line.isBlank()) continue
            if (!seen.add(line)) continue
            if (seen.size > SeenLimit) {
                val oldest = seen.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
            result += line
        }
        return result
    }

    fun clear(seen: LinkedHashSet<String>) = seen.clear()
}
