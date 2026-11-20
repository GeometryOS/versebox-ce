import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

// 解包布局版本。改动解包规则时递增，旧的 runtime-inputs 会自动重建。
val bootstrapLayout = 2

fun sha256Hex(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

// 构建链版本策略见 docs/ui-rework/01-构建基座/01-版本矩阵.md：
// AGP 9.x 自带的 Kotlin 支持取代了 org.jetbrains.kotlin.android 插件，
// 因此这里只声明 AGP 与 Compose 编译器插件，不再声明 kotlin-android。
plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.compose.compiler) apply false
    // 路由必须是 @Serializable：miuix-nav 的返回栈 Saver 会把它序列化成 JSON（InstallerX 同款）
    alias(libs.plugins.kotlin.serialization) apply false
}

// ---------------------------------------------------------------- 依赖仓库
// 项目级仓库：不依赖机器上的 init.gradle / 镜像脚本，换台机器同样可用。
// miuix 的 SNAPSHOT 只在 GitHub Packages，凭据来自 gpr.user/gpr.key（~/.gradle/gradle.properties）
// 或环境变量 GITHUB_ACTOR/GITHUB_TOKEN（需 read:packages）。没有凭据时自动回落到 Maven Central 的 0.9.4。
val gprUser: String? = (findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
val gprKey: String? = (findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
val hasGpr = !gprUser.isNullOrBlank() && !gprKey.isNullOrBlank()

// 干净检出时还没有 runtime-inputs/。这个任务从参考 APK 里解出已经审计过的
// Android 10 运行库输入，供 :guest-runtime 和 :native-display 直接消费。
// 首次约解包 1.5 GB，之后靠标记文件跳过；CI/Termux 可以用
// -PguestRuntimePrepared=... 和 -PnativeDisplayPrepared=... 完全绕过它。
val bootstrapBundledRuntime = tasks.register("bootstrapBundledRuntime") {
    group = "build"
    description = "从参考 APK 解包 Android 10 ROM 运行库输入（一次性）"
    val apk = providers.gradleProperty("baseApk")
        .map { rootProject.file(it) }
        .getOrElse(rootProject.file("_bin/A10VM.apk"))
    val destination = layout.projectDirectory.dir("runtime-inputs").asFile
    val marker = File(destination, ".bootstrap.json")
    outputs.upToDateWhen { false }
    doLast {
        if (!apk.isFile) {
            // 干净检出（_bin/ 不入库）+ -PciSkipNativeVerify=true：这是「只编代码」的合法组合，
            // 不能硬失败，否则逃生口被 native-display / guest-runtime 的 dependsOn 绕过。
            if (providers.gradleProperty("ciSkipNativeVerify").isPresent) {
                logger.lifecycle(
                    "bootstrapBundledRuntime: 已跳过（-PciSkipNativeVerify=true，且没有参考 APK " +
                        "${apk.absolutePath}）。runtime-inputs 不会被生成。",
                )
                return@doLast
            }
            throw GradleException(
                "缺少参考 APK：${apk.absolutePath}\n" +
                    "该 APK 只用来提供已审计的 ROM 运行库输入。三条路，任选一条：\n" +
                    "  1) 把参考 APK 放回 _bin/A10VM.apk（本机已有，文件名见 README）；\n" +
                    "  2) 用 -PbaseApk=/path/to/A10VM.apk 指定一个外部路径；\n" +
                    "  3) 用 -PguestRuntimePrepared=<prepared-apk 目录> 直接给已解包的运行库；\n" +
                    "  只想编代码 / 看 UI、不需要能跑 A10 时，加 -PciSkipNativeVerify=true 跳过本步。\n" +
                    "完整 ROM 构建流程见 docs/zh-CN/04-从零构建与移植教程.md。"
            )
        }
        val stamp = "{\"layout\":" + bootstrapLayout + ",\"apk\":\"" +
            apk.absolutePath.replace("\\", "\\\\") +
            "\",\"size\":" + apk.length() + ",\"mtime\":" + apk.lastModified() + "}"
        val complete = File(destination, "guest-runtime/assets/guest-runtime-inputs.json").isFile &&
            File(destination, "guest-runtime/jniLibs/arm64-v8a").isDirectory &&
            File(destination, "native-display/assets/native-display-inputs.json").isFile &&
            File(destination, "native-display/jniLibs/arm64-v8a").isDirectory
        if (complete && marker.isFile && marker.readText() == stamp) {
            logger.lifecycle("ROM 运行库输入已就绪：${destination.absolutePath}")
            return@doLast
        }
        logger.lifecycle("正在从 ${apk.name} 解包 ROM 运行库输入（首次约 1.5 GB，请耐心等待）…")
        File(destination, "guest-runtime").deleteRecursively()
        File(destination, "native-display").deleteRecursively()
        val displayLibraries = setOf("liba10vm-desktop-window.so", "liba10vm-p1-client.so")
        var files = 0
        var bytes = 0L
        var manifestText: String? = null
        // Windows/NTFS 不区分大小写，而 ROM 里可能存在 Arcturus.ogg 与 arcturus.ogg
        // 这种只差大小写的文件。把它们改名成哈希资源，运行时再按 installPath 还原。
        val renamedAssets = HashMap<String, String>()
        ZipFile(apk).use { zip ->
            val manifestEntry = zip.getEntry("assets/guest-runtime-inputs.json")
                ?: throw GradleException("参考 APK 缺少 assets/guest-runtime-inputs.json")
            manifestText = zip.getInputStream(manifestEntry).readBytes().toString(Charsets.UTF_8)
            @Suppress("UNCHECKED_CAST")
            val manifest = JsonSlurper().parseText(manifestText) as MutableMap<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val data = manifest["data"] as? MutableList<MutableMap<String, Any?>>
                ?: throw GradleException("参考 APK 的运行库清单缺少 data 数组")
            val groups = data.groupBy { (it["name"] as String).lowercase() }
            for (group in groups.values) {
                if (group.map { it["name"] as String }.toSet().size < 2) continue
                for (item in group) {
                    val original = item["name"] as String
                    val encoded = "guest-runtime/rom-encoded/" +
                        sha256Hex(original.toByteArray(Charsets.UTF_8)) + ".asset"
                    item["installPath"] = original.removePrefix("guest-runtime/")
                    item["name"] = encoded
                    renamedAssets[original] = encoded
                }
            }
            manifestText = JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n"
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                val target: File? = when {
                    // 清单最后单独写入修改后的版本。
                    name == "assets/guest-runtime-inputs.json" -> null
                    name == "assets/native-display-inputs.json" ->
                        File(destination, "native-display/assets/native-display-inputs.json")
                    name.startsWith("assets/") -> {
                        val relative = name.removePrefix("assets/")
                        File(destination, "guest-runtime/assets/" + (renamedAssets[relative] ?: relative))
                    }
                    name.startsWith("lib/arm64-v8a/") -> {
                        val library = name.substringAfterLast('/')
                        if (library in displayLibraries) {
                            File(destination, "native-display/jniLibs/arm64-v8a/$library")
                        } else {
                            File(destination, "guest-runtime/jniLibs/arm64-v8a/$library")
                        }
                    }
                    else -> null
                }
                if (target == null) continue
                val canonical = target.canonicalFile
                if (!canonical.toPath().startsWith(destination.canonicalFile.toPath())) {
                    throw GradleException("参考 APK 含非法路径：$name")
                }
                canonical.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    canonical.outputStream().buffered(1 shl 20).use { input.copyTo(it) }
                }
                files++
                bytes += entry.size
            }
        }
        File(destination, "guest-runtime/assets/guest-runtime-inputs.json")
            .writeText(requireNotNull(manifestText), Charsets.UTF_8)
        marker.parentFile.mkdirs()
        marker.writeText(stamp)
        logger.lifecycle("ROM 运行库输入完成：$files 个文件，${bytes / (1024 * 1024)} MB")
    }
}

tasks.register("cleanRuntimeInputs") {
    group = "build"
    description = "删除解包出来的 runtime-inputs（下次构建会重新生成）"
    doLast { layout.projectDirectory.dir("runtime-inputs").asFile.deleteRecursively() }
}
