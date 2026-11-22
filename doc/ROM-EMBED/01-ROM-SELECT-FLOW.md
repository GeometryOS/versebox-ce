# ROM 拉取流程

图中每条配置只处理一个 ROM id。固定版本和 `maxcv` 在解析阶段变成唯一 `romKey`，之后本地 dist 与远程 Release 走同一套清单验证和 staging。

```mermaid
flowchart TD
    A[读取 app/rom-embed.env] --> B{ROM id 配置完整?}
    B -- 否 --> X[配置错误，测试或构建失败]
    B -- 是 --> C[按 source 选择本地 dist 或 GitHub Release]
    C --> D[列举候选版本目录与 pack-audit-intent.json]
    D --> E{version 是固定值?}
    E -- 是 --> F[精确匹配 romKey]
    E -- 否，maxcv --> G[过滤 minAppVersionCode <= app versionCode]
    G --> H[按语义版本降序选最高兼容版本]
    F --> I{唯一匹配?}
    H --> I
    I -- 否 --> Y[缺失或歧义，失败并列出候选]
    I -- 是 --> J[校验清单 romKey 与 versionPointer]
    J --> K[核对产物数量、文件名、字节数、SHA-256]
    K --> L{全部一致?}
    L -- 否 --> Z[完整性失败，禁止进入 APK]
    L -- 是 --> M[仅 stage 选中的 ROM 包与 resident .so]
    M --> N{APK profile}
    N -- full --> O[完整 ROM ZIP + resident .so]
    N -- minimal --> P[最小占位 ZIP + resident .so]
    O --> Q[APK 内容审计与签名]
    P --> Q
    Q --> R[仅发布两种 APK 与 SHA256SUMS]
```

远程与本地只在候选产物的读取位置不同。版本选择、清单验证和失败策略保持一致，因此本地来源不会因为信任工作区而跳过哈希校验。下载失败、版本不存在、兼容版本为空、重复 `romKey`、文件计数不一致或哈希不匹配都会阻断 Release 构建。

流程图描述目标契约，不代表尚未验证的阶段已经在 GitHub Actions 实测通过；实现进度见 [06-IMPLEMENTATION-STATUS.md](06-IMPLEMENTATION-STATUS.md)。
