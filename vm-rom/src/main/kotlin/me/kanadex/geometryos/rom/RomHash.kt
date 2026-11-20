// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import java.io.File
import java.security.MessageDigest

/**
 * payload 的规范化 SHA-256。必须和各运行时仓库里的 rom/rom_hash.py 逐字节一致，
 * 打包器和解包器共用同一份算法，否则同一个 payload 换一种压法就会假失败。
 *
 * 算法是把文件按相对路径的 UTF-8 字节序排好，依次喂路径、0、字节数、0、文件内容。
 */
object RomHash {

    // isCancelled 每读一块查一次。用户点停止时要能立刻退出，不然会卡在正在校验
    fun payloadSha256(payloadRoot: File, isCancelled: () -> Boolean = { false }): Pair<String, Int> {
        val entries = payloadRoot.walkTopDown()
            .filter { it.isFile }
            .map { file -> relativePath(payloadRoot, file) to file }
            .toList()
            .sortedWith { left, right -> compareBytes(left.first, right.first) }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        for ((rel, file) in entries) {
            if (isCancelled()) throw RomCancelled()
            digest.update(rel.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.length().toString().toByteArray(Charsets.US_ASCII))
            digest.update(0)
            file.inputStream().use { input ->
                while (true) {
                    if (isCancelled()) throw RomCancelled()
                    val count = input.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().toHex() to entries.size
    }

    fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun relativePath(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar).replace('\\', '/')

    private fun compareBytes(left: String, right: String): Int {
        val a = left.toByteArray(Charsets.UTF_8)
        val b = right.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(a.size, b.size)) {
            val av = a[index].toInt() and 0xFF
            val bv = b[index].toInt() and 0xFF
            if (av != bv) return av - bv
        }
        return a.size - b.size
    }
}

// 字节数组转小写十六进制，全项目都用这一份
fun ByteArray.toHex(): String {
    val builder = StringBuilder(size * 2)
    for (value in this) builder.append(String.format("%02x", value.toInt() and 0xFF))
    return builder.toString()
}
