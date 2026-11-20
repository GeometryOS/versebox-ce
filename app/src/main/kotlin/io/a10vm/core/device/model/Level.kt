// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 VerseBox contributors
package io.a10vm.core.device.model

/** 构建等级。关于页据此显示「正式版 / 预览版 / 测试版」，与 InstallerX 的 Level 同名同义。 */
enum class Level {
    STABLE,
    PREVIEW,
    UNSTABLE,
}
