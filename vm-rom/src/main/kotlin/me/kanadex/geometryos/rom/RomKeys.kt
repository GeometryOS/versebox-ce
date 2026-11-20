// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

// romKey 的拼接与比较。运行期主键是 id@version，旧格式没有版本号，key 就是 id
object RomKeys {
    const val LEGACY_VERSION = "legacy"

    private val VERSION = Regex("^[0-9]+(\\.[0-9]+)*$")
    private val ID = Regex("[a-z0-9][a-z0-9._-]*")

    // 拼出运行期主键。旧格式只返回 id
    fun key(id: String, version: String): String =
        if (version.isBlank() || version == LEGACY_VERSION) id else id + "@" + version

    fun idOf(key: String): String = key.substringBefore('@')

    fun versionOf(key: String): String = key.substringAfter('@', LEGACY_VERSION)

    fun isValidId(id: String): Boolean = ID.matches(id)

    fun isValidVersion(version: String): Boolean = VERSION.matches(version)

    // 按数值逐段比较，缺位补 0
    fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.toIntOrNull() ?: -1 }
        val b = right.split('.').map { it.toIntOrNull() ?: -1 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(index) { 0 }
            val bv = b.getOrElse(index) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}
