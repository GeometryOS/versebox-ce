import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest

plugins { id("com.android.library") }

// A10 的 **ROM 数据**属于 guest-runtime-a10 仓库（`docs/rom-package/11` §1）：那里从参考 APK
// 抽出 `prepared/`，再由 `rom/stage-apk-payload.py` 产出 `prepared-apk/`。
// 本模块（app 仓库）只保留**共享运行时与 Java 运行层**，消费 A10 仓库的 `prepared-apk`。
//
// 2026-11-12 起客体 .so 不再进 APK 的 `lib/arm64-v8a`：旧格式在
// `assets/guest-runtime/lib/**`（清单 files[].libPath），新格式在 ROM 的 `payload/lib/`。
// 只有被 execve 的那几个（rom/resident-native.txt）留在 APK 的 lib/ 里。
val preparedRoot: File? = providers.gradleProperty("guestRuntimePrepared").orNull
    ?.let { rootProject.file(it) }
val a10Root = rootProject.layout.projectDirectory.dir("guest-runtime-a10/prepared-apk").asFile
// 只接受源码构建产物：-PguestRuntimePrepared 或 guest-runtime-a10/prepared-apk。
// 参考 APK 解包（runtime-inputs）已移除，避免把来路不明的旧产物打进 APK。
val runtimeRoot = preparedRoot ?: a10Root

android {
    namespace = "io.a10vm.guestruntime"
    compileSdk = 37
    compileSdkMinor = 0
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
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

// ciSkipNativeVerify 是「只编代码、不要客体运行库」的逃生口。
val skipNativeVerify = providers.gradleProperty("ciSkipNativeVerify").isPresent

val verifyRuntime = tasks.register("verifyRuntime") {
    doLast {
        if (skipNativeVerify) {
            logger.lifecycle(
                "guest-runtime: 已跳过 A10 运行库校验（-PciSkipNativeVerify=true）。" +
                    "打出来的 APK **不含**客体运行库，只能用于看 UI / 编代码。",
            )
            return@doLast
        }
        val manifest = runtimeRoot.resolve("assets/guest-runtime-inputs.json")
        check(manifest.isFile) {
            "缺少 Android 10 ROM 运行库输入：$manifest\n" +
                "A10 的运行时目录属于 guest-runtime-a10 仓库，在本仓库根跑一次：\n" +
                "  sh guest-runtime-a10/scripts/build-a10.sh --skip-rom\n" +
                "（它从 Redroid 镜像抽 prepared/，再产出 prepared-apk/；或" +
                "传入 -PguestRuntimePrepared=<prepared-apk 目录>。）"
        }
        val json = JsonSlurper().parse(manifest) as Map<*, *>
        check((json["guestSdk"] as Number).toInt() == 29) { "guestSdk must be 29" }
        val files = json["files"] as List<*>
        check(files.isNotEmpty()) { "No native files in the A10 manifest" }
        var checked = 0
        for (item in files) {
            val entry = item as Map<*, *>
            val logical = entry["logical"] as String
            val libPath = entry["libPath"] as? String
            val isResident = (entry["resident"] as? Boolean) == true
            // 位置由清单自己说（docs/rom-package/04 §4）。老布局（没有 libPath）只允许历史产物。
            val file = if (isResident || libPath == null) runtimeRoot.resolve("jniLibs/arm64-v8a/" + entry["name"])
                       else runtimeRoot.resolve("assets/" + libPath)
            check(file.isFile) {
                "Missing A10 native input: $file\n" +
                        (if (libPath == null)
                            "这份输入还是老布局（.so 在 jniLibs/）。跑 sh guest-runtime-a10/scripts/build-a10.sh --skip-rom"
                        else "prepared-apk 不完整，重跑 guest-runtime-a10/rom/stage-apk-payload.py")
            }
            check(file.length() == (entry["bytes"] as Number).toLong()) { "Size mismatch for $logical" }
            check(sha256(file) == entry["sha256"]) { "SHA-256 mismatch for $logical" }
            checked += 1
        }
        // 硬闸门：APK 的 lib/ 只允许 resident 那几个客体文件。
        val jniDir = runtimeRoot.resolve("jniLibs/arm64-v8a")
        val allowed = files.map { (it as Map<*, *>) }
            .filter { (it["resident"] as? Boolean) == true }
            .map { it["name"] as String }
            .toSet()
        val present = jniDir.listFiles { f -> f.isFile && f.name.endsWith(".so") }
            ?.map { it.name }?.toSet() ?: emptySet<String>()
        check(present == allowed) {
            "APK 的 lib/arm64-v8a 内容与 resident 清单不一致：" +
                    "\n  多出来（不该在 APK 里）：" + (present - allowed) +
                    "\n  缺少（必须在 APK 里，execve 只能从这里）：" + (allowed - present)
        }
        logger.lifecycle("A10 runtime verified: $checked native files (payload layout)")
    }
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
tasks.named("preBuild").configure { dependsOn(verifyRuntime) }

tasks.withType<org.gradle.api.tasks.bundling.Zip>().configureEach {
    isZip64 = true
}

// AGP 9 的官方目录注入点：按 variant 添加"静态源码目录"，等价于旧 sourceSets 的
// jniLibs.srcDir / assets.srcDir，且不经过已废弃的 com.android.build.gradle.api.* 类型。
// --------------------------------------------------------------- 旧格式 payload 的开关
//
// 旧格式（把整份 payload 摊在 APK 的 assets/<prefix>/ 里）与「ROM 入库」是**平行**的两条路：
// 新格式的 ROM 走 app/rom-embed.json → build/rom-dist → assets/kanadex-rom/，
// 这里只负责「旧描述文件 + 旧 payload」那一份（RomCatalog 读 assets/roms/*.yaml）。
//
// 本轮**默认仍然开着**，原因不是懒：关掉之后，rom_id 还是旧格式 id（android15，没有 @版本）
// 的实例会先找不到旧描述、再找不到新包，启动会被闸门拦住。开着的唯一代价是 APK 里多一份
// payload（~1.5 GB），这是"不把用户已经在用的虚拟机弄坏"的价格。
//
// 关掉的顺序（docs/rom-release/03 §3）：先确认设备上所有实例的 rom_id 都是 romKey，
// 再传 -PverseboxLegacyPayload=false 打一次包，用真机把三台系统各起一次。
val keepLegacyPayload = (providers.gradleProperty("verseboxLegacyPayload").orNull ?: "false").toBoolean()

androidComponents {
    onVariants { variant ->
        // **本模块不再注入 jniLibs**：APK 的 lib/ 由 rom-embed 配置里的
        // apk-resident-libs-*.tar 提供（:app:stageRomAssets → build/rom-dist/apk-libs）。
        // 两边同时注入同名 .so 会在 AGP 合并阶段直接失败，而"哪几个 so 进 lib/"
        // 只允许有一个来源（docs/rom-release/01-入库配置/02-构建链产物.md §4）。
        //
        // 旧格式 payload（assets/<prefix>/）默认也关掉：
        // 需要它时用 -PverseboxLegacyPayload=true，或更好——在 rom-embed 配置里登记
        // legacyPreparedApk，让构建链按配置引入（docs/rom-release/01-入库配置/03）。
        if (keepLegacyPayload) {
            variant.sources.assets?.addStaticSourceDirectory(runtimeRoot.resolve("assets").absolutePath)
        }
    }
}