import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // 开放源代码许可页
    alias(libs.plugins.aboutlibraries)
}

val miuiTrial = providers.gradleProperty("guestRuntimeProfile").orNull == "miui12"
val gprUser: String? = (findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
val gprKey: String? = (findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
val miuixVersion: String = (findProperty("miuixVersion") as String?)
    ?: libs.versions.miuix.get()

android {
    namespace = "me.kanadex.versebox"


    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        applicationId = if (miuiTrial) "io.a10vm.miui" else "me.kanadex.versebox"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // 新格式在 ROM 的 payload/lib）
        versionCode = if (miuiTrial) 98 else 105
        versionName = if (miuiTrial) "0.2.88-miui12-android9-aosp-ime" else "0.1.5-romlib-bootlog"
        resValue("string", "app_name", if (miuiTrial) "A10VM MIUI12" else "Versebox")
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        // 客体运行库要按原文件名解压到 nativeLibraryDir
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/*.so"
        jniLibs.excludes += "**/liba10vm-p1-window.so"
    }

    androidResources {
        // 内置 ROM 的 zip 已经是 deflate 过的，再压一次只会把构建时间翻倍、体积不变。
        noCompress += listOf("zip")
    }
    // jniLibs 与 assets 由 :native-display 和 :guest-runtime 两个库模块合并进来，
    // 这里不再指向任何手工暂存目录。
}

val miuixFromLocalRepo = miuixVersion == "0.9.4-292d8ec6"
if (!miuixVersion.endsWith("-SNAPSHOT") && !miuixFromLocalRepo) {
    throw GradleException(
        "miuix $miuixVersion 是 Maven Central 正式版，它编在 material-color-utilities 5.0.1 上，" +
            "与本工程的 material-kolor 6.0.0 / Cmf 调色板 / Spec 2026 不兼容：" +
            "① ThemePaletteStyle 没有 Cmf、ThemeColorSpec 没有 Spec2026、" +
            "ColorSpec.SpecVersion 没有 SPEC_2026，编译期就过不去；" +
            "② 把 utilities force 回 5.0.1 会反过来打断 material-kolor 6.0.0，" +
            "启动即 NoClassDefFoundError（DynamicScheme\$Platform 从 scheme 包搬到了 dynamiccolor 包）。" +
            "请改用与 InstallerX 对齐的 SNAPSHOT：gradle/libs.versions.toml 里 " +
            "miuix = \"0.9.4-292d8ec6-SNAPSHOT\"；凭据放 ~/.gradle/gradle.properties 的 " +
            "gpr.user / gpr.key（或环境变量 GITHUB_ACTOR / GITHUB_TOKEN，需 read:packages）。"
    )
}

/*
 * 开放源代码许可：把传递依赖合并成一份清单（InstallerX 的 aboutLibraries 配置同款）。
 * 生成的 com.mikepenz.aboutlibraries.Libs 由 AboutPage / 许可页直接渲染。
 */
aboutLibraries {
    library {
        duplicationMode = DuplicateMode.MERGE
        duplicationRule = DuplicateRule.SIMPLE
    }
}

dependencies {
    implementation(project(":native-display"))
    implementation(project(":guest-runtime"))
    // Android 12+ phantom process 限制检测用
    implementation(project(":phantom-probe"))
    // 日志审计策略库
    implementation(project(":log-audit"))
    // 显示设置策略库
    implementation(project(":vm-display"))
    // ROM 契约与版本管理策略库
    implementation(project(":vm-rom"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Compose：全部显式版本
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // MIUI 风格组件库
    implementation("top.yukonga.miuix.kmp:miuix-core-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-shader-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:$miuixVersion")

    // 主题换色
    implementation(libs.material.kolor)

    // 路由返回栈的 JSON 序列化
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // 关于页 / 开放源代码许可页
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
}



data class RomEmbedSource(
    val name: String,
    val release: String?,
    val dir: String?,
    val zip: String?,
    val only: List<String>,
    val mode: String,
    val residentLibs: String?,
    val legacyPreparedApk: String?,
)

val romEmbedConfigFile: File = run {
    val explicit = (findProperty("verseboxRomEmbed") as String?)?.let { rootProject.file(it) }
    val local = project.file("rom-embed.local.json")
    when {
        explicit != null -> explicit
        local.isFile -> local
        else -> project.file("rom-embed.json")
    }
}

val romEmbedAssetDir = "kanadex-rom"
val romDistDir = layout.buildDirectory.dir("rom-dist").get().asFile
val romDistRoms = File(romDistDir, "roms")
val romDistMin = File(romDistDir, "min")
val romDistLibs = File(romDistDir, "apk-libs/arm64-v8a")
val romAssetsRoot = layout.buildDirectory.dir("generated/rom-assets").get().asFile
val romEmbedStagingDir = File(romAssetsRoot, romEmbedAssetDir)
// APK 的 lib/
val romDistJniRoot = File(romDistDir, "apk-libs")
// 旧格式（prepared-apk）如果被登记，它的 assets 在这里暂存；默认不产生任何文件。
val romLegacyAssetsRoot = layout.buildDirectory.dir("generated/rom-legacy-assets").get().asFile
val romEmbedCacheDir = rootProject.layout.projectDirectory.dir("_rom_cache").asFile
val romAssetsStrict = (findProperty("verseboxRomStrict") as String?)?.toBoolean() ?: true

/** 配置示例：构建时生成 */
val romEmbedSample = """
{
  "schema": "versebox-rom-embed/2",
  "assetDir": "kanadex-rom",
  "about": [
    "这个文件决定「这次打出来的 APK 里内置了哪些 ROM」。",
    "按文件计，不按安卓版本计：每条 source 只描述一个 ROM 的发布产物在哪。",
    "release: GitHub 仓库（拉最近一次 Release 的 versebox-*.zip / apk-resident-libs-*.tar）",
    "dir:     本地发布产物目录（相对仓库根或绝对路径；同卷硬链接，不复制）",
    "zip:     单个包（调试用）",
    "only:    只取这些文件名（可省略）",
    "mode:    full（默认，完整包）/ minimal（只带占位包）/ libs（只带 APK 必备 .so）"
  ],
  "sources": [
    { "release": "https://github.com/GeometryOS/VerseBox-guest-runtime-a15" },
    { "release": "https://github.com/GeometryOS/VerseBox-guest-runtime-a10" },
    { "release": "https://github.com/GeometryOS/VerseBox-guest-runtime-a9" }
  ]
}
""".trimIndent() + "\n"

/** 读配置。 */
fun readRomEmbedSources(): List<RomEmbedSource> {
    if (!romEmbedConfigFile.isFile) {
        val sample = File(romDistDir, "rom-embed.sample.json")
        sample.parentFile.mkdirs()
        sample.writeText(romEmbedSample, Charsets.UTF_8)
        logger.lifecycle("rom-embed: 没有配置文件（${romEmbedConfigFile.name}）→ APK 不内置任何 ROM；示例已写到 ${sample.path}")
        return emptyList()
    }
    val parsed = runCatching { JsonSlurper().parseText(romEmbedConfigFile.readText(Charsets.UTF_8)) }.getOrElse {
        logger.lifecycle("rom-embed: 配置不是合法 JSON（${romEmbedConfigFile.name}）→ 不内置任何 ROM")
        return emptyList()
    }
    @Suppress("UNCHECKED_CAST")
    val root = parsed as? Map<String, Any?> ?: return emptyList()
    @Suppress("UNCHECKED_CAST")
    val sources = root["sources"] as? List<Map<String, Any?>> ?: return emptyList()
    return sources.mapIndexed { index, item ->
        val release = (item["release"] as? String)?.takeIf { it.isNotBlank() }
        val dir = ((item["dir"] ?: item["localDir"]) as? String)?.takeIf { it.isNotBlank() }
        val single = (item["zip"] as? String)?.takeIf { it.isNotBlank() }
        RomEmbedSource(
            // 名字只用于日志、缓存目录与 tar 文件名；省略时从来源推出来，不要求用户起名。
            name = (item["name"] as? String)?.takeIf { it.isNotBlank() }
                ?: dir?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')
                ?: release?.trimEnd('/')?.substringAfterLast('/')
                ?: single?.substringAfterLast('/')?.substringBeforeLast('.')
                ?: "rom-${index}",
            release = release,
            dir = dir,
            zip = single,
            only = (item["only"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            mode = ((item["mode"] as? String) ?: "full").lowercase().let {
                if (it in setOf("full", "minimal", "libs")) it else "full"
            },
            residentLibs = (item["residentLibs"] as? String)?.takeIf { it.isNotBlank() },
            legacyPreparedApk = (item["legacyPreparedApk"] as? String)?.takeIf { it.isNotBlank() },
        )
    }
}

fun resolveRomSourceDir(source: RomEmbedSource): File? {
    source.zip?.let { raw ->
        val file = if (File(raw).isAbsolute) File(raw) else rootProject.file(raw)
        if (!file.isFile) {
            logger.lifecycle("rom-embed: 跳过 ${source.name}：包不存在 ${file.path}")
            return null
        }
        return file.parentFile
    }
    source.dir?.let { raw ->
        val dir = if (File(raw).isAbsolute) File(raw) else rootProject.file(raw)
        if (!dir.isDirectory) {
            logger.lifecycle("rom-embed: 跳过 ${source.name}：目录不存在 ${dir.path}")
            return null
        }
        return dir
    }
    val release = source.release ?: run {
        logger.lifecycle("rom-embed: 跳过 ${source.name}：既没有 release / dir 也没有 zip")
        return null
    }
    return runCatching { downloadRelease(source.name, release) }.getOrElse { failure ->
        // 拉取失败就是跳过（docs/rom-release/01 §4）——不把"没网"变成"构建失败"。
        logger.lifecycle("rom-embed: 跳过 ${source.name}：拉取 ${release} 失败（${failure.message}）")
        null
    }
}

/**
 * 从 GitHub Release 拉 ROM 产物到 <工作区>/_rom_cache/<name>/：
 * download/ 放原始下载，build/ 放"处理过、要进 APK"的那一份。
 */
fun downloadRelease(name: String, releaseUrl: String): File? {
    val base = releaseUrl.trimEnd('/')
    val slug = base.substringAfterLast('/')
    val owner = base.substringBeforeLast('/').substringAfterLast('/')
    val download = File(romEmbedCacheDir, name + "/download").apply { mkdirs() }
    val built = File(romEmbedCacheDir, name + "/build").apply { mkdirs() }
    val api = URI("https://api.github.com/repos/${owner}/${slug}/releases/latest").toURL()
    val json = api.openConnection().let { connection ->
        (connection as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
    @Suppress("UNCHECKED_CAST")
    val release = JsonSlurper().parseText(json) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val assets = release["assets"] as? List<Map<String, Any?>> ?: emptyList()
    val wanted = assets.filter { asset ->
        val assetName = (asset["name"] as? String).orEmpty()
        assetName.endsWith(".zip") || assetName.endsWith(".tar")
    }
    if (wanted.isEmpty()) {
        logger.lifecycle("rom-embed: ${name} 的 Release 里没有任何 ROM 产物")
        return null
    }
    for (asset in wanted) {
        val assetName = asset["name"] as String
        val target = File(download, assetName)
        if (!target.isFile || target.length() != (asset["size"] as? Number)?.toLong()) {
            val url = asset["browser_download_url"] as String
            logger.lifecycle("rom-embed: 下载 ${assetName} …")
            URI(url).toURL().openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (assetName.endsWith(".zip")) {
            linkOrCopy(target, File(built, assetName))
        } else if (assetName.endsWith(".tar")) {
            extractTar(target, built)
        }
    }
    return built
}

/** 同卷硬链接，跨卷才复制（避免为入库再复制一份 GB 级副本）。 */
fun linkOrCopy(source: File, target: File) {
    if (target.isFile && target.length() == source.length()) return
    target.parentFile?.mkdirs()
    if (target.exists()) target.delete()
    val linked = runCatching { Files.createLink(target.toPath(), source.toPath()) }
    if (linked.isFailure) source.copyTo(target, overwrite = true)
}

/**
 * 吸收一份 `apk-resident-libs-*.tar`：解到中间目录（`_rom_cache/<name>/build/libs`），
 * 再硬链接进 `rom-dist/apk-libs/arm64-v8a/`。
 *
 * 为什么要有中间目录：tar 是**分发格式**，jniLibs 是**打包格式**。中间目录把
 * "下载了什么"（download）和"处理成什么"（build）分开，出问题一眼看到是哪一步。
 */
fun absorbResidentTar(tarFile: File, name: String): List<String> {
    val staged = File(romEmbedCacheDir, name + "/build/libs").apply { mkdirs() }
    staged.listFiles().orEmpty().forEach { it.delete() }
    extractTar(tarFile, staged)
    val libs = staged.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".so") }.sortedBy { it.name }
    libs.forEach { linkOrCopy(it, File(romDistLibs, it.name)) }
    if (libs.isNotEmpty()) {
        logger.lifecycle("rom-embed: " + name + " 从 " + tarFile.name + " 取到 " + libs.size +
            " 个 APK 必备 .so：" + libs.joinToString(", ") { it.name })
    }
    return libs.map { it.name }
}

/** 把一棵目录硬链接到另一棵（旧格式 assets 用；同卷 0 额外字节）。 */
fun linkTree(source: File, target: File) {
    if (!source.isDirectory) return
    source.listFiles().orEmpty().forEach { child ->
        val destination = File(target, child.name)
        if (child.isDirectory) {
            destination.mkdirs()
            linkTree(child, destination)
        } else {
            linkOrCopy(child, destination)
        }
    }
}

/**
 * 最小 ustar 读：只处理普通文件（apk-resident-libs-*.tar 由发布方生成，只有平铺文件）。
 *
 * 刻意不引入 commons-compress：这是构建脚本，不是产品代码，多一个依赖就多一次解析失败的可能。
 */
fun extractTar(tar: File, target: File) {
    target.mkdirs()
    tar.inputStream().buffered().use { input ->
        val header = ByteArray(512)
        while (true) {
            var read = 0
            while (read < 512) {
                val count = input.read(header, read, 512 - read)
                if (count < 0) break
                read += count
            }
            if (read < 512) break
            if (header.all { it.toInt() == 0 }) break
            val rawName = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000').trim()
            val sizeText = String(header, 124, 12, Charsets.US_ASCII).substringBefore('\u0000').trim()
            val size = sizeText.toLongOrNull(8) ?: 0L
            val type = header[156].toInt().toChar()
            if (rawName.isNotEmpty() && (type == '0' || type == '\u0000') && !rawName.endsWith("/")) {
                val out = File(target, rawName.substringAfterLast('/'))
                var remaining = size
                out.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val want = minOf(remaining, buffer.size.toLong()).toInt()
                        val count = input.read(buffer, 0, want)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            } else {
                var remaining = size
                while (remaining > 0) {
                    val skipped = input.skip(remaining)
                    if (skipped <= 0) break
                    remaining -= skipped
                }
            }
            val padding = (512 - (size % 512)) % 512
            var pad = padding
            while (pad > 0) {
                val skipped = input.skip(pad)
                if (skipped <= 0) break
                pad -= skipped
            }
        }
    }
}

/** 写一份 ustar（apk-libs-<name>.tar），只放平铺文件。 */
fun writeTar(files: List<File>, target: File) {
    target.parentFile?.mkdirs()
    target.outputStream().buffered().use { out ->
        val now = System.currentTimeMillis() / 1000
        for (file in files) {
            val name = file.name.toByteArray(Charsets.UTF_8)
            require(name.size <= 100) { "tar 文件名太长：${file.name}" }
            val header = ByteArray(512)
            System.arraycopy(name, 0, header, 0, name.size)
            fun putOctal(offset: Int, length: Int, value: Long) {
                val text = value.toString(8).padStart(length - 1, '0')
                val bytes = text.toByteArray(Charsets.US_ASCII)
                System.arraycopy(bytes, 0, header, offset, minOf(bytes.size, length - 1))
                header[offset + length - 1] = 0
            }
            putOctal(100, 8, 0b111_101_101L)     // mode 0755：客体 linker 要能 exec
            putOctal(108, 8, 0L)                 // uid
            putOctal(116, 8, 0L)                 // gid
            putOctal(124, 12, file.length())
            putOctal(136, 12, now)
            header[156] = '0'.code.toByte()      // typeflag = 普通文件
            val magic = "ustar".toByteArray(Charsets.US_ASCII)
            System.arraycopy(magic, 0, header, 257, magic.size)
            header[262] = 0
            header[263] = '0'.code.toByte()
            header[264] = '0'.code.toByte()
            // 校验和：算之前 chksum 字段按空格计
            for (index in 148 until 156) header[index] = ' '.code.toByte()
            var sum = 0L
            for (byte in header) sum += (byte.toInt() and 0xFF)
            val check = sum.toString(8).padStart(6, '0') + "\u0000 "
            System.arraycopy(check.toByteArray(Charsets.US_ASCII), 0, header, 148, 8)
            out.write(header)
            file.inputStream().use { input -> input.copyTo(out) }
            val padding = (512 - (file.length() % 512)) % 512
            if (padding > 0) out.write(ByteArray(padding.toInt()))
        }
        out.write(ByteArray(1024))               // 结束块：两个 512 全零
    }
}

/** 返回 (问题列表, 索引条目)。问题为空表示这个 zip 可以带进 APK。 */
fun romAssetReport(zipFile: File): Pair<List<String>, MutableMap<String, Any?>> {
    val problems = mutableListOf<String>()
    val index = mutableMapOf<String, Any?>()
    index["file"] = zipFile.name
    index["zipBytes"] = zipFile.length()
    val names: List<String>
    val propertiesText: String
    val manifestText: String?
    try {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList()
            names = entries.map { it.name }
            val properties = entries.firstOrNull { it.name == "versebox_properties.yaml" }
                ?: return listOf("缺少 versebox_properties.yaml") to index
            propertiesText = zip.getInputStream(properties).use { it.readBytes().toString(Charsets.UTF_8) }
            val manifestName = propertiesText.lineSequence()
                .map { it.substringBefore('#').trim() }
                .firstOrNull { it.startsWith("inputs:") }
                ?.substringAfter("inputs:")?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
                ?: "versebox_inputs.json"
            manifestText = entries.firstOrNull { it.name == manifestName }
                ?.let { zip.getInputStream(it).use { stream -> stream.readBytes().toString(Charsets.UTF_8) } }
        }
    } catch (failure: Exception) {
        return listOf("不是可读的 zip（" + (failure.message ?: failure.javaClass.simpleName) + "）") to index
    }

    val roots = names.map { it.substringBefore('/') }.toSet()
    val payloadMissing = names.none { it.startsWith("payload/") }
    if (!payloadMissing && roots != setOf("versebox_properties.yaml", "versebox_inputs.json", "payload")) {
        problems += "根条目不是恰好三项：" + roots.sorted()
    }
    val values = LinkedHashMap<String, String>()
    propertiesText.lineSequence().forEach { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@forEach
        val separator = line.indexOf(':')
        if (separator <= 0) return@forEach
        values[line.substring(0, separator).trim()] = line.substring(separator + 1).trim().trim('"').trim('\'')
    }
    val api = values["apiLevel"]?.toIntOrNull()
    for (field in listOf("id", "romVersion", "name", "androidVersion", "description", "inputs",
        "payloadBytes", "payloadSha256", "libMode", "packedBy", "packedAt")) {
        index[field] = values[field].orEmpty()
    }
    index["apiLevel"] = api ?: 0
    index["payloadBytes"] = values["payloadBytes"]?.toLongOrNull() ?: 0L
    val declaredPlaceholder = values["placeholder"].orEmpty().lowercase() in setOf("true", "1", "yes")
    index["placeholder"] = payloadMissing || declaredPlaceholder

    if (values["schema"] != "versebox-rom/2") problems += "schema 不是 versebox-rom/2"
    val id = values["id"].orEmpty()
    val version = values["romVersion"].orEmpty()
    if (!Regex("[a-z0-9][a-z0-9._-]*").matches(id)) problems += "id 不合法：" + id
    if (!Regex("[0-9]+(\\.[0-9]+)*").matches(version)) problems += "romVersion 不合法：" + version
    if (api == null || api !in 26..35) problems += "apiLevel 不合法：" + values["apiLevel"]
    if ((values["payloadBytes"]?.toLongOrNull() ?: 0L) < 0L) problems += "payloadBytes 不能是负数"
    val sha = values["payloadSha256"].orEmpty()
    if (sha.length != 64 || sha.any { it !in "0123456789abcdef" }) problems += "payloadSha256 不是 64 位十六进制"
    if ((values["payloadRoot"].orEmpty().trimEnd('/')) != "payload") problems += "payloadRoot 必须是 payload/"
    if (names.any { it.startsWith("/") || it.contains("..") }) problems += "包含绝对路径/上跳的条目"

    // 占位包（最小包）里**只有**自述：没有清单、没有 payload，这是它的定义，不是缺陷。
    if (manifestText == null) {
        if (!payloadMissing && !declaredPlaceholder) problems += "缺少 versebox_inputs.json"
    } else {
        // 用真解析，不用正则：清单里 data[].name 是 payload/ 下的路径，files[].name 是**逻辑库名**
        // （liba15vm-xxx.so），正则会把后者也当成路径，于是每个合法的 A15 包都会被判"不指向 payload/"。
        @Suppress("UNCHECKED_CAST")
        val manifest = runCatching { JsonSlurper().parseText(manifestText) as Map<String, Any?> }.getOrNull()
        val guestSdk = (manifest?.get("guestSdk") as? Number)?.toInt()
        if (guestSdk == null || guestSdk != api) {
            problems += "清单 guestSdk=" + guestSdk + " 与 apiLevel=" + api + " 不一致"
        }
        @Suppress("UNCHECKED_CAST")
        val data = manifest?.get("data") as? List<Map<String, Any?>>
        val dataNames = data?.mapNotNull { it["name"] as? String }.orEmpty()
        if (dataNames.isEmpty() && index["placeholder"] != true) problems += "清单里没有 data[].name"
        dataNames.firstOrNull { !it.startsWith("payload/") }?.let { problems += "清单条目不指向 payload/：" + it }
        @Suppress("UNCHECKED_CAST")
        val files = manifest?.get("files") as? List<Map<String, Any?>>
        val libPaths = files?.mapNotNull { it["libPath"] as? String }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        libPaths.firstOrNull { !it.startsWith("payload/") }?.let { problems += "清单 libPath 不在 payload/ 下：" + it }
        val present = names.toSet()
        (dataNames + libPaths).firstOrNull { !present.contains(it) }?.let {
            problems += "清单条目在 zip 里找不到：" + it
        }
    }
    return problems to index
}

/**
 * 从 ROM 包里抽出「必须留在 APK lib/ 的 .so」（清单 files[].resident == true）。
 *
 * 为什么从包里抽而不是再要一份目录：pack-rom.py 把**全部** jniLibs 都放进 payload/lib，
 * 并给被 execve 的那几个打上 resident: true（guest-runtime-* 仓库的 rom/resident-native.txt）。
 * 于是「哪几个 so 进 APK」只有一个答案来源——ROM 包自己。
 * 配置里不需要 residentLibs 路径，也就没有"本地手拼目录"那件事（docs/rom-release/01 §3）。
 */
fun extractResidentLibs(zipFile: File, outDir: File): List<String> {
    val written = mutableListOf<String>()
    ZipFile(zipFile).use { zip ->
        val properties = zip.getEntry("versebox_properties.yaml") ?: return emptyList()
        val propertiesText = zip.getInputStream(properties).use { it.readBytes().toString(Charsets.UTF_8) }
        val manifestName = propertiesText.lineSequence()
            .map { it.substringBefore('#').trim() }
            .firstOrNull { it.startsWith("inputs:") }
            ?.substringAfter("inputs:")?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
            ?: "versebox_inputs.json"
        val manifestEntry = zip.getEntry(manifestName) ?: return emptyList()
        val manifestText = zip.getInputStream(manifestEntry).use { it.readBytes().toString(Charsets.UTF_8) }
        @Suppress("UNCHECKED_CAST")
        val manifest = runCatching { JsonSlurper().parseText(manifestText) as Map<String, Any?> }.getOrNull()
            ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val files = manifest["files"] as? List<Map<String, Any?>> ?: return emptyList()
        outDir.mkdirs()
        for (item in files) {
            if ((item["resident"] as? Boolean) != true) continue
            val name = (item["name"] as? String)?.takeIf { it.isNotEmpty() } ?: continue
            val libPath = (item["libPath"] as? String)?.takeIf { it.isNotEmpty() }
            // 老布局（没有 libPath）说明这份包还是"so 在 APK 里"的老产物，抽不出来，跳过并说明。
            if (libPath == null) {
                logger.lifecycle("rom-embed: ${name} 标了 resident 但清单没有 libPath（老布局），跳过")
                continue
            }
            val entry = zip.getEntry(libPath) ?: run {
                logger.lifecycle("rom-embed: 包里找不到 resident 库 ${libPath}（${name}）")
                null
            } ?: continue
            val target = File(outDir, name)
            if (target.isFile && target.length() == entry.size) continue
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            written += name
        }
    }
    return written
}

/**
 * 生成**最小占位包**：根目录只有 versebox_properties.yaml，并把 placeholder 置为 true。
 *
 * payloadBytes 必须归零（L1 校验：非占位包要求 > 0），payloadSha256 保留原值——
 * 它表示"这一版应该是什么内容"，导入完整包之后两者指纹相同，天然被判成同一份内容，
 * 不会冒出"同版本不同内容"的假提示。
 */
fun writeMinimalRom(full: File, target: File): Boolean {
    ZipFile(full).use { zip ->
        val properties = zip.getEntry("versebox_properties.yaml") ?: return false
        val text = zip.getInputStream(properties).use { it.readBytes().toString(Charsets.UTF_8) }
        val kept = mutableListOf<String>()
        var sawPlaceholder = false
        for (line in text.lines()) {
            val key = line.substringBefore(':').trim()
            when (key) {
                "payloadBytes" -> kept += "payloadBytes: 0"
                "placeholder" -> {
                    kept += "placeholder: true"
                    sawPlaceholder = true
                }
                else -> kept += line
            }
        }
        if (!sawPlaceholder) {
            // 插在 payloadBytes 之后，保持自述的可读顺序（顺序对解析没有影响）。
            val at = kept.indexOfFirst { it.substringBefore(':').trim() == "payloadBytes" }
            if (at >= 0) kept.add(at + 1, "placeholder: true") else kept += "placeholder: true"
        }
        val header = listOf(
            "# 最小占位包（由 app 构建链生成，不是 ROM 发布的原件）：",
            "# 只用于让安装包的 ROM 列表里出现这一版；要真的开机必须导入完整包。",
        )
        target.parentFile?.mkdirs()
        val stream = ZipOutputStream(BufferedOutputStream(target.outputStream()))
        try {
            stream.putNextEntry(ZipEntry("versebox_properties.yaml"))
            stream.write((header + kept).joinToString("\n").toByteArray(Charsets.UTF_8))
            stream.closeEntry()
        } finally {
            stream.close()
        }
    }
    return true
}

val stageRomAssets = tasks.register("stageRomAssets") {
    group = "build"
    description = "按 rom-embed 配置把 ROM 包 / 最小占位包 / APK 必备 .so staging 到 build/rom-dist"
    // 没有配置文件是**合法状态**（零内置 ROM，见本节 T1 语义）。Gradle 对 inputs.file()
    // 的 optional() 仍会做存在性校验，所以这里干脆只在文件存在时声明输入。
    if (romEmbedConfigFile.isFile) inputs.file(romEmbedConfigFile)
    outputs.dir(romDistDir)
    outputs.dir(romAssetsRoot)
    outputs.upToDateWhen { false }
    doLast {
        logger.lifecycle("rom-embed: 配置 ${romEmbedConfigFile.path}")
        // 每次重建：产物目录与 assets staging 都必须和这一份配置一致，
        // 残留的旧 zip 会被打进 APK（上一次真机上出现过 1.0.0 与 1.1.0 同时存在）。
        romDistRoms.deleteRecursively()
        romDistMin.deleteRecursively()
        romDistLibs.deleteRecursively()
        romEmbedStagingDir.deleteRecursively()
        romLegacyAssetsRoot.deleteRecursively()
        romDistRoms.mkdirs()
        romDistMin.mkdirs()
        romDistLibs.mkdirs()
        romEmbedStagingDir.mkdirs()
        File(romDistDir, "rom-embed.sample.json").writeText(romEmbedSample, Charsets.UTF_8)

        val sources = readRomEmbedSources()
        val indexEntries = mutableListOf<MutableMap<String, Any?>>()
        val residentBySource = LinkedHashMap<String, List<String>>()
        var total = 0L

        for (source in sources) {
            if (source.legacyPreparedApk != null) {
                // 旧格式：把 prepared-apk 的 assets 与 jniLibs **硬链接**进暂存目录。
                // 这是配置驱动的一部分（docs/rom-release/01-入库配置/03-旧格式与开关.md）：
                // 指定本地目录，构建链自动引入，不需要谁去改模块的 Gradle。
                val legacy = if (File(source.legacyPreparedApk).isAbsolute) {
                    File(source.legacyPreparedApk)
                } else {
                    rootProject.file(source.legacyPreparedApk)
                }
                if (!legacy.isDirectory) {
                    logger.lifecycle("rom-embed: 跳过 ${source.name}：旧格式目录不存在 ${legacy.path}")
                    continue
                }
                val assets = legacy.resolve("assets")
                if (assets.isDirectory) {
                    linkTree(assets, romLegacyAssetsRoot)
                    logger.lifecycle("rom-embed: ${source.name} 旧格式 assets 已引入（${assets.path}）")
                }
                val legacyLibs = legacy.resolve("jniLibs/arm64-v8a")
                val libs = legacyLibs.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".so") }
                libs.forEach { linkOrCopy(it, File(romDistLibs, it.name)) }
                if (libs.isNotEmpty()) {
                    logger.lifecycle("rom-embed: ${source.name} 旧格式 resident .so " + libs.size + " 个")
                }
                residentBySource[source.name] = libs.map { it.name }
                continue
            }
            val dir = resolveRomSourceDir(source) ?: continue
            val zips = dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
                // -min.zip 是最小占位包（发布产物之一），不是"一个 ROM"，由下面的 shippedMin 消费。
                .filter { !it.name.endsWith("-min.zip", ignoreCase = true) }
                .filter { source.only.isEmpty() || it.name in source.only }
                .sortedBy { it.name }
            if (zips.isEmpty()) {
                logger.lifecycle("rom-embed: 跳过 ${source.name}：目录里没有 zip（${dir.path}）")
                continue
            }
            val residents = mutableListOf<String>()
            // APK 必备 .so 的第一来源：发布产物里的 apk-resident-libs-*.tar。
            // 这是"发布方算过、CI 校验过"的那一份；只有它缺失时才从 ROM 清单反推（见下面的 extractResidentLibs）。
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".tar", true) && it.name.contains("resident") }
                .sortedBy { it.name }
                .forEach { tar -> residents += absorbResidentTar(tar, source.name) }
            for (zip in zips) {
                val (issues, entry) = romAssetReport(zip)
                if (issues.isNotEmpty()) {
                    val lines = issues.map { "ROM 包不合法 ${zip.name}：$it" }
                    if (romAssetsStrict) throw GradleException(lines.joinToString("\n"))
                    lines.forEach { logger.lifecycle("rom-embed: 跳过 ${zip.name} -> $it") }
                    continue
                }
                // 1) APK 必备 .so：没有 resident tar 时才从包里的清单反推（resident: true 那几条）
                if (residents.isEmpty()) residents += extractResidentLibs(zip, romDistLibs)
                // 2) 最小占位包：发布产物里带了 -min.zip 就直接用（与发布一致），否则现生成。
                val shippedMin = File(dir, zip.name.removeSuffix(".zip") + "-min.zip")
                val minimal = File(romDistMin, shippedMin.name)
                val minOk = if (shippedMin.isFile) {
                    linkOrCopy(shippedMin, minimal)
                    true
                } else {
                    writeMinimalRom(zip, minimal)
                }
                // 3) 决定哪一份进 APK
                val embedded = when (source.mode) {
                    "libs" -> null
                    "minimal" -> minimal.takeIf { minOk }
                    else -> zip
                }
                if (embedded != null) {
                    linkOrCopy(embedded, File(romDistRoms, embedded.name))
                    linkOrCopy(embedded, File(romEmbedStagingDir, embedded.name))
                    val (minIssues, minEntry) = romAssetReport(embedded)
                    if (minIssues.isNotEmpty()) {
                        val lines = minIssues.map { "ROM 包不合法 ${embedded.name}：$it" }
                        if (romAssetsStrict) throw GradleException(lines.joinToString("\n"))
                        lines.forEach { logger.lifecycle("rom-embed: 跳过 ${embedded.name} -> $it") }
                        continue
                    }
                    minEntry["sourceName"] = source.name
                    minEntry["mode"] = source.mode
                    indexEntries += minEntry
                    total += embedded.length()
                    logger.lifecycle(
                        "rom-embed: 内置 " + minEntry["id"] + "@" + minEntry["romVersion"] +
                            (if (source.mode == "minimal") "（最小占位包，" else "（") +
                            embedded.name + "，" +
                            "%.1f".format(embedded.length() / 1024.0 / 1024.0) + " MB）",
                    )
                } else {
                    logger.lifecycle("rom-embed: ${zip.name} 只带 APK 必备 .so，包不进安装包（mode=libs）")
                }
                logger.lifecycle("rom-embed: 最小占位包 → ${minimal.name}")
            }
            if (residents.isEmpty()) {
                source.residentLibs?.let { raw ->
                    val libDir = if (File(raw).isAbsolute) File(raw) else rootProject.file(raw)
                    val libs = libDir.listFiles().orEmpty()
                        .filter { it.isFile && it.name.endsWith(".so") }
                        .sortedBy { it.name }
                    if (libs.isNotEmpty()) {
                        libs.forEach { linkOrCopy(it, File(romDistLibs, it.name)) }
                        residents += libs.map { it.name }
                        logger.lifecycle(
                            "rom-embed: ${source.name} 的包没有 resident 标记，按 residentLibs 补 " +
                                libs.size + " 个 .so（旧包回退，见 docs/rom-release/01 §3.2）",
                        )
                    } else {
                        logger.lifecycle("rom-embed: ${source.name} 的 residentLibs 目录里没有 .so（${libDir.path}）")
                    }
                }
            }
            residentBySource[source.name] = residents.distinct()
            if (residents.isEmpty()) {
                logger.lifecycle(
                    "rom-embed: ${source.name} 没有抽到 resident .so。" +
                        "如果这份包是老布局（清单没有 libPath），APK 的 lib/ 需要由 :guest-runtime-* 提供",
                )
            } else {
                logger.lifecycle("rom-embed: ${source.name} APK 必备 .so ${residents.distinct().size} 个：${residents.distinct()}")
            }
        }

        // 每个 source 一份 tar（发布产物；也是"构建链的第二个产物"）
        for ((name, libs) in residentBySource) {
            if (libs.isEmpty()) continue
            val files = libs.map { File(romDistLibs, it) }.filter { it.isFile }
            writeTar(files, File(romDistDir, "apk-libs-" + name + ".tar"))
        }
        val allLibs = romDistLibs.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        if (allLibs.isNotEmpty()) writeTar(allLibs, File(romDistDir, "apk-libs-all.tar"))

        val index = mapOf(
            "schema" to "versebox-rom-index/1",
            "generatedBy" to "app:stageRomAssets",
            "config" to romEmbedConfigFile.name,
            "entries" to indexEntries,
        )
        val indexText = JsonOutput.prettyPrint(JsonOutput.toJson(index)) + "\n"
        File(romEmbedStagingDir, "index.json").writeText(indexText)
        File(romDistDir, "index.json").writeText(indexText)
        logger.lifecycle(
            "rom-embed: " + indexEntries.size + " 个内置 ROM，合计 " +
                "%.1f".format(total / 1024.0 / 1024.0) + " MB；产物目录 " + romDistDir.path,
        )
        // 判据：APK 的 lib/ 是空的，等于"能装、能列 ROM、一启动就 EACCES"。
        // 这不是可以静默放过的告警（untrusted_app 不能从 app_data_file execve 客体文件）。
        val jniCount = romDistLibs.listFiles().orEmpty().count { it.isFile && it.name.endsWith(".so") }
        if (jniCount == 0) {
            logger.lifecycle(
                "rom-embed: 警告 —— APK 的 lib/ 没有任何原生文件。原因通常是：配置里没有足够" +
                    "的 source（resident tar / ROM 清单都没给出 resident 集合），也没有登记旧格式。" +
                    "这种包装上去以后客体起不来（execve EACCES），不要拿去装机。",
            )
        } else {
            logger.lifecycle("rom-embed: APK 必备 .so 合计 " + jniCount + " 个（" + romDistLibs.path + "）")
        }
    }
}

/**
 * 内置 ROM 的轻校验（T3）：读 zip 中央目录 + 自述/清单的文本片段。
 * 它不改变打包内容，也不替代运行期的 L2 全量校验（payload 哈希与逐条 bytes/sha256）。
 */
val verifyRomAssets = tasks.register("verifyRomAssets") {
    group = "verification"
    description = "轻校验已 staging 的 ROM 包（没有包就跳过；坏包默认构建失败）"
    dependsOn(stageRomAssets)
    inputs.dir(romEmbedStagingDir)
    doLast {
        val zips = romEmbedStagingDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
        if (zips.isEmpty()) {
            println("rom-assets: 0 个 ROM（跳过）")
            return@doLast
        }
        val problems = mutableListOf<String>()
        val keys = mutableSetOf<String>()
        var bytes = 0L
        for (zip in zips.sortedBy { it.name }) {
            val (issues, entry) = romAssetReport(zip)
            if (issues.isEmpty()) {
                bytes += zip.length()
                val key = (entry["id"] as? String).orEmpty() + "@" + (entry["romVersion"] as? String).orEmpty()
                if (!keys.add(key)) problems += "romKey 重复：" + key + "（" + zip.name + "）"
            } else {
                val lines = issues.map { "ROM 包不合法 " + zip.name + "：" + it }
                if (romAssetsStrict) {
                    problems += lines
                } else {
                    lines.forEach { println("rom-assets: 跳过 " + zip.name + " -> " + it) }
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
        println("rom-assets: " + zips.size + " 个 ROM, " + "%.1f".format(bytes / 1024.0 / 1024.0) + " MB")
    }
}

android.sourceSets.getByName("main").assets.directories.add(romAssetsRoot.absolutePath)
// 旧格式（如果配置里登记了）的 assets 暂存目录；没登记时目录不存在，AGP 会忽略
android.sourceSets.getByName("main").assets.directories.add(romLegacyAssetsRoot.absolutePath)
// APK 的 lib/：唯一来源是 rom-dist/apk-libs（resident tar / ROM 清单 / 旧格式 jniLibs 都汇到这里）
android.sourceSets.getByName("main").jniLibs.directories.add(romDistJniRoot.absolutePath)

tasks.named("preBuild") { dependsOn(verifyRomAssets) }
