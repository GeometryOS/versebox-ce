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
