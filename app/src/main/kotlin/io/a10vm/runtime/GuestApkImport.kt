package io.a10vm.runtime

import android.content.Context
import android.net.Uri
import java.io.File
import org.json.JSONArray

internal class GuestApkImport(private val context: Context) {

    /**
     * 一次「批量导入应用」的结果。
     *
     * [imported] / [total] 是**记录状态**的唯一依据：以前只回一段文字，于是
     * 「0/2 个导入成功」也被收口成 SUCCESS（真机上就是这么骗过记录中心的）。
     * [failure] 只在确实有失败时非空，且第一行就是结论，供记录中心当 failure 落库。
     */
    data class Result(
        val text: String,
        val imported: Int,
        val total: Int,
        val failure: String? = null,
    )

    fun importInstalled(
        packages: JSONArray,
        session: NativeGraphicsSession,
        deviceName: String,
        progress: (String) -> Unit,
    ): Result {
        val names = (0 until packages.length()).map { packages.getString(it) }.distinct()
        require(names.isNotEmpty()) { "请先选择应用" }
        val rom = session.rom
        val results = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var installed = 0
        // 暂存目录必须在这台虚拟机自己的状态根下：GuestPaths 只放行 A10VM_HOST_ROOT 与
        // A10VM_STATE_ROOT 之下的宿主路径，放到 filesDir 根上多开时客体读不到。
        val directory = session.importsDirectory()
        for ((index, name) in names.withIndex()) {
            var label = name
            val temporary = java.nio.file.Files.createTempDirectory(directory.toPath(), "app-").toFile()
            try {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getApplicationInfo(name, 0)
                label = info.loadLabel(context.packageManager).toString()
                progress("正在将 ${index + 1}/${names.size} 导入 $deviceName：$label")
                require(info.minSdkVersion <= rom.apiLevel) {
                    "此应用要求 API ${info.minSdkVersion}，${rom.name} 只支持到 API ${rom.apiLevel}"
                }
                val sources = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty().toList()
                val apks = sources.mapIndexed { part, path ->
                    File(path).copyTo(File(temporary, if (part == 0) "base.apk" else "split-$part.apk"))
                }
                session.installApks(apks)
                installed++
                results += "$label：已导入"
            } catch (failure: Exception) {
                android.util.Log.w("A10VM_NATIVE", "Import $name", failure)
                val message = failure.message.orEmpty()
                val reason = Regex("Failure \\[([^]]+)]").find(message)?.groupValues?.get(1)
                    ?: message.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(180)
                results += "$label：$reason"
                failures += "$label（$reason）"
            } finally { temporary.deleteRecursively() }
        }
        val text = "已向 $deviceName（${rom.name}）导入 $installed/${names.size} 个应用，" +
            "可在它的桌面打开。\n${results.joinToString("\n")}"
        val failure = if (failures.isEmpty()) {
            null
        } else {
            "$installed/${names.size} 个应用导入成功，未导入：${failures.joinToString("；")}"
        }
        return Result(text = text, imported = installed, total = names.size, failure = failure)
    }

    fun install(uri: Uri, session: NativeGraphicsSession, deviceName: String): String {
        val rom = session.rom
        val directory = session.importsDirectory()
        val apk = File.createTempFile("import-", ".apk", directory)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "读取 APK 失败" }
                apk.outputStream().use { output -> input.copyTo(output) }
            }
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            requireNotNull(info) { "请选择完整 APK 文件" }
            val output = session.installApk(apk)
            return "${info.packageName} 已安装到 $deviceName（${rom.name}），进入桌面应用抽屉即可打开。\n$output"
        } finally {
            apk.delete()
        }
    }
}
