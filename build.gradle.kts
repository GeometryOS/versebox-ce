/*
AGP 9.x 自带的 Kotlin 支持取代了 org.jetbrains.kotlin.android 插件，
因此这里只声明 AGP 与 Compose 编译器插件，不再声明 kotlin-android。

依赖仓库统一在 settings.gradle.kts 声明。
客体运行库的输入只来自源码构建产物（-PguestRuntimePrepared 或 guest-runtime-a10/prepared-apk），
*/
plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.compose.compiler) apply false
    // 路由必须是 @Serializable：miuix-nav 的返回栈 Saver 会把它序列化成 JSON（InstallerX 同款）
    alias(libs.plugins.kotlin.serialization) apply false
}

/*
 * Test 任务默认只在失败时打栈，全绿的运行一行用例名都不打，日志里看不出测了什么。
 * 逐条用例输出统一在这里打开，避免五个模块各写一遍、各写走样。
 * XML 与 HTML 报告仍然照常生成：XML 给 CI 汇总，HTML 给人看细节。
 *
 * Robolectric 在测试运行期自己取 android-all-instrumented，默认直连 Maven Central，
 * 既绕开本工程的镜像，也没有重试，CI 上会以 429 直接判失败。
 * 所以这份 SDK 改由 Gradle 解析一次落到 build/robolectric-sdk，测试只读该目录。
 */
val robolectricSdkDir = layout.buildDirectory.dir("robolectric-sdk")

val robolectricSdk: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    description = "Robolectric 运行期使用的 Android SDK 镜像，预先落盘供测试离线读取"
}

dependencies {
    robolectricSdk(libs.robolectric.android.all)
}

val stageRobolectricSdk = tasks.register<Copy>("stageRobolectricSdk") {
    from(robolectricSdk)
    into(robolectricSdkDir)
}

subprojects {
    tasks.withType<Test>().configureEach {
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }

    fun stageRobolectricSdkForTests() {
        tasks.withType<Test>().configureEach {
            dependsOn(":stageRobolectricSdk")
            systemProperty("robolectric.offline", "true")
            systemProperty("robolectric.dependency.dir", robolectricSdkDir.get().asFile.absolutePath)
        }
    }

    plugins.withId("com.android.application") { stageRobolectricSdkForTests() }
    plugins.withId("com.android.library") { stageRobolectricSdkForTests() }
}