# 实施状态

状态按本次实际工作区代码记录，不能据设计草图推断为已完成。

## 已落地

| 项 | 代码落点 | 本地判据 |
| --- | --- | --- |
| 根 Gradle 不再配置 A9/A15 兄弟仓库 | settings.gradle.kts | include 列表只列 app 工程模块 |
| app workflow 拆成五个文件 | .github/workflows/ | build.yml 编排测试、ROM staging、Debug；release workflow 只在成功 tag workflow 后运行 |
| 测试失败阻断 app CI | app-tests.yml | log-audit、vm-display、vm-rom 三个模块测试非零退出即失败，JUnit XML 在 always 阶段上传 |
| release variant 私钥从环境读取 | app/build.gradle.kts、release.yml | 只有 requireReleaseSigning=true 时缺 signing 环境变量会配置失败 |
| full/minimal Gradle profile 覆盖 | app/build.gradle.kts | verseboxRomProfile 参数改变本次 stage 选中的 ROM ZIP |
| 本机签名文件不入仓库 | .gitignore | 现有规则忽略 *.jks 与 *.keystore |

## 尚未落地

| 项 | 当前事实 | 完成标准 |
| --- | --- | --- |
| .env.example / .env ROM 输入 | 已落地：app/rom-embed.env.example 跟踪默认 GitHub 来源，app/rom-embed.env 本机配置（gitignore）；旧 JSON 已移除 | 首次构建从 example 生成 .env，CI 不缓存 .env |
| 固定版本与 maxcv | 已落地并本地验证：env 的 VERSION 支持固定版本或 maxcv；选择读 pack-audit-intent.json，过滤 minAppVersionCode 后取最高版本；固定版本也受下限约束 | 夹具验证：maxcv 跳过不兼容版本、固定不兼容版本被拒、SHA 篡改被拒 |
| pack-audit-intent.json 与文件 SHA/大小 | 三仓库已对齐：dist/<romKey>/ 隔离、自动生成 pack-audit-intent.json（无 versionPointer）、逐文件 bytes/SHA-256 | 三仓库各自 CI 实测通过后再签核 |
| app ROM 拉取失败闸门 | 当前 staging 仍允许远程拉取失败后跳过 source；新 workflow 只额外阻止零 ROM 结果 | 缺版本、下载失败、兼容性不符、文件数量/字节/SHA 不一致均失败 |
| root doc 与 ROM 仓库 doc 同步 | root doc/ 和 A10 doc/ 均已建立；A10 doc 记录的是未推送未实测改动 | A9/A15 各自文档同步后再验三仓库发布闭环 |
| GitHub Actions 线上运行与签名 Release | 本机无 Actions run，也没有维护者 JKS secrets | 推送后五阶段 CI 全绿；设置 secret 后验证两种 APK 签名和 release assets |

## 当前 CI 验证边界

2026-11-25 本地按 app-tests 参数运行 `:vm-rom:test :app:testDebugUnitTest -x :app:stageRomAssets -x :app:verifyRomAssets -PciSkipNativeVerify=true`，BUILD SUCCESSFUL；新增详情分组字段与 legacy 缺失值测试通过。随后按本机三仓库 dist 执行 `:app:stageRomAssets :app:verifyRomAssets :app:assembleDebug -PverseboxRomStrict=true -PciSkipNativeVerify=true`，严格 staging 和 Debug 构建成功。APK 审计确认 ROM 索引、3 个完整 ROM ZIP 与 `libversebox_kanadex_logo.so` 均存在，大小 1,985,380,722 B，SHA-256 为 `7A5BC9EA3ED4E0B3D9007E60DF73CBD9F5F837A49BF2EF0151FE5183F54F4213`。CI 参数跳过 A10 guest runtime 校验，因此该 APK 不含 guest runtime 核心库，只用于 app 与 ROM 管理 UI 测试，不用于启动客体。已单次执行 `adb -s 8ac2ffd3 install -r`，设备返回 Success，versionCode 105，lastUpdateTime 为 2026-11-25 23:47:44；未启动 App 或虚拟机，等待维护者目视验收。

本次未运行 GitHub Actions；线上 runner 能否安装 compileSdk 37、Maven Packages 凭据是否具备仍待线上 workflow 验证。

app/rom-embed.env 是本机配置，指向三个独立 guest-runtime 仓库的本地 dist；本次未改该配置或任何 guest-runtime 仓库源码。A15 的 doc/RELEASE/ARTIFACTS.md 未触碰。

## 2026-11-22 追加：参考 APK 兜底已移除

- 根 `build.gradle.kts` 删除 `bootstrapBundledRuntime` / `cleanRuntimeInputs`，不再从 `_bin/A10VM.apk` 解包 `runtime-inputs/`。
- `:guest-runtime` 只接受 `-PguestRuntimePrepared` 或 `guest-runtime-a10/prepared-apk`；缺失时给出明确错误。
- `:native-display` 只接受 `-PnativeDisplayPrepared`、源码构建产物或旧暂存目录；不再回落到捆绑 APK。
- 已用 `:app:help` 验证 Gradle 配置通过；未做完整 APK 与真机验证。
