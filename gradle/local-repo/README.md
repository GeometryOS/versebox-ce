# gradle/local-repo —— 工程内自带的 miuix 快照副本

## 这是什么

`top.yukonga.miuix.kmp:*:0.9.4-292d8ec6-SNAPSHOT` 的 `.aar` / `.pom` / `.module`，共 32 个文件、约 4.5 MB，
按 Maven 目录布局存放，由 `settings.gradle.kts` 里的 `LocalMiuixSnapshot` 仓库提供。

## 为什么要带这一份

1. **远程会消失。** 这套快照只发在 GitHub Packages（`compose-miuix-ui/miuix`）。2026-11-20 实测：
   该模块的 `0.9.4-292d8ec6-SNAPSHOT` 目录已返回 404，模块元数据里只剩 `0.9.5-*`。
   于是同一个 commit 会出现「昨天能编、今天 `Could not find`」。
2. **正式版不能用。** Maven Central 的 `0.9.4` 正式版编在 `material-color-utilities 5.0.1` 上：
   `ThemePaletteStyle` 没有 `Cmf`、`ThemeColorSpec` 没有 `Spec2026`，而 `material-kolor 6.0.0`
   要 6.0.0 的 `dynamiccolor.DynamicScheme$Platform`——两边抢同一根轴，语义上不可能共存。
   细节见 `app/build.gradle.kts` 顶部注释与 `gradle/libs.versions.toml` 的 miuix 说明。
3. **凭据不该是编译前提。** 带这一份之后，没有 GitHub token 的机器也能 `Sync`。

## 内容来源

- 上游：`https://maven.pkg.github.com/compose-miuix-ui/miuix`
- 版本：`0.9.4-292d8ec6-SNAPSHOT`（与 `D:\my213\InstallerX-Revived-main` 钉的版本一致）
- 抓取时间：2026-11-20 之前（该版本从远程消失前）
- 模块：`miuix-core`、`miuix-core-android`、`miuix-ui`、`miuix-ui-android`、`miuix-preference-android`、
  `miuix-icons-android`、`miuix-blur-android`、`miuix-shader`、`miuix-shader-android`、
  `miuix-squircle`、`miuix-squircle-android`、`miuix-nav-android`
- 许可：Apache-2.0（miuix 上游许可）

## 怎么更新

```powershell
# 1. 删掉旧副本
Remove-Item -Recurse -Force gradle\local-repo\top

# 2. 把 libs.versions.toml 的 miuix 改成一个**远程还在**的版本，联网 sync 一次把它抓进缓存
#    （远程可用列表：
#     https://maven.pkg.github.com/compose-miuix-ui/miuix/top/yukonga/miuix/kmp/miuix-core-android/maven-metadata.xml
#     需要 gpr.user / gpr.key）

# 3. 把缓存里的新版本复制回来（只取 aar/pom/module，不要 sources）
$ver = '<新版本>'
$src = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\top.yukonga.miuix.kmp"
Get-ChildItem $src -Directory | ForEach-Object {
    $d = Join-Path $_.FullName $ver
    if (-not (Test-Path $d)) { return }
    $out = "gradle\local-repo\top\yukonga\miuix\kmp\$($_.Name)\$ver"
    New-Item -ItemType Directory -Force $out | Out-Null
    Get-ChildItem $d -Recurse -File | Where-Object { $_.Extension -in '.aar','.pom','.module' } |
        Copy-Item -Destination $out -Force
}

# 4. 同步改 libs.versions.toml；再确认新版本里有 ThemePaletteStyle.Cmf / ThemeColorSpec.Spec2026
```

## 注意

- **不要**把 `-sources.jar` 放进来（体积翻倍，构建用不到）。
- 升级前先确认新版本仍然有 `Cmf` 与 `Spec2026`：0.9.5-* 的已发布快照里**没有** `Spec2026`
  （只有 `Spec2021` / `Spec2025`），换上去会重新出现 `Unresolved reference 'Spec2026'`。
  校验方法：解 `miuix-ui-android-<ver>.aar` 里的 `classes.jar`，看
  `top/yukonga/miuix/kmp/theme/ThemeColorSpec.class` 的常量池里有没有 `Spec2026`。
