// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

/**
 * 判断一台实例属于列表里的哪一行，以及一行有几台在用。
 *
 * 扫描时算使用台数、运行时按身份取行都用这一份。写成两份，就会出现列表说
 * 一台在用、启动却解析到另一行。一台实例只归属一行，同版本号的本地行与内置行
 * 不是同一个版本的两半，不能各加一次。
 */
object RomUsage {

    /**
     * 这个解包目录是不是某个内置包解出来的。
     * 解包只是内置包的一个状态，不是又新建了一份本地 ROM；少了这一条，
     * 扫描会把解包体再补成一行本地 ROM，用户看到的是解包完多出一个还能删的本地 ROM。
     * 只比身份，不看目录名，目录名是内容寻址的实现细节。
     */
    fun belongsToBuiltin(
        key: String,
        payloadSha256: String,
        builtin: List<RomRef>,
    ): Boolean = builtin.any { candidate ->
        candidate.key == key &&
            (candidate.payloadSha256.isEmpty() || payloadSha256.isEmpty() ||
                candidate.payloadSha256 == payloadSha256)
    }

    /**
     * 找到这台实例绑定的那一行。rows 要按运行时解析顺序传，内置在前；
     * 旧实例只记了 rom_id，取第一条匹配的，这样列表里的台数和启动解析才是同一行。
     */
    fun owner(
        rows: List<RomRow>,
        romId: String,
        payloadSha256: String,
        source: String,
    ): RomRow? {
        if (romId.isEmpty()) return null
        return rows.firstOrNull { row ->
            when (row.source) {
                // 旧格式跟着 APK 的 assets 走，只有 rom_id 等于旧 id 才算
                RomSource.Legacy -> romId == row.id
                else ->
                    romId == row.key &&
                        (payloadSha256.isEmpty() || row.payloadSha256 == payloadSha256) &&
                        (source.isEmpty() || row.source.name == source)
            }
        }
    }
}
