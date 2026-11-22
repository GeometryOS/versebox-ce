# APK 内容档位

Release 产物固定为两个 APK。两者都包含本次配置选中的每个 ROM 对应的 APK resident `.so`；区别仅在 ROM ZIP 内容。

| APK | ROM 内容 | resident `.so` | 用途 |
| --- | --- | --- | --- |
| full APK | 每个选中 ROM 的完整 ZIP | 清单验证过的 resident tar 内容 | 可直接使用已内置 ROM |
| minimal APK | 每个选中 ROM 的最小占位 ZIP | 同一组 resident tar 内容 | 声明支持的 ROM，客体内容由用户导入 |

同一 APK 只包含每个配置 id 的一个 `romKey`。未选中的版本不进入 `assets/`、`jniLibs/` 或 APK index。resident 文件按 ROM id 隔离提取后合并；若不同 ROM 对同一个 APK 路径提供不同字节，构建必须失败，禁止后写覆盖。

构建时先清空 app build 下专用 staging 目录，再按配置顺序挑选版本、验证清单、建立唯一输出目录。APK 内置 index 记录实际 `romKey`、指针选择结果和输入文件摘要。普通 `assembleDebug` 不签 release 密钥。Release variant 必须通过 GitHub Actions secrets 解码的 JKS 签名，并在工作流收尾删除 JKS 临时文件。

发布资产严格白名单：两个 APK 与 `SHA256SUMS`。不上传 ROM ZIP、resident tar、pack audit 清单、运行时 tar、报告或临时文件；ROM 仓库自己的 Release 独立发布这些 ROM 资产。
