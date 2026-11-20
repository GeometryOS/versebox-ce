import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest

plugins { id("com.android.library") }

// =============================================================================
// 打包哪个桌面渲染器（jniLibs/arm64-v8a/lib*desktop-window.so）
// =============================================================================
// 渲染器是桌面 GPU 协议的**宿主半边**：解码客体发来的 EGL/GLES 流。GLES1 的处理只存在于
// guest-runtime-a15/third_party/graphics/container 的源码里（0cb89e 的 skip + 应答、
// 7f217bd 的构建接入），而它**只有靠 scripts/build-desktop-window.sh 编译出**才会进 APK。
//
// 从 _bin/A10VM.apk 解出来的那份（runtime-inputs/native-display）没有这段代码：
// 它对第一个 GLES1 opcode（glClearColor = 1025）直接 throw，客户端被踢 →
// rcCreateContext 收不到应答 → EGL_BAD_ALLOC → GLThread FATAL → code=137（AIDA64 的死法）。
//
// 旧版本只有两级：-PnativeDisplayPrepared → A10 捆绑那份。于是 **Android Studio 的
// Build / gradlew :app:assembleDebug 默认就打包错的渲染器**，只有 guest-runtime-a15 的
// 脚本传了参数才正确（"AI 助手用特定脚本才对"）。现在改成：源码产物优先，必要且能
// 本模块里编出来，A10 只作最后兜底并**大声告警**（可用 -PnativeDisplayRequireSource=true 直接失败）。
//
// 优先级（第一个命中的生效）：
//   1. -PnativeDisplayPrepared=<dir>                显式指定（A15 脚本 / CI / 其它树）
//   2. guest-runtime-a15/artifacts/native-display    build-desktop-window.sh 的产物
//                                                   （工具链齐全时由本模块的
//                                                   :native-display:buildDesktopWindowRenderer 生成）
//   3. native-display/build/prepared                同一构建的旧暂存目录
//   4. runtime-inputs/native-display                A10 捆绑那份（兜底：没有 GLES1，会告警）
// =============================================================================

val rootDirFile: File = rootProject.projectDir
val a15Dir: File = File(rootDirFile, "guest-runtime-a15")
val rendererScript: File = File(a15Dir, "scripts/build-desktop-window.sh")
val sourceBuiltRoot: File = File(a15Dir, "artifacts/native-display")
val legacyBuiltRoot: File = File(projectDir, "build/prepared")
val bundledRoot: File = File(rootDirFile, "runtime-inputs/native-display")

val explicitRoot: File? = providers.gradleProperty("nativeDisplayPrepared").orNull
    ?.let { rootProject.file(it) }
val autoBuildRenderer: Boolean =
    providers.gradleProperty("nativeDisplayAutoBuild").orNull?.equals("false", ignoreCase = true) != true
// 默认严格：打包的渲染器不带 GLES1 就是构建错误。r163 的规则（缺编译条件就失败，
// 让问题暴露）就是从这里来的；只有明确知道自己在做 A9/A10 时才允许降级成告警。
val allowA10Renderer: Boolean =
    providers.gradleProperty("nativeDisplayAllowA10Renderer").orNull
        ?.equals("true", ignoreCase = true) == true
val skipVerify: Boolean = providers.gradleProperty("ciSkipNativeVerify").isPresent

fun File.rendererLibrary(): File? =
    listOf("liba15vm-desktop-window.so", "liba10vm-desktop-window.so")
        .map { File(this, "jniLibs/arm64-v8a/$it") }
        .firstOrNull { it.isFile }

// 判定「这份渲染器认不认 GLES1」用的是它自己的日志标记（DesktopDecoder 里 skip / es1Query
// 两条路径各打一行）。比文件名、比大小、比 mtime 都可靠：A10 那份 6,806,024 B，
// 源码产物 6,350,912 B，两者同名同 soname，只有内容不同。
fun File.hasAscii(needle: String): Boolean {
    if (!isFile) return false
    val haystack = readBytes()
    val pin = needle.toByteArray(Charsets.US_ASCII)
    if (pin.isEmpty() || haystack.size < pin.size) return false
    outer@ for (start in 0..(haystack.size - pin.size)) {
        for (offset in pin.indices) if (haystack[start + offset] != pin[offset]) continue@outer
        return true
    }
    return false
}

fun isGles1Renderer(dir: File): Boolean =
    dir.rendererLibrary()?.hasAscii("desktop_gpu_es1_query") == true

fun executableOnPath(name: String): File? =
    System.getenv("PATH").orEmpty()
        .split(File.pathSeparatorChar)
        .asSequence()
        .filter { it.isNotBlank() }
        .map { File(it, name) }
        .firstOrNull { it.isFile }

/**
 * WSL 的 `System32\bash.exe` 与商店 shim 都在 PATH 最前面，但它们读不懂 Windows 形式的
 * `ANDROID_NDK_HOME`，渲染器脚本会退回 `/root/Android/Sdk` 再报 NDK not found。
 * 只认能消费 Windows 路径的 bash（Git Bash、msys；Linux 上路径不含反斜杠，恒为真）。
 */
fun canUseWindowsPaths(bash: File): Boolean {
    val path = bash.absolutePath.lowercase().replace('/', '\\')
    return !path.contains("\\system32\\bash.exe") && !path.contains("\\windowsapps\\bash.exe")
}

/** Git Bash / msys / 其它 Unix 的 bash；Windows 上通常在 PATH 之外（例如 D:\Program\Git）。 */
fun findBash(): File? {
    for (name in listOf("bash", "bash.exe")) {
        val hit = executableOnPath(name) ?: continue
        if (!canUseWindowsPaths(hit)) continue
        return hit
    }
    val home = System.getProperty("user.home")
    val candidates = listOfNotNull(
        System.getenv("ProgramFiles")?.let { File(it, "Git/bin/bash.exe") },
        System.getenv("ProgramW6432")?.let { File(it, "Git/bin/bash.exe") },
        System.getenv("ProgramFiles(x86)")?.let { File(it, "Git/bin/bash.exe") },
        File("D:/Program/Git/bin/bash.exe"),
        File("/usr/bin/bash"),
        File("/bin/bash"),
        File(home, "AppData/Local/Programs/Git/bin/bash.exe"),
    )
    return candidates.firstOrNull { it.isFile }
}

/** build-desktop-window.sh 只要 NDK 的 clang++ 与 libunwind（自带的，不依赖 CMake）。 */
fun findNdk(): File? {
    val looksUsable = { dir: File -> File(dir, "toolchains/llvm/prebuilt").isDirectory }
    for (key in listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT")) {
        System.getenv(key)?.let { File(it) }?.takeIf(looksUsable)?.let { return it }
    }
    val home = System.getProperty("user.home")
    val sdkRoots = listOfNotNull(
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("ANDROID_HOME"),
        System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" },
        "$home/Android/Sdk",
        "$home/AppData/Local/Android/Sdk",
    ).map(::File)
    for (sdk in sdkRoots) {
        // 与脚本一致：A15 的桥接与渲染器按 NDK 29 编，别的版本会悄悄换掉 ABI/头文件。
        val hit = File(sdk, "ndk").listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("29.") }
            ?.sortedBy { it.name }
            ?.lastOrNull()
        if (hit != null) return hit
    }
    return null
}

/** 渲染器里编进去的 codec 必须与客体侧同源：优先取随包的 desktop-gpu-host，其次 pinned 常量。 */
fun codecHint(): String {
    val host = File(a15Dir, "bridges/bin/desktop-gpu-host")
    if (host.isFile) {
        Regex("[0-9a-f]{64}")
            .find(String(host.readBytes(), Charsets.ISO_8859_1))
            ?.let { return it.value }
    }
    val pinned = File(a15Dir, "third_party/graphics/codec-id.txt")
    if (pinned.isFile) return pinned.readText().trim()
    return ""
}

val bash: File? = if (skipVerify) null else findBash()
val ndk: File? = if (skipVerify) null else findNdk()
val canBuildFromSource: Boolean =
    explicitRoot == null && autoBuildRenderer && bash != null && ndk != null && rendererScript.isFile

// 从源码编：Android Studio 的 Build 与 gradlew :app:assembleDebug 都会走到这里，
// 不再依赖「记得传 -PnativeDisplayPrepared」。
val buildDesktopWindowRenderer: TaskProvider<Exec>? = if (canBuildFromSource) {
    val shell = requireNotNull(bash)
    val ndkDir = requireNotNull(ndk)
    tasks.register<Exec>("buildDesktopWindowRenderer") {
        group = "android15"
        description = "从 guest-runtime-a15/third_party/graphics 编出 GLES1 版桌面渲染器"
        workingDir = a15Dir
        commandLine(shell.absolutePath, "scripts/build-desktop-window.sh")
        environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
        // 输入 = 脚本真正读的东西；源一变就重编，没变就跳过（Gradle 的 up-to-date 判定）。
        inputs.file(rendererScript)
        inputs.dir(File(a15Dir, "third_party/graphics/container"))
        inputs.dir(File(a15Dir, "third_party/graphics/a9/generated"))
        inputs.dir(File(a15Dir, "third_party/graphics/a9/definitions"))
        inputs.dir(File(a15Dir, "third_party/graphics/a9/upstream"))
        inputs.dir(File(a15Dir, "third_party/graphics/compat"))
        inputs.property("codec", codecHint())
        inputs.property("ndk", ndkDir.absolutePath)
        outputs.file(File(sourceBuiltRoot, "jniLibs/arm64-v8a/liba15vm-desktop-window.so"))
        outputs.file(File(sourceBuiltRoot, "assets/native-display-inputs.json"))
    }
} else null

val displayRoot: File = explicitRoot ?: when {
    isGles1Renderer(sourceBuiltRoot) -> sourceBuiltRoot
    canBuildFromSource -> sourceBuiltRoot // 由 buildDesktopWindowRenderer 产出
    isGles1Renderer(legacyBuiltRoot) -> legacyBuiltRoot
    else -> bundledRoot
}

android {
    namespace = "io.a10vm.nativedisplay"
    compileSdk = 37
    compileSdkMinor = 0
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 目录注入移到下面的 androidComponents 块（AGP 9 的 sourceSets 库路径会抛
    // ClassCastException: DefaultAndroidLibrarySourceSet -> AndroidLibrarySourceSet）。
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val verifyRenderer = tasks.register("verifyRenderer") {
    buildDesktopWindowRenderer?.let { dependsOn(it) }
    if (explicitRoot == null && displayRoot == bundledRoot &&
        !File(bundledRoot, "jniLibs/arm64-v8a").isDirectory
    ) {
        // 干净检出 + 没有 bash/NDK：兜底只能走 A10 那份，那也得先把捆绑包解出来。
        dependsOn(rootProject.tasks.named("bootstrapBundledRuntime"))
    }
    doLast {
        if (skipVerify) return@doLast
        val relative = displayRoot.absolutePath.removePrefix(rootDirFile.absolutePath)
            .removePrefix(File.separator).replace(File.separatorChar, '/')
        val library = displayRoot.rendererLibrary()
        check(library != null && library.isFile) {
            "缺少原生显示库：${File(displayRoot, "jniLibs/arm64-v8a")}\n" +
                "源码构建：sh guest-runtime-a15/scripts/build-desktop-window.sh" +
                "（或直接 gradlew :native-display:buildDesktopWindowRenderer，需要 NDK 29 + bash）\n" +
                "显式指定：-PnativeDisplayPrepared=<含 jniLibs/ 与 assets/ 的目录>\n" +
                "干净检出时也可以从 _bin/A10VM.apk 自动解包（那份没有 GLES1，会让 ES1 应用崩）。"
        }
        val gles1 = isGles1Renderer(displayRoot)

        // 清单里记着 bytes/sha256，运行时的 files.verifyInputs() 会逐条核对，
        // 对不上就停在 "VERIFY Checking display"，所以在这里先当构建错误报出来。
        val manifestFile = File(displayRoot, "assets/native-display-inputs.json")
        check(manifestFile.isFile) { "缺少显示清单：$manifestFile（运行时会停在 VERIFY Checking display）" }
        val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
        val listed = manifest["files"] as? List<*> ?: emptyList<Any?>()
        check(listed.isNotEmpty()) { "$manifestFile 没有 files 数组" }
        for (item in listed) {
            val entry = item as Map<*, *>
            val name = entry["name"] as String
            val file = File(displayRoot, "jniLibs/arm64-v8a/$name")
            check(file.isFile) { "清单列了 $name，但目录里没有：$file" }
            check(file.length() == (entry["bytes"] as Number).toLong()) {
                "清单/文件大小不一致：$name 清单 ${entry["bytes"]}，实际 ${file.length()}（重跑源码构建，别手改清单）"
            }
            check(sha256(file) == entry["sha256"]) {
                "清单/文件 sha256 不一致：$name（运行时会停在 VERIFY Checking display；重跑源码构建，别手改清单）"
            }
        }

        // 跨侧协议常量同源：清单里的 codec 必须真的编进了这个 .so。
        val codec = manifest["codec"] as? String
        val embeddedCodec = Regex("[0-9a-f]{64}")
            .findAll(String(library.readBytes(), Charsets.ISO_8859_1))
            .map { it.value }
            .toSet()
        val codecOk = codec != null && codec in embeddedCodec

        if (!gles1) {
            val message = buildString {
                append("打包的是 A10 渲染器（$relative，${library.length()} B）：它没有 GLES1 处理。\n")
                append("  后果：任何 setEGLContextClientVersion(1) 的应用在第一条 GLES1 调用就被踢，\n")
                append("        rcCreateContext 返回 0 → EGL_BAD_ALLOC → code=137。\n")
                append("  修法：gradlew :native-display:buildDesktopWindowRenderer")
                if (bash == null) append("（当前没找到 bash：Git Bash / msys）")
                if (ndk == null) append("（当前没找到 NDK 29：设 ANDROID_NDK_HOME）")
                if (!autoBuildRenderer) append("（本次用 -PnativeDisplayAutoBuild=false 关掉了 Gradle 内编译）")
                append("\n        或 -PnativeDisplayPrepared=guest-runtime-a15/artifacts/native-display")
                append("\n  确实要用 A10 渲染器（只做 A9/A10 时）：-PnativeDisplayAllowA10Renderer=true")
            }
            if (allowA10Renderer) logger.warn(message) else error(message)
        }
        if (!codecOk) {
            val message = "显示清单的 codec（$codec）没有编进 ${library.name}，" +
                "客体侧握手会用同一个 codec 常量，不一致时宿主日志出现 " +
                "\"Desktop GPU codec mismatch\"，画面黑屏。"
            if (allowA10Renderer) logger.warn(message) else error(message)
        }
        logger.lifecycle(
            "renderer: $relative -> ${library.name} ${library.length()} B" +
                (if (gles1) " (GLES1-aware, built from source)" else " (A10 bundled, NO GLES1)") +
                ", codec ${if (codecOk) "ok" else "MISMATCH"}"
        )
    }
}
tasks.named("preBuild").configure { dependsOn(verifyRenderer) }

// AGP 9 的官方目录注入点：按 variant 添加"静态源码目录"，等价于旧 sourceSets 的
// jniLibs.srcDir / assets.srcDir，且不经过已废弃的 com.android.build.gradle.api.* 类型。
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(displayRoot.resolve("jniLibs").absolutePath)
        variant.sources.assets?.addStaticSourceDirectory(displayRoot.resolve("assets").absolutePath)
    }
}
