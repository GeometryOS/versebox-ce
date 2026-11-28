<div align="center">
  <img src="doc/assets/versebox-icon.svg" alt="VerseBox 应用图标" width="120" height="120">
</div>

<h1 align="center">VerseBox CE</h1>
<p align="center">在安卓设备上运行独立的安卓虚拟机</p>

<p align="center">
  <a href="https://github.com/GeometryOS/versebox-ce/actions/workflows/app-build.yml"><img alt="构建状态" src="https://github.com/GeometryOS/versebox-ce/actions/workflows/app-build.yml/badge.svg?branch=main"></a>
  <a href="LICENSE"><img alt="自定义只读许可" src="https://img.shields.io/badge/%E8%AE%B8%E5%8F%AF-%E5%8F%AA%E8%AF%BB%E5%8F%82%E8%80%83-4D6BFE"></a>
</p>

VerseBox CE 是一款好用的安卓虚拟机 app，并面向各种 Android9 以上的设备喵

它无需 root 即可在同一台设备上创建和管理多台虚拟机，各实例保有自己的应用、数据和显示设置等

## 核心功能

- **多实例**：在同一台设备上创建多台虚拟机，分别选择 ROM 和设置分辨率。
- **ROM 管理**：导入兼容的 ROM ZIP，管理同系列的多个版本，检查 ROM 完整性，并在需要时重新解包。
- **备份与恢复**：备份虚拟机的数据和设置，通过备份创建独立实例。
- **双主题与运行记录**：提供 Miuix 和 Material 3 Expressive 两套界面，可查看启动与操作记录。

## 快速上手

宿主设备须运行 **Android 9（API 28）或更高版本**，并支持 **ARM64（arm64-v8a）**。实际兼容性随宿主设备和客体 ROM 版本而异。

1. 在 [官方发布页](https://github.com/GeometryOS/versebox-ce/releases) 查看是否有可用 APK。
2. 安装后打开「ROM 管理」。已有内置 ROM 时可直接选择，也可从下方客体仓库获取匹配的 ROM ZIP 后导入。
3. 在「虚拟机」页新建实例，选择 ROM 并启动。首次使用该 ROM 时，应用会按需解包并检查完整性。

## 官方客体 ROM 与构建

本仓库负责宿主应用和 APK。各客体运行时独立维护、构建和发布 ROM，宿主应用只校验和使用对应产物。客体功能与已知限制以各仓库的文档为准。

| 客体版本 | 独立仓库 |
| --- | --- |
| Android 9（API 28） | [guest-runtime-a9](https://github.com/GeometryOS/VerseBox-guest-runtime-a9) |
| Android 10（API 29） | [guest-runtime-a10](https://github.com/GeometryOS/VerseBox-guest-runtime-a10) |
| Android 15（API 35） | [guest-runtime-a15](https://github.com/GeometryOS/VerseBox-guest-runtime-a15) |

ROM 来源配置、APK 构建及持续集成流程见[工程文档](doc/README.md)。

## 维护与反馈

主要作者与维护者为 [@kanadex](https://github.com/kanadex)。反馈问题时，请在 [GitHub 问题区](https://github.com/GeometryOS/versebox-ce/issues) 提供宿主机型、系统版本、ROM 版本和复现步骤。

## 许可协议

> [!IMPORTANT]
> 适用自定义条款的 VerseBox CE 源码与资料仅供人工在线查阅。再分发、自动抓取和 AI／机器学习用途受条款限制。适用范围、完整限制及例外以英文 [LICENSE](LICENSE) 为准。
>
> 第三方组件及独立客体 ROM 继续适用各自条款。