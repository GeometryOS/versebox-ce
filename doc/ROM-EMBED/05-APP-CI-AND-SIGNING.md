# app CI 与签名发布

根 app CI 共五个 workflow 文件：build.yml 调用三个可复用阶段，release.yml 在成功 tag workflow 后接续发布。每阶段单独显示、单独失败，任何失败均阻断后续 APK 和 Release。

| Workflow | 阶段 | 失败门禁 |
| --- | --- | --- |
| `build.yml` | 编排测试、ROM 准备和 Debug 构建；成功 tag 后由 release.yml 接力 | 任一依赖失败则整体失败 |
| `app-tests.yml` | 跑每个 Gradle 模块已有的 Kotlin/Java unit tests，上传 XML 与简明统计 | Gradle 测试任务非零即失败；报告上传不改变结果 |
| `app-rom-stage.yml` | 当前用 rom-embed.json 调用 stageRomAssets，并拒绝零 ROM staging；dotenv、pointer 和 audit intent gate 尚待接入 | 缺包或 staging 失败阻断后续 job |
| `app-debug-build.yml` | 编译 Debug APK，检查 APK 文件与内部 ROM 清单 | 构建或产物断言失败即失败 |
| `release.yml` | 解码 JKS、构建 full/minimal release、验签、生成 sums、发 Release | Secret 缺失、构建/验签/hash 错误均阻断发布 |

JKS 不入库。若 Miuix GitHub Packages 依赖仍为私有包，还需在包仓库设置中授权 VerseBox 仓库读取；Actions job 使用 packages: read 与 GITHUB_TOKEN。将 JKS 原文件 Base64 编码后设置为仓库或环境级 Actions secret `ANDROID_KEYSTORE_BASE64`；同时设置 `ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 与 `ANDROID_KEY_PASSWORD`。secret 只能由 release job 读取。建议用 PowerShell 编码，不把明文或编码结果写入仓库：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\secure\versebox-release.jks'))
```

把命令输出复制到 GitHub 仓库的 Settings > Secrets and variables > Actions > New repository secret，四个 secret 使用上表名称。Base64 不是加密；真正的保护来自 GitHub secret 存储与 Actions 日志脱敏。禁止 `echo` secret、启用 shell tracing 或上传解码后的文件。解码到 `$RUNNER_TEMP`，只供 Gradle 使用，构建后执行 `shred -u`（不可用时 `rm -f`），并设置 always cleanup。Release 输出只包含两个已验签 APK 和 `SHA256SUMS`。

本机没有维护者 JKS secret，因此只能验证 Gradle 的 debug 构建与 release 配置逻辑，不能声称完成签名 Release 实测。首次真实发布仍须由维护者推送后查看 Actions 运行结果。
