import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 版本由根工程 buildscript classpath 决定（与 :log-audit 同一条规则，不要在这里写版本）。
    id("org.jetbrains.kotlin.jvm")
}

/*
 * 虚拟机显示类设置的策略库 —— docs/display-settings/03-业务逻辑与解耦.md §1 的落点。
 *
 * 这里是**纯 Kotlin/JVM**：没有 Android 插件、没有 android.*、没有 Compose。
 * 分辨率预设、真机几何换算、"这次改动要不要重启"的判定、给用户看的两行文案，
 * 全部在这里用纯函数表达；UI 只渲染 VmDisplayOption，"写库 / 写审计 / 停虚拟机"由
 * :app 的 me.kanadex.geometryos.display.android 适配层负责。
 *
 * 为什么单独一个模块而不是塞进 :app：
 * 1. 两套 UI（Miuix / Material 3）必须共用同一份判定与文案，放在 app 里迟早被复制成两份；
 * 2. 它不依赖 Android，所以能用 JUnit 直接测（真机几何用 HostDisplay 注入，不读 DisplayMetrics）；
 * 3. 渲染器与客体侧的输入是**三个整数**（宽/高/DPI），这份契约只有一处定义。
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
    testImplementation(libs.junit4)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
