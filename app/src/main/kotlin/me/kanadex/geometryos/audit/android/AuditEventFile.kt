// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.audit.android

import android.util.Log
import me.kanadex.geometryos.audit.json.AuditEventJson
import me.kanadex.geometryos.audit.model.AuditEvent
import me.kanadex.geometryos.audit.port.AuditClock
import me.kanadex.geometryos.audit.port.AuditEventSink
import me.kanadex.geometryos.audit.port.AuditFileSystem
import java.io.File

/**
 * 审计事件落盘：`files/audit/audit.log`，NDJSON 一行一条（docs/log-audit/02 §4）。
 *
 * 有上限（默认 1 MB，超出保留尾部）—— 审计日志自己也不能成为下一个无界增长点。
 * 写失败只打 logcat：审计写不进去不能反过来把清理搅黄，但**绝不**因此跳过清理事件的记录。
 */
class AuditEventFile(
    private val fs: AuditFileSystem,
    private val clock: AuditClock,
    private val path: String,
    private val capBytes: () -> Long,
) : AuditEventSink {
    override fun emit(event: AuditEvent) {
        runCatching {
            fs.appendText(path, AuditEventJson.encode(event) + "\n")
            val cap = capBytes()
            if (cap > 0 && fs.size(path) > cap) {
                // 保留尾部：截断点可能落在半行，读的时候按行解析，坏行直接跳过。
                fs.truncateToTail(path, cap)
            }
        }.onFailure { Log.w(TAG, "审计事件写入失败（${event.kind}）：${it.message}") }
    }

    /** 审计页/导出用：读最近这些字节的事件原文。 */
    fun tail(maxBytes: Long): String {
        val file = File(path)
        if (!file.isFile) return ""
        val length = file.length()
        if (length <= maxBytes) return fs.readText(path, length)
        // 从文件头读 maxBytes 会拿到最旧的部分；这里按"保留尾部"的语义读末段。
        val temp = File(file.parentFile, file.name + ".read-tmp")
        return runCatching {
            fs.copyTail(path, temp.absolutePath, maxBytes)
            fs.readText(temp.absolutePath, maxBytes)
        }.getOrElse { "" }.also { temp.delete() }
    }

    private companion object {
        const val TAG = "A10VM_AUDIT"
    }
}
