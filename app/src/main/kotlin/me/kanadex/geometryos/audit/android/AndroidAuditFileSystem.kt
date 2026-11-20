// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.audit.android

import me.kanadex.geometryos.audit.port.AuditFileSystem
import me.kanadex.geometryos.audit.port.FsEntry
import java.io.File
import java.io.RandomAccessFile

/**
 * [AuditFileSystem] 的 Android/File 实现（docs/log-audit/04 §2 的适配层）。
 *
 * 这是**唯一**允许在审计链路里 import `java.io` 的地方：`:log-audit` 只声明接口，
 * 因此"谁能删文件"永远由库里的 policy 决定，而不是某个业务类顺手 delete。
 */
class AndroidAuditFileSystem : AuditFileSystem {
    override fun exists(path: String): Boolean = path.isNotEmpty() && File(path).exists()

    override fun isDirectory(path: String): Boolean = path.isNotEmpty() && File(path).isDirectory

    override fun size(path: String): Long = File(path).let { if (it.isFile) it.length() else 0L }

    override fun modifiedAt(path: String): Long = File(path).let { if (it.exists()) it.lastModified() else 0L }

    override fun list(directory: String): List<FsEntry> {
        val dir = File(directory)
        if (!dir.isDirectory) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        return children.map { child ->
            FsEntry(
                path = child.absolutePath,
                directory = child.isDirectory,
                bytes = if (child.isFile) child.length() else 0L,
                modifiedAt = child.lastModified(),
            )
        }
    }

    override fun walkFiles(directory: String): List<FsEntry> {
        val dir = File(directory)
        if (!dir.isDirectory) return emptyList()
        val found = ArrayList<FsEntry>()
        dir.walkTopDown().forEach { child ->
            if (child.isFile) {
                found += FsEntry(child.absolutePath, directory = false, bytes = child.length(), modifiedAt = child.lastModified())
            }
        }
        return found
    }

    override fun makeDirectories(directory: String) {
        if (directory.isEmpty()) return
        File(directory).mkdirs()
    }

    override fun copyFile(source: String, target: String): Long {
        val file = File(source)
        if (!file.isFile) return 0L
        val targetFile = File(target)
        targetFile.parentFile?.mkdirs()
        file.inputStream().use { input ->
            targetFile.outputStream().use { output -> return input.copyTo(output) }
        }
    }

    override fun copyTail(source: String, target: String, maxBytes: Long): Long {
        val file = File(source)
        if (!file.isFile) return 0L
        val targetFile = File(target)
        targetFile.parentFile?.mkdirs()
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val from = (length - maxBytes).coerceAtLeast(0L)
            input.seek(from)
            var written = 0L
            val buffer = ByteArray(1 shl 16)
            targetFile.outputStream().use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    written += read
                }
            }
            return written
        }
    }

    override fun writeText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    override fun appendText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.appendText(text, Charsets.UTF_8)
    }

    override fun readText(path: String, maxBytes: Long): String {
        val file = File(path)
        if (!file.isFile) return ""
        val limit = minOf(file.length(), maxBytes.coerceAtLeast(0L)).toInt()
        if (limit <= 0) return ""
        val buffer = ByteArray(limit)
        var read = 0
        file.inputStream().use { input ->
            while (read < limit) {
                val count = input.read(buffer, read, limit - read)
                if (count <= 0) break
                read += count
            }
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    override fun rename(from: String, to: String): Boolean {
        val source = File(from)
        if (!source.exists()) return false
        return source.renameTo(File(to))
    }

    override fun delete(path: String): Boolean = File(path).delete()

    override fun deleteRecursively(path: String): Boolean {
        if (path.isEmpty()) return false
        return File(path).deleteRecursively()
    }

    /**
     * 只保留尾部：**先写同目录临时文件再替换**，避免截断中途被杀留下半个文件。
     * `gpu-host.log` 是客体一直追加写的，不能就地 `setLength` 之后又继续追加成空洞。
     */
    override fun truncateToTail(path: String, keepBytes: Long): Long {
        val file = File(path)
        if (!file.isFile) return 0L
        val length = file.length()
        if (length <= keepBytes) return length
        val temp = File(file.parentFile, file.name + ".tail-tmp")
        val written = copyTail(path, temp.absolutePath, keepBytes)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) {
                temp.delete()
                return length
            }
        }
        return written
    }
}
