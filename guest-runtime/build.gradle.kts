import java.io.File

plugins { id("com.android.library") }

// 本模块只保留共享运行时与 Java 运行层。客体 .so 与 ROM payload 由 :app:stageRomAssets
// 从配置的 ROM 包 staging 到 build/rom-dist，模块自己不再持有任何客体产物路径。
//
// 旧格式（整份 payload 摊在 APK 的 assets/<prefix>/）只在显式打开
// -PverseboxLegacyPayload=true 且同时给出 -PguestRuntimePrepared=<目录> 时注入。
// 不给 prepared-apk 默认路径：那个目录是 guest-runtime-a10 仓库的中间产物，
// 新格式 ROM 与它无关，缺目录时构建不应失败。
val legacyPayloadRoot: File? = providers.gradleProperty("guestRuntimePrepared").orNull
    ?.let { rootProject.file(it) }

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

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}

tasks.withType<org.gradle.api.tasks.bundling.Zip>().configureEach {
    isZip64 = true
}

// AGP 9 的官方目录注入点：按 variant 添加"静态源码目录"，等价于旧 sourceSets 的
// jniLibs.srcDir / assets.srcDir，且不经过已废弃的 com.android.build.gradle.api.* 类型。
// --------------------------------------------------------------- 旧格式 payload 的开关
//
// 旧格式（把整份 payload 摊在 APK 的 assets/<prefix>/ 里）与「ROM 入库」是平行的两条路：
// 新格式的 ROM 走 app/rom-embed.env → build/rom-dist → assets/kanadex-rom/，
// 这里只负责「旧描述文件 + 旧 payload」那一份（RomCatalog 读 assets/roms/*.yaml）。
//
// 默认关闭。打开时必须同时给出 -PguestRuntimePrepared=<prepared-apk 目录>；
// 本模块没有默认目录，也不会去 guest-runtime-a10 仓库里找中间产物。
val keepLegacyPayload = (providers.gradleProperty("verseboxLegacyPayload").orNull ?: "false").toBoolean()
val legacyRoot = legacyPayloadRoot
if (keepLegacyPayload) {
    if (legacyRoot == null) {
        throw GradleException(
            "旧格式 payload 需要显式目录：-PverseboxLegacyPayload=true 时同时传 " +
                "-PguestRuntimePrepared=<prepared-apk 目录>。新格式 ROM 不要走这条路，" +
                "用 app/rom-embed.env 配置 :app:stageRomAssets。",
        )
    }
    if (!legacyRoot.resolve("assets").isDirectory) {
        throw GradleException("旧格式 payload 目录缺少 assets/：" + legacyRoot)
    }
}

androidComponents {
    onVariants { variant ->
        // **本模块不再注入 jniLibs**：APK 的 lib/ 由 rom-embed 配置里的
        // apk-resident-libs-*.tar 提供（:app:stageRomAssets → build/rom-dist/apk-libs）。
        // 两边同时注入同名 .so 会在 AGP 合并阶段直接失败，而"哪几个 so 进 lib/"
        // 只允许有一个来源（docs/rom-release/01-入库配置/02-构建链产物.md §4）。
        if (keepLegacyPayload && legacyRoot != null) {
            variant.sources.assets?.addStaticSourceDirectory(legacyRoot.resolve("assets").absolutePath)
        }
    }
}
