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
 */
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
}