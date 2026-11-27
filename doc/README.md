# VerseBox CE 工程文档

本目录记录 app 侧构建与 ROM 入库的工程契约。客体运行时自身的源码、镜像构建和发布实现分别留在各自仓库；本目录只描述 app 与运行时发布物的交界，以及 app CI 如何消费它们。

## 章节

| 目录 | 内容 |
| --- | --- |
| [ROM-EMBED](ROM-EMBED/README.md) | ROM 版本选择、发布清单校验、APK stage 与 app CI |
| Wall of Shame | [01. Missing host window bridge](WALL-OF-SHAME/01-CI-APK-MISSING-WINDOW-BRIDGE.md) · [02. Unresolved A9 ROM preload](WALL-OF-SHAME/02-CI-A9-ROM-MISSING-HEALTHD-PRELOAD.md) |

## 仓库边界

| 仓库 | 责任 |
| --- | --- |
| VerseBox CE 根仓库 | app、版本选择输入、ROM staging、APK 构建与签名发布 |
| `guest-runtime-a9` | A9 客体构建、ROM 包和独立 Release |
| `guest-runtime-a10` | A10 客体构建、ROM 包和独立 Release |
| `guest-runtime-a15` | A15 客体构建、ROM 包和独立 Release |

三份运行时源码仓库互不作为 Gradle 子项目依赖。ROM Release 提供数据包与审计清单，app 只校验并消费发布物。
