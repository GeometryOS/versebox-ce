import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 版本由根工程 buildscript classpath 决定（与 :log-audit / :vm-display 同一条规则，不要在这里写版本）。
    id("org.jetbrains.kotlin.jvm")
}

/*
 * 虚拟机 ROM 的契约与版本管理策略库 —— docs/rom-package/14-版本管理/03-业务逻辑与解耦.md 的落点。
 *
 * 这里是**纯 Kotlin/JVM**：没有 Android 插件、没有 android.*、没有 Compose、没有第三方依赖
 * （org.json 也刻意不用：清单解析留在 :app 的适配层，这一层只收已经解析好的条目）。
 *
 * 放进来的是「ROM 是什么、怎么比、该怎么选」：
 *  1. romKey / id / 版本号的解析与比较（RomKeys）；
 *  2. 包自述的字段契约与 L1 校验（RomProperties）；
 *  3. 规范化 payload 哈希（RomHash，必须与各运行时仓库的 rom_hash.py 逐字节一致）；
 *  4. 解包流水线（RomExtractor：空间检查 → 临时目录 → 逐条 L2 → payload 哈希 → chmod → 原子换名）；
 *  5. 版本管理策略（RomVersionPolicy：同 id 多版本、更新/强制更新/降级/同版本切换的**唯一**判据）；
 *  6. 用户可见的行文案与对话框文案（RomTexts）。
 *
 * 留在 :app 的只有「Android 适配 + UI 调用点」：Context→File 的布局、SAF 读 uri、SQLite 登记、
 * 审计与记录、两个分支的 ViewModel 与页面。
 *
 * 为什么单独一个模块而不是塞进 :app：
 *  1. 两套 UI（Miuix / Material 3）必须共用同一份判定与文案，放在 app 里迟早被复制成两份；
 *  2. 它不依赖 Android，所以能用 JUnit 直接测（版本决策的分支比页面上看到的按钮多得多）；
 *  3. 版本管理会被「启动闸门」「开机更新提示」「管理页入口」「ROM 列表」四处调用，
 *     只有一份实现才可能一致（docs/rom-package/14-版本管理/02）。
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
