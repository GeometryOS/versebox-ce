// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 VerseBox contributors
package io.a10vm.core.env

import io.a10vm.core.device.model.Level
import me.kanadex.versebox.BuildConfig

/**
 * 编译期常量。对应 InstallerX 的 core/env/AppConfig：
 * 只暴露「关于页要显示的版本信息」和「是否启用文件日志」这类与构建有关的事实。
 *
 * VerseBox 的运行记录走记录中心（RecordCollector → records.db + 归档目录），
 * 不再另开一套文件日志，所以 [isLogEnabled] 恒为 false，关于页的日志开关按原逻辑不显示。
 */
object AppConfig {
    val LEVEL: Level = if (BuildConfig.DEBUG) Level.UNSTABLE else Level.STABLE
    val VERSION_NAME: String = BuildConfig.VERSION_NAME
    val VERSION_CODE: Long = BuildConfig.VERSION_CODE.toLong()
    const val isLogEnabled: Boolean = false
}
