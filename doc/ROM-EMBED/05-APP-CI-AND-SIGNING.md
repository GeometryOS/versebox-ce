# app CI 与签名发布

根 app CI 共五个**独立** workflow：app-tests 为入口（push/PR 触发），经 workflow_run 依次触发 app-rom-stage、app-debug-build、app-apk-audit；release 由 tag 推送直接触发。每阶段单独显示、单独失败，任何失败均阻断后续 APK 和 Release。

| Workflow | 阶段 | 失败门禁 |
| --- | --- | --- |
| `app-tests.yml` | 入口：push/PR 触发，跑模块单测并汇总 JUnit | 测试非零或零报告即失败 |
| `app-tests.yml` | 跑每个 Gradle 模块已有的 Kotlin/Java unit tests，上传 XML 与简明统计 | Gradle 测试任务非零即失败；报告上传不改变结果 |
| `app-rom-stage.yml` | tests 成功后触发：读 rom-embed.env，按 VERSION 选包并校验清单 | 缺清单、版本不兼容或哈希不符即失败 |
| `app-debug-build.yml` | stage 成功后触发：取回 staging 并构建 Debug APK | 构建失败即失败 |
| `app-apk-audit.yml` | debug 成功后触发：审计 APK 内 ROM index 与 lib/*.so | 缺 index 或 lib 为空即失败 |
| `release.yml` | tag 推送触发：自足构建 full/minimal、验签、发 Release | Secret 缺失、构建/验签/hash 错误均阻断发布 |

JKS 不入库。若 Miuix GitHub Packages 依赖仍为私有包，还需在包仓库设置中授权 VerseBox 仓库读取；Actions job 使用 packages: read 与 GITHUB_TOKEN。将 JKS 原文件 Base64 编码后设置为仓库或环境级 Actions secret `ANDROID_KEYSTORE_BASE64`；同时设置 `ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 与 `ANDROID_KEY_PASSWORD`。secret 只能由 release job 读取。建议用 PowerShell 编码，不把明文或编码结果写入仓库：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\secure\versebox-release.jks'))
```

把命令输出复制到 GitHub 仓库的 Settings > Secrets and variables > Actions > New repository secret，四个 secret 使用上表名称。Base64 不是加密；真正的保护来自 GitHub secret 存储与 Actions 日志脱敏。禁止 `echo` secret、启用 shell tracing 或上传解码后的文件。解码到 `$RUNNER_TEMP`，只供 Gradle 使用，构建后执行 `shred -u`（不可用时 `rm -f`），并设置 always cleanup。Release 输出只包含两个已验签 APK 和 `SHA256SUMS`。

本机没有维护者 JKS secret，因此只能验证 Gradle 的 debug 构建与 release 配置逻辑，不能声称完成签名 Release 实测。首次真实发布仍须由维护者推送后查看 Actions 运行结果。
