# app CI 与签名发布

根 app CI 共三个**独立** workflow：app-tests（push/PR）与 app-build（push/PR，内部 tests → rom-stage → debug → audit 依次依赖）；app-release 由 tag 推送触发。push 上显示 2/2，tag 上 3/3。

| Workflow | 阶段 | 失败门禁 |
| --- | --- | --- |
| `app-tests.yml` | push/PR：模块单测 + JUnit 汇总 | 测试非零或无报告即失败 |
| `app-build.yml` | push/PR：tests → rom-stage → debug → audit 四段依赖 | 任一 job 失败则后续不执行 |
| `app-release.yml` | tag `vX.Y.Z`：自足构建 full/minimal、验签、发布 | Secret 缺失、构建/验签/hash 错误均阻断 |

`app-tests.yml` 是客户端业务逻辑的必需 CI 入口，`app-build.yml` 的 tests job 复用它，不再新增平行 workflow。ROM 行为测试使用 Robolectric/JVM 夹具和应用/系统临时目录，执行 :vm-rom:test、:guest-runtime:testDebugUnitTest 与 :app:testDebugUnitTest。覆盖 ROM ZIP 导入、真实解包清单校验、RuntimeFiles.installData 从 ROM payload 补齐缺失/损坏的实例文件、RomRepository.rows 实际列表扫描和三项元信息、单台虚拟机只归属一行、解包体缺文件后的 ROM_REPAIR，以及解包目录不完整后的 ROM_REEXTRACT。每个操作日志须在记录详情归档中可读，重解包记录须早于后续 LAUNCH 收尾。测试只写 Robolectric 应用沙箱和系统临时目录，不接触维护者的 ROM、设备数据或 guest-runtime-a9/a10/a15 仓库。JUnit 报告缺失、测试数为零或失败均阻断 workflow。

JKS 不入库。若 Miuix GitHub Packages 依赖仍为私有包，还需在包仓库设置中授权 VerseBox 仓库读取；Actions job 使用 packages: read 与 GITHUB_TOKEN。将 JKS 原文件 Base64 编码后设置为仓库或环境级 Actions secret `ANDROID_KEYSTORE_BASE64`；同时设置 `ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 与 `ANDROID_KEY_PASSWORD`。secret 只能由 release job 读取。建议用 PowerShell 编码，不把明文或编码结果写入仓库：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\secure\versebox-release.jks'))
```

把命令输出复制到 GitHub 仓库的 Settings > Secrets and variables > Actions > New repository secret，四个 secret 使用上表名称。Base64 不是加密；真正的保护来自 GitHub secret 存储与 Actions 日志脱敏。禁止 `echo` secret、启用 shell tracing 或上传解码后的文件。解码到 `$RUNNER_TEMP`，只供 Gradle 使用，构建后执行 `shred -u`（不可用时 `rm -f`），并设置 always cleanup。Release 输出只包含两个已验签 APK 和 `SHA256SUMS`。

本机没有维护者 JKS secret，因此只能验证 Gradle 的 debug 构建与 release 配置逻辑，不能声称完成签名 Release 实测。首次真实发布仍须由维护者推送后查看 Actions 运行结果。
