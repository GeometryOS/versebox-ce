# gradle/local-repo —— 工程内自带的 miuix 副本

## 这是什么

`top.yukonga.miuix.kmp:*` 的 `.aar` / `.pom` / `.module`，按 Maven 目录布局存放，
由 `settings.gradle.kts` 里的 `LocalMiuixSnapshot` 仓库提供。

版本号是 **`0.9.4-292d8ec6`**。它是上游快照
`0.9.4-292d8ec6-SNAPSHOT` 的原样副本，只是把坐标钉成了固定版本号：这样 Gradle 不会去问
`maven-metadata.xml`、不会做时间戳快照解析，也就不存在"上游删了快照就编不过"这件事。

## 为什么要带这一份

1. **远程会消失。** 这套快照只发在 GitHub Packages（`compose-miuix-ui/miuix`）
2. **正式版不能用。** Maven Central 的 `0.9.4` 正式版编在 `material-color-utilities 5.0.1` 上：
   `ThemePaletteStyle` 没有 `Cmf`、`ThemeColorSpec` 没有 `Spec2026`，而 `material-kolor 6.0.0`
   要 6.0.0 的 `dynamiccolor.DynamicScheme$Platform`——两边抢同一根轴，不可能共存。
3. **凭据不该是编译前提。** 带这一份之后，没有 GitHub token 的机器也能 Sync。

## 本机额外一步（很重要）

`~/.gradle/init.gradle`（本机实际是 `D:\C\.gradle\init.gradle`）为了走阿里云镜像，在
`settingsEvaluated` 里执行了 `repos.clear()`，会**清掉工程声明的所有仓库**。
不把 `gradle/local-repo` 加回那个脚本，这里的文件永远轮不到（表现为明明带了副本，
仍然报 `Could not find top.yukonga.miuix.kmp:miuix-core-android:0.9.4-292d8ec6`）。
现在脚本里已经加了 `LocalMiuixSnapshot`，并排在镜像之前。

## 内容来源

- 上游：`https://maven.pkg.github.com/compose-miuix-ui/miuix`
- 原版本：`0.9.4-292d8ec6-SNAPSHOT`
- 模块：`miuix-core`、`miuix-core-android`、`miuix-ui`、`miuix-ui-android`、`miuix-preference-android`、
  `miuix-icons-android`、`miuix-blur-android`、`miuix-shader`、`miuix-shader-android`、
  `miuix-squircle`、`miuix-squircle-android`、`miuix-nav-android`
