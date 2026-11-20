package io.a10vm.records

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 记录导出：打包成 zip、落到用户能找到的地方、并且可以分享出去。
 *
 * 落地位置：
 * - Android 10+：`Download/VerseBox/records/`（MediaStore，免权限，用户能在文件管理器里看到）；
 * - Android 9：`Download/VerseBox/records/`（直接写公共目录，需要存储权限时由调用方先申请）；
 * - 两者都失败：退到应用自己的外部目录 `Android/data/<pkg>/files/records/`，至少不丢。
 */
object RecordExporter {
    private const val TAG = "A10VM_RECORDS"
    private const val RELATIVE_DIR = "VerseBox/records"

    data class Exported(val uri: Uri, val displayPath: String, val bytes: Long)

    sealed interface Result {
        data class Done(val exported: Exported) : Result
        data class Failed(val message: String) : Result
    }



    // ------------------------------------------------------------------ zip

    /** 导出文件名：给系统文件选择器当默认名。 */
    fun zipName(record: VmRecord): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(record.startedAt))
        return "versebox-${sanitize(record.vmName)}-$stamp-${record.kind.name.lowercase()}.zip"
    }

    /**
     * 打包好等用户挑位置：UI 拿 [Prepared.fileName] 去开系统文件选择器，
     * 选完把 uri 交给 [write]。整个过程不再往固定目录里写。
     */
    data class Prepared(val fileName: String, val zip: File)

    fun prepare(context: Context, record: VmRecord): Prepared? = runCatching {
        Prepared(zipName(record), buildZip(context, record))
    }.getOrNull()

    /** 写到系统文件选择器返回的 uri；无论成败都清掉缓存里的临时 zip。 */
    fun write(context: Context, uri: Uri, prepared: Prepared): Result = try {
        var bytes = 0L
        val output = context.contentResolver.openOutputStream(uri) ?: error("无法写入所选位置")
        output.use { out ->
            prepared.zip.inputStream().use { input -> bytes = input.copyTo(out) }
        }
        Result.Done(Exported(uri, uri.lastPathSegment ?: prepared.fileName, bytes))
    } catch (failure: Exception) {
        Log.w(TAG, "写入失败：${failure.message}")
        Result.Failed("写入失败：${failure.message}")
    } finally {
        prepared.zip.delete()
    }

    /** 把缓存里的 zip 交给系统分享面板（系统面板本身就是确认，不再自弹对话框）。 */
    fun sharePrepared(context: Context, prepared: Prepared, subject: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", prepared.zip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享运行记录").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun buildZip(context: Context, record: VmRecord): File {
        val file = File(context.cacheDir, "export/${zipName(record)}").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("summary.txt"))
            zip.write(report(context, record).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val root = record.logDir?.let { File(context.filesDir, it) }
            if (root != null && root.isDirectory) {
                root.walkTopDown().filter { it.isFile }.forEach { source ->
                    val entryName = "logs/" + source.relativeTo(root).path.replace('\\', '/')
                    zip.putNextEntry(ZipEntry(entryName))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return file
    }

    /** 人看的概览：先给结论，再给环境，最后给时间线。 */
    private fun report(context: Context, record: VmRecord): String = buildString {
        appendLine("VerseBox 运行记录")
        appendLine("================")
        appendLine("虚拟机      ：${record.vmName}（${record.vmId}）")
        appendLine("系统        ：${record.romName ?: record.romId} · Android ${record.androidVersion ?: "?"} · API ${record.apiLevel}")
        appendLine("事件        ：${record.kind.label}")
        appendLine("结果        ：${record.status.label}")
        appendLine("结论        ：${record.headline}")
        appendLine("开始时间    ：${formatTime(record.startedAt)}")
        record.finishedAt?.let { appendLine("结束时间    ：${formatTime(it)}") }
        appendLine("耗时        ：${record.durationText}")
        appendLine("事件条数    ：${record.eventCount}")
        appendLine("应用        ：${record.appVersion}")
        appendLine("宿主        ：${record.hostModel} · ${record.hostRelease}")
        val timeline = LogDigest.parse(RecordArchive.startupLog(context, record))
        if (timeline.isNotEmpty()) {
            appendLine()
            appendLine("关键节点")
            appendLine("--------")
            LogDigest.milestones(timeline).forEach { appendLine(it) }
            appendLine()
            appendLine("最后一条")
            appendLine("--------")
            appendLine(timeline.last().text)
        }
        val failure = record.failure
        if (!failure.isNullOrBlank()) {
            appendLine()
            appendLine("失败原因")
            appendLine("--------")
            appendLine(failure)
        }
    }

    private fun formatTime(millis: Long) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(32)


}
