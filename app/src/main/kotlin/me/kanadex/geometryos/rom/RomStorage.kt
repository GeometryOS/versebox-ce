// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import android.content.Context
import android.os.StatFs
import android.system.Os
import java.io.File

// app 侧把目录、剩余空间和可执行位注入给 :vm-rom，不含业务判断
object RomStorage {

    // 内置 ROM 的入口目录，常量只在 :vm-rom 定义一次
    const val ASSET_DIR = RomLayout.ASSET_DIR

    // 构建期为内置包生成的索引文件
    const val INDEX_NAME = RomLayout.INDEX_NAME

    const val PROPERTIES = RomLayout.PROPERTIES
    const val INPUTS = RomLayout.INPUTS
    const val PAYLOAD = RomLayout.PAYLOAD
    const val PAYLOAD_DIR = RomLayout.PAYLOAD_DIR
    const val LIB_DIR = RomLayout.LIB_DIR

    fun layout(context: Context): RomLayout = RomLayout(File(context.filesDir, "roms"))

    // 解包器。app 提供空间和可执行位，解包流程在 :vm-rom
    fun extractor(context: Context): RomExtractor = RomExtractor(
        layout = layout(context),
        freeBytes = { runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(-1L) },
        chmodExecutable = { file -> runCatching { Os.chmod(file.path, 0b111_101_101) } },
    )

    fun root(context: Context): File = layout(context).root

    fun archives(context: Context): File = layout(context).archives()

    // 旧布局目录，新代码用 layout.dirFor(key, payloadSha256)
    fun dir(context: Context, key: String): File = layout(context).dir(key)

    // 旧布局的原包，新代码用 layout.archiveFor(key, payloadSha256)
    fun archive(context: Context, key: String): File = layout(context).archive(key)

    fun properties(context: Context, dir: File): File = layout(context).properties(dir)

    fun inputs(context: Context, dir: File): File = layout(context).inputs(dir)

    fun payload(context: Context, dir: File): File = layout(context).payload(dir)

    fun payloadLib(context: Context, dir: File): File = layout(context).payloadLib(dir)

    // 这个目录是不是完整的运行形态。只按 key 猜会拿到另一份内容
    fun isExtracted(context: Context, dir: File): Boolean = layout(context).isExtracted(dir)

    fun isValidKey(key: String): Boolean = RomLayout(File(".")).isValidKey(key)
}
