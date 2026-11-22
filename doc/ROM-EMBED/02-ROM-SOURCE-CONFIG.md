# ROM 来源配置

app 的跟踪配置为 `app/rom-embed.env.example`，本机实际配置为 `app/rom-embed.env`。二者关系与 `.env.example` 和 `.env` 相同：第一次构建可从 example 初始化实际文件，之后只在实际文件不存在时创建，不能用 example 覆盖维护者本机自定义值。CI 每次 clean checkout 都从当前 example 初始化，不缓存实际配置。

配置使用 dotenv 风格的 `KEY=value`，ROM 条目用连续序号建模，避免把 A9、A10、A15 写进代码，也不限制 ROM 数量：

```dotenv
ROM_SOURCE_1_ID=android10
ROM_SOURCE_1_TYPE=local
ROM_SOURCE_1_PATH=guest-runtime-a10/dist
ROM_SOURCE_1_VERSION=maxcv
ROM_SOURCE_1_PROFILE=full

ROM_SOURCE_2_ID=android15
ROM_SOURCE_2_TYPE=github
ROM_SOURCE_2_REPOSITORY=GeometryOS/VerseBox-guest-runtime-a15
ROM_SOURCE_2_VERSION=1.0.1
ROM_SOURCE_2_PROFILE=minimal
```

`TYPE` 只决定候选文件来自本地目录还是 GitHub Release。`VERSION` 是精确 ROM 语义版本，或 `maxcv`。`PROFILE` 可在每个 ROM 上选择 full、minimal 或 libs；最终 Release 工作流分别构建 full 和 minimal 组合，CI profile 不得隐式改变 ROM 清单。唯一身份来自 `ID` 和包内 `romVersion`，没有冗余的 `name` 字段。

`.env` 不是发布的完整性边界。每个候选版本仍需提供 `pack-audit-intent.json` 并通过 SHA-256 校验。相对本地路径相对 app 仓库根解析；路径缺失、条目序号断档、重复 id、未知来源或未知 profile 都按配置错误处理。

旧的 `rom-embed.json` / `rom-embed.local.json` 只作为迁移输入处理，不再作为目标配置格式。迁移期间保留已有本机文件，不自动删除、不自动覆盖；完成迁移后由维护者自行移除旧文件。
