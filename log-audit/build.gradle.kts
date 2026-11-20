import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 不写版本：根工程 plugins 块已经（通过 compose 编译器插件）把 Kotlin Gradle 插件
    // 放上了 buildscript classpath，这里再声明版本会得到
    // "already on the classpath with an unknown version"。版本仍由 libs.versions.toml 的 kotlin 决定。
    id("org.jetbrains.kotlin.jvm")
}

/*
 * 日志审计策略库 —— docs/log-audit/04-代码结构与解耦.md §1 的落点。
 *
 * 这里是**纯 Kotlin/JVM**：没有 Android 插件、没有 android.* 依赖、没有 Compose。
 * 「谁能删文件」由 policy/ 的纯函数决定，「怎么删」由 port/ 的实现（在 :app 的
 * me.kanadex.geometryos.audit.android 里）决定 —— 两者都不在本模块之外直接改文件，
 * 所以业务代码想顺手 File.delete() 只能经过 AuditEngine 的公开 API。
 */
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 状态流（AuditState）与 port 的挂起调用
    implementation(libs.kotlinx.coroutines.core)
    // manifest.json / audit.log 的编解码：用库，不自己写 JSON 解析器
    implementation(libs.kotlinx.serialization.json)

    // 纯策略单测：不需要设备、不需要 Robolectric
    testImplementation(libs.junit4)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
