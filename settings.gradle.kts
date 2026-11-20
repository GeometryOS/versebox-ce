pluginManagement {
    /*
     * AGP 的插件标记（com.android.application:com.android.application.gradle.plugin:9.2.1）
     * 是一个只有几十字节的 POM；镜像/离线缓存里常常只缓存了 com.android.tools.build:gradle
     * 这个真正实现模块。把 id 直接映射到实现模块，既绕开标记 POM，也让离线构建更稳。
     */
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application", "com.android.library" ->
                    useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        // 顺序即优先级：国内镜像在前，官方源兜底。
        // 实测（2026-11，本机 2 MB 区间取样）：阿里云 google 5.59 MB/s、阿里云 public 3.97 MB/s、
        // 华为云 2.4 MB/s，而 Google Maven 只有 0.32 MB/s、Maven Central 0.17 MB/s。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 本工程自带一份 miuix 快照副本（gradle/local-repo，4.5 MB，来源见其 README.md）。
        // 原因：GitHub Packages 会清掉旧快照——2026-11-20 实测 0.9.4-292d8ec6-SNAPSHOT 已 404，
        // 只依赖远程就会出现「同一个 commit 昨天能编、今天不能编」。本地仓库排最前，优先命中。
        maven {
            name = "LocalMiuixSnapshot"
            url = uri(rootDir.resolve("gradle/local-repo"))
        }
        // miuix 的 SNAPSHOT（InstallerX 用的那个 0.9.4-<commit>-SNAPSHOT）只发在 GitHub Packages，
        // 必须带凭据：本地 ~/.gradle/gradle.properties 里配 gpr.user / gpr.key，
        // 或环境变量 GITHUB_ACTOR / GITHUB_TOKEN（需要 read:packages 权限）。
        val gprUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR"))
        val gprKey = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN"))
        maven {
            name = "GitHubPackagesMiuix"
            url = uri("https://maven.pkg.github.com/compose-miuix-ui/miuix")
            if (gprUser.isPresent && gprKey.isPresent) {
                credentials {
                    username = gprUser.get()
                    password = gprKey.get()
                }
                // GitHub Packages 对未认证请求返回 404（不是 401），Gradle 必须先发 Basic 凭据。
                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }
        // 顺序说明：miuix-nav-android 这类新构件阿里云同步滞后，官方源在前更稳；
        // 镜像仍保留在后面兜底（google/mavenCentral 拉不到的旧构件走镜像）。
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "VerseBox-CE"
include(":app", ":native-display", ":guest-runtime", ":phantom-probe")
// guest-runtime-a9 / -a15 是**同一工作区的兄弟仓库**（各有自己的 .git 与 GB 级 ROM 产物），
// 不属于本仓库、也被 .gitignore 排除。干净检出时它们不存在，无条件 include 会让 Gradle 直接失败：
//   "Configuring project ':guest-runtime-a9' without an existing directory is not allowed"
// 所以按目录存在与否决定是否纳入（app 不依赖它们，只有本地做 ROM 发布时才需要）。
listOf(":guest-runtime-a9", ":guest-runtime-a15").forEach { path ->
    if (file(path.removePrefix(":")).isDirectory) include(path)
}
// 日志审计策略库：纯 Kotlin/JVM，不依赖 Android（见 docs/log-audit/04-代码结构与解耦.md）。
include(":log-audit")
// 虚拟机显示类设置的策略库：纯 Kotlin/JVM，不依赖 Android/Compose（见 docs/display-settings/03）。
include(":vm-display")
// 虚拟机 ROM 的契约与版本管理策略库：纯 Kotlin/JVM，不依赖 Android/Compose/第三方库
// （见 docs/rom-package/14-版本管理/03-业务逻辑与解耦.md）。
include(":vm-rom")
if (providers.gradleProperty("containerProbe").isPresent) {
    include(":container-probe")
}
