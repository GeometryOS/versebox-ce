// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.a10vm.records.RecordExporter
import io.a10vm.records.RecordStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「分享日志快照」的数据层（`docs/ui-rework/09/03` §3）。
 *
 * 抓四段内容打包成 zip 放 `cacheDir/snapshots/`，再交给系统分享面板：
 *
 * | 片段 | 取多少 |
 * | --- | --- |
 * | `startup-events.log` | 尾部 200 行 |
 * | `session-state.log` | 全文 |
 * | `framework-server.log` | 尾部 400 行 |
 * | `audit/audit.log` | 尾部 20 行 |
 *
 * 全部在 IO 线程上跑（`docs/ui-rework/05` §6）：读文件、压缩、写盘一个都不许落在组合线程。
 * 页面/状态层只拿 [Prepared]，拿到之后才回主线程起分享面板。
 */
object BootSnapshot {
    /** 每段取多少行；null = 全文。 */
    private val TAILS = listOf(
        "startup-events.log" to 200,
        "session-state.log" to null,
        "framework-server.log" to 400,
    )

    /** 审计流水单独从 files/audit 拿。 */
    private const val AUDIT_LOG = "audit.log"
    private const val AUDIT_TAIL = 20

    /** 打包好的快照：文件名、zip、给人看的一句话摘要。 */
    data class Prepared(val fileName: String, val zip: File, val summary: String)

    /**
     * 要分享的东西：**优先复用记录中心的归档**，只有确实没有归档时才自己抓一份实时快照。
     *
     * 为什么不再自己造一份包：记录中心的 `RecordExporter` 已经把该有的日志（含 summary.txt 与
     * 归档清单）打成同一个 zip 了。两条打包路径意味着「同一个动作两份实现」，早晚只维护一份
     * （`docs/ui-rework/00` §1.1：同一语义第二套写法默认判错）。
     * 实时快照只在「这一轮还没有归档」时兜底（例如启动阶段就失败、记录还没收尾）。
     */
    sealed interface Payload {
        data class Archived(val prepared: RecordExporter.Prepared, val subject: String) : Payload
        data class Live(val prepared: Prepared) : Payload
    }

    /**
     * 决定分享什么。**必须在 IO 线程调用**。
     *
     * @param recordId 本次运行在记录中心的行号（`BootUiState.recordId`）；null = 还没有记录
     */
    fun preparePayload(context: Context, vmId: String, vmName: String, recordId: Long?): Payload? {
        if (recordId != null) {
            val record = runCatching { RecordStore.get(context).find(recordId) }.getOrNull()
            if (record != null) {
                val prepared = runCatching { RecordExporter.prepare(context, record) }.getOrNull()
                if (prepared != null) {
                    return Payload.Archived(prepared, "VerseBox 运行记录 · " + vmName)
                }
            }
        }
        val live = prepare(context, vmId, vmName) ?: return null
        return Payload.Live(live)
    }

    /** 起分享面板。**必须在主线程调用**。 */
    fun share(context: Context, payload: Payload) {
        when (payload) {
            is Payload.Archived ->
                RecordExporter.sharePrepared(context, payload.prepared, payload.subject)

            is Payload.Live -> shareLive(context, payload.prepared)
        }
    }

    /**
     * 打包快照。**必须在 IO 线程调用**。
     *
     * @return null = 连一段日志都没找到（此时不该假装分享成功）
     */
    fun prepare(context: Context, vmId: String, vmName: String): Prepared? {
        val devices = io.a10vm.runtime.VmDevices(context)
        val device = runCatching { devices.find(vmId) }.getOrNull()
        val vmRoot = device?.let { runCatching { devices.rootOf(it) }.getOrNull() }
        val entries = mutableListOf<Pair<String, String>>()

        if (vmRoot != null && vmRoot.isDirectory) {
            for ((name, tail) in TAILS) {
                // 目录布局会随实例版本变（有的在 vm/ 下，有的在窗口目录），所以按文件名递归找一次。
                val file = sequenceOf(vmRoot).flatMap { it.walkTopDown() }
                    .firstOrNull { it.isFile && it.name == name } ?: continue
                entries += name to readTail(file, tail)
            }
        }
        val audit = File(File(context.filesDir, "audit"), AUDIT_LOG)
        if (audit.isFile) entries += AUDIT_LOG to readTail(audit, AUDIT_TAIL)

        if (entries.isEmpty()) return null

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeId = vmId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "vm" }
        val dir = File(context.cacheDir, "snapshots").apply { mkdirs() }
        val zip = File(dir, "versebox-" + safeId + "-" + stamp + ".zip").apply {
            if (exists()) delete()
        }
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        val summary = "VerseBox 运行日志快照：" + vmName + "（" + vmId + "）" +
            " · " + stamp + " · " + entries.joinToString(" / ") { it.first }
        return Prepared(zip.name, zip, summary)
    }

    /**
     * 起系统分享面板。**必须在主线程调用**（startActivity）。
     *
     * 系统面板本身就是确认，所以这里不再套一层「确定要分享吗」（`docs/ui-rework/04` §4 第 2 条）。
     */
    private fun shareLive(context: Context, prepared: Prepared) {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", prepared.zip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, BootTexts.SNAPSHOT_SUBJECT)
            putExtra(Intent.EXTRA_TEXT, prepared.summary)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, BootTexts.SNAPSHOT_SUBJECT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 读尾部 [tail] 行；[tail] 为 null 读全文。日志可能很大，所以按行流式读到环形缓冲。 */
    private fun readTail(file: File, tail: Int?): String = runCatching {
        if (tail == null) return@runCatching file.readText()
        val buffer = ArrayDeque<String>()
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                buffer.addLast(line)
                while (buffer.size > tail) buffer.removeFirst()
            }
        }
        buffer.joinToString("\n")
    }.getOrElse { "读取失败：" + it.message }
}
