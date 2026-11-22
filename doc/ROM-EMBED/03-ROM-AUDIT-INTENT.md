# ROM 发布审计清单

`pack-audit-intent.json` 是 ROM 打包器在 Release 构建时自动生成的消费清单。工作区不得手写它。清单位于单个 `romKey` 的隔离产物目录，文件本身不带 ROM 名称或版本后缀；外层目录已经提供身份。

```json
{
  "schema": "versebox-rom-pack-audit-intent/1",
  "romKey": "android10@1.0.1",
  "minAppVersionCode": 105,
  "packSeconds": 123.4,
  "files": [
    { "name": "versebox-android10-1.0.1.zip", "bytes": 123, "sha256": "..." },
    { "name": "versebox-android10-1.0.1-min.zip", "bytes": 123, "sha256": "..." },
    { "name": "apk-resident-libs-android10-1.0.1.tar", "bytes": 123, "sha256": "..." }
  ]
}
```

`romKey` 必须与 ROM ZIP 内的 `id` 与 `romVersion` 完全一致。清单只声明发布事实，不声明选包策略：`maxcv` 是 **app 侧**的 `VERSION` 取值，ROM 不得写入 `versionPointer`。候选条件是 `minAppVersionCode <= app versionCode`；固定版本同样必须满足该下限，不能用固定版本绕过兼容性闸门。

`minAppVersionCode` 是 ROM 发布者设置的下限，app 从 `app/build.gradle.kts` 的 `versionCode` 读取当前值。解析要求正整数。`files` 必须精确列出该发布中供 app 消费的每个 ZIP、resident tar 和占位 ZIP；不能多、不能少。每项的字节数和 SHA-256 都由打包器对最终文件计算。`residentLibs` 直接由 tar 文件成员清单表达，不再单列一份会漂移的名字数组。

ROM 仓库可以保留面向人工下载的文件名 `versebox-<id>-<version>...`，以便用户辨认来源。所有文件存放在 `dist/<romKey>/`，不同版本不会互相覆盖或散落在一个平面目录。
