// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import java.io.File

/**
 * ROM 在磁盘上的布局，一个运行形态加一个包缓存。
 *
 * archives/<romKey>@<sha8>.zip 是原包，<romKey>@<sha8>/ 是解包后的运行形态，
 * 运行时只读后者。不带指纹的 <romKey>/ 是旧布局，只读不再写。解包先落 .tmp 再改名，
 * 替换时先进 .trash 再删。
 *
 * 同一个 romKey 的两份不同内容落在两个目录，切换版本是真的换目录。
 * 这一层不认识 Android，传进来的就是 app 的 filesDir/roms。
 */
class RomLayout(val root: File) {

    companion object {
        // 内置 ROM 的入口目录，相对 assets
        const val ASSET_DIR = "kanadex-rom"

        // 构建期为内置包生成的索引，放在 assets/<ASSET_DIR>/index.json
        const val INDEX_NAME = "index.json"

        const val PROPERTIES = "versebox_properties.yaml"
        const val INPUTS = "versebox_inputs.json"
        const val PAYLOAD = "payload"
        const val PAYLOAD_DIR = "payload/"
        const val LIB_DIR = "payload/lib"
        const val ARCHIVES = "archives"

        // 指纹取前 8 位，够区分也不算长
        const val PAYLOAD_TAG_LENGTH = 8

        // 目录名和包名里的分段符
        const val PAYLOAD_TAG_SEPARATOR = "@"

        // 取内容指纹的前 8 位。没有指纹就返回空串，调用方据此退回旧布局命名
        fun payloadTag(payloadSha256: String): String {
            val clean = payloadSha256.trim().lowercase()
            return if (clean.isEmpty()) "" else clean.take(PAYLOAD_TAG_LENGTH)
        }
    }

    fun archives(): File = File(root, ARCHIVES)

    // 旧布局目录，不带指纹。只用来读迁移前那一份或迁移后清理，写入一律用 dirFor
    fun dir(key: String): File = File(root, key)

    // 旧布局的原包，只读兼容，写入用 archiveFor
    fun archive(key: String): File = File(archives(), key + ".zip")

    // 内容寻址的目录名。没有指纹就退回 key，也就是旧布局
    fun dirName(key: String, payloadSha256: String): String {
        val tag = payloadTag(payloadSha256)
        return if (tag.isEmpty()) key else key + PAYLOAD_TAG_SEPARATOR + tag
    }

    // 唯一的写入目标
    fun dirFor(key: String, payloadSha256: String): File = File(root, dirName(key, payloadSha256))

    // 原包的路径
    fun archiveFor(key: String, payloadSha256: String): File =
        File(archives(), dirName(key, payloadSha256) + ".zip")

    fun tmpDir(key: String, stamp: Long): File = File(root, ".tmp-" + key + "-" + stamp)

    fun trashDir(key: String, stamp: Long): File = File(root, ".trash-" + key + "-" + stamp)

    fun properties(dir: File): File = File(dir, PROPERTIES)

    fun inputs(dir: File): File = File(dir, INPUTS)

    fun payload(dir: File): File = File(dir, PAYLOAD)

    fun payloadLib(dir: File): File = File(dir, LIB_DIR)

    // 量一次目录多大，只在安装和删除时用，不要放进每次扫描
    fun measuredBytes(dir: File): Long {
        if (!dir.isDirectory) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    // 三件必需物都在才算能运行。按目录判断，不要按键猜名字，同一个 key 可能有两个目录
    fun isExtracted(dir: File): Boolean =
        File(dir, PROPERTIES).isFile && File(dir, INPUTS).isFile && File(dir, PAYLOAD).isDirectory

    // 能当目录名用。只允许字母数字和 . _ - @
    fun isValidKey(key: String): Boolean = isValidName(key, 96)

    // 写入磁盘的目录名，字符规则同上。长度按键加分隔符加指纹放宽，否则长 id 会在解包时被判非法
    fun isValidDirName(name: String): Boolean = isValidName(name, 96 + 1 + PAYLOAD_TAG_LENGTH)

    private fun isValidName(name: String, maxLength: Int): Boolean =
        name.isNotEmpty() && name.length <= maxLength && name.all { ch ->
            ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == '@'
        }
}
