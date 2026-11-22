# ROM 入库与 APK 构建

本章说明 app 如何从本地 dist 或 GitHub Release 选中 ROM 版本，如何检查完整性，以及哪些文件进入两种 APK。配置以 ROM id 为键，ROM 数量不设上限，来源可以逐条混合。

## 阅读顺序

| 文件 | 内容 |
| --- | --- |
| [01-ROM-SELECT-FLOW.md](01-ROM-SELECT-FLOW.md) | 拉取与版本解析流程图，包含失败出口 |
| [02-ROM-SOURCE-CONFIG.md](02-ROM-SOURCE-CONFIG.md) | `.env.example` / `.env` 配置和本机覆盖语义 |
| [03-ROM-AUDIT-INTENT.md](03-ROM-AUDIT-INTENT.md) | ROM 仓库生成的清单、SHA-256 与最小 app 版本 |
| [04-APK-PROFILES.md](04-APK-PROFILES.md) | full/minimal APK 的文件构成与隔离规则 |
| [05-APP-CI-AND-SIGNING.md](05-APP-CI-AND-SIGNING.md) | 五个 workflow 文件、测试门禁、JKS secret 发布 |
| [06-IMPLEMENTATION-STATUS.md](06-IMPLEMENTATION-STATUS.md) | 当前代码状态、实测记录与未完成项 |

## 两类版本约束

ROM 版本指针属于 app 的入库输入，每个 ROM 独立设置为精确版本或 `maxcv`。`maxcv` 只由 ROM 发布清单中的 `minAppVersionCode` 对照 app 当前 `versionCode` 得出；app 侧不设 ROM 上限。

ROM 仓库维护者在 `rom/rom.properties.yaml` 写 `minAppVersionCode`。Release 时打包器将值复制进自动生成的 `pack-audit-intent.json`。清单随对应 ROM 版本产物一起发布，源代码树不手写该文件。
