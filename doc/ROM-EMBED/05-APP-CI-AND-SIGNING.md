# app CI 与签名发布

根 app CI 共三个 workflow，**push/PR 只有 app-build 一个入口**：它的 tests job 以可复用工作流调用 app-tests，后者提供 policy 与 android 两个 job；之后 rom-stage → debug → audit 依次依赖。app-tests 只保留 `workflow_call` 与手动入口，不再自己监听 push，否则同一条测试命令会在一次提交里并发跑两遍。一次 push 是 1 个 run、5 个 check，tag 再加 app-release 的 release job。

| Workflow | 阶段 | 失败门禁 |
| --- | --- | --- |
| `app-tests.yml` | 可复用入口：policy（`:log-audit`/`:vm-display`/`:vm-rom`）与 android（`:guest-runtime`/`:app` 加 Rust 契约）两个 job，共用 JUnit 汇总 | 测试非零或无报告即失败 |
| `app-build.yml` | push/PR：tests → rom-stage → debug → audit 四段依赖 | 任一 job 失败则后续不执行 |
| `app-release.yml` | tag `vX.Y.Z`：自足构建 full/minimal、验签、发布 | Secret 缺失、构建/验签/hash 错误均阻断 |

`app-tests.yml` 是客户端业务逻辑的必需 CI 入口，`app-build.yml` 的 tests job 复用它，不再新增平行 workflow；两个 job 的汇总与报告步骤收敛在 `.github/actions/test-report`，artifact 名分别是 `app-test-results-policy` 与 `app-test-results-android`。ROM 行为测试使用 Robolectric/JVM 夹具和应用/系统临时目录，执行 :vm-rom:test、:guest-runtime:testDebugUnitTest 与 :app:testDebugUnitTest。`:vm-rom:test` 同时覆盖详情字段分组、legacy 版本标记过滤和缺失元数据省略。覆盖 ROM ZIP 导入、真实解包清单校验、RuntimeFiles.installData 从 ROM payload 补齐缺失/损坏的实例文件、RomRepository.rows 实际列表扫描和三项元信息、单台虚拟机只归属一行、解包体缺文件后的 ROM_REPAIR，以及解包目录不完整后的 ROM_REEXTRACT。每个操作日志须在记录详情归档中可读，重解包记录须早于后续 LAUNCH 收尾。测试只写 Robolectric 应用沙箱和系统临时目录，不接触维护者的 ROM、设备数据或 guest-runtime-a9/a10/a15 仓库。JUnit 报告缺失、测试数为零或失败均阻断 workflow。

## job 与外部依赖的边界

ROM staging 要访问三个运行时仓库的 Release 资产，只有拿到令牌的 job 才能执行；单测 job 必须完全离线。

| job | 网络 | GITHUB_TOKEN | 说明 |
| --- | --- | --- | --- |
| app-build / policy、android | 不使用 | 不注入 | 单测离线通过，命令带 `-x :app:stageRomAssets -x :app:verifyRomAssets` |
| app-build / rom-stage | GitHub API 与 Release 资产 | 必需，`contents: read` | 解析清单、逐文件校验 SHA-256、上传 staging |
| app-build / debug | 不使用 | 不注入 | 复用 artifact，`-x :app:stageRomAssets` 仍执行 verifyRomAssets 复核 |
| app-release | GitHub API 与 Release 资产 | 必需，`contents: write` | 构建 full 与 minimal 两个 APK 并发布 |

`:app:testDebugUnitTest` 的依赖链是 `preBuild → verifyRomAssets → stageRomAssets`。因此 `-x` 是单测 job 的契约，不是可选优化：漏掉它，tests job 会在无令牌的 runner 上请求 `https://api.github.com/repos/GeometryOS/VerseBox-guest-runtime-*/releases`，拿到 403 并把整条流水线打红。

令牌只由 Actions 注入（`GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}`）。禁止把令牌写进仓库、`app/rom-embed.env`、构建脚本常量或日志；`app/rom-embed.env` 只登记来源（仓库、版本、profile）。

## ROM staging 的失败判据

| 现象 | 判据 | 处理 |
| --- | --- | --- |
| `GitHub API 返回 403：未认证限流` | 令牌未注入且 `X-RateLimit-Remaining: 0` | 该 job 不应跑 staging，按上表把 staging 留给 rom-stage |
| `403：令牌的每小时额度已用尽` | 有令牌且额度为 0 | 等额度恢复；`_rom_cache` 内已有同版本产物时不会重复下载 |
| `403：令牌没有该仓库的读取权限` | 有令牌但额度非 0 | 补 `permissions: contents: read`；私有仓库还要在仓库设置里授权 |
| `404：仓库或 Release 不存在` | 状态码 404 | 核对 `rom-embed.env` 的仓库名与 Release 资产名 |
| 资产下载返回 403 或 400 | 重定向到对象存储后仍带 `Authorization` | 构建脚本按域决定是否携带该头，重定向由脚本自己跟随 |

## 本机复现

单测（离线，与 app-tests job 同参数）。Robolectric 的 Android SDK 镜像由根构建脚本的 `:stageRobolectricSdk` 预先解析到 `build/robolectric-sdk`，测试只读该目录，运行期不访问 Maven Central：

    .\gradlew.bat --no-daemon --console=plain :log-audit:test :vm-display:test :vm-rom:test :guest-runtime:testDebugUnitTest :app:testDebugUnitTest -x :app:stageRomAssets -x :app:verifyRomAssets -PciSkipNativeVerify=true

staging（需要三份 dist/<romKey>/ 与本机令牌，来源见 [02-ROM-SOURCE-CONFIG](02-ROM-SOURCE-CONFIG.md)）：

    .\gradlew.bat --no-daemon --console=plain :app:stageRomAssets :app:verifyRomAssets -PverseboxRomStrict=true

## 测试结果怎么读

| 位置 | 内容 | 来源 |
| --- | --- | --- |
| Job 日志 | 每条用例一行 `类名 > 方法 PASSED/FAILED`，失败附完整栈与原因 | 根 build.gradle.kts 的 testLogging |
| Job Summary | 每个模块的套件数、用例数、通过、失败、错误、跳过、耗时，以及失败用例清单 | 汇总步骤写入 GITHUB_STEP_SUMMARY |
| Checks 页 | 按套件展开的用例列表与行内注解 | dorny/test-reporter 读取 JUnit XML |
| artifact | `build/reports/tests/**` 的 HTML 报告，以及原始 XML | 上传步骤 |

JUnit XML 是给机器读的中间格式：浏览器打开只显示 "This XML file does not appear to have any style information"，因为 XML 没有附带样式表。这不是测试失败，也不是上传损坏。要读人话就看 Job Summary、Job 日志里逐条 PASSED 行，或下载 artifact 里的 HTML 报告。

testLogging 只在根 build.gradle.kts 配一次，五个模块共用；新模块不需要重复配置，也不会出现"有的模块有逐条输出、有的没有"。

本机验证：`:vm-display:test --rerun-tasks` 打印四行 `DisplayPolicyTest > 方法 PASSED`；用 app-test-results 的 18 个 XML 跑汇总脚本，得到 5 个模块共 94 项、13.2 s 的表格。Checks 步骤依赖 Actions 的 checks: write，本机无法验证。

JKS 不入库。若 Miuix GitHub Packages 依赖仍为私有包，还需在包仓库设置中授权 VerseBox 仓库读取；Actions job 使用 packages: read 与 GITHUB_TOKEN。将 JKS 原文件 Base64 编码后设置为仓库或环境级 Actions secret `ANDROID_KEYSTORE_BASE64`；同时设置 `ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 与 `ANDROID_KEY_PASSWORD`。secret 只能由 release job 读取。建议用 PowerShell 编码，不把明文或编码结果写入仓库：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\secure\versebox-release.jks'))
```

把命令输出复制到 GitHub 仓库的 Settings > Secrets and variables > Actions > New repository secret，四个 secret 使用上表名称。Base64 不是加密；真正的保护来自 GitHub secret 存储与 Actions 日志脱敏。禁止 `echo` secret、启用 shell tracing 或上传解码后的文件。解码到 `$RUNNER_TEMP`，只供 Gradle 使用，构建后执行 `shred -u`（不可用时 `rm -f`），并设置 always cleanup。Release 输出只包含两个已验签 APK 和 `SHA256SUMS`。

本机没有维护者 JKS secret，因此只能验证 Gradle 的 debug 构建与 release 配置逻辑，不能声称完成签名 Release 实测。首次真实发布仍须由维护者推送后查看 Actions 运行结果。
