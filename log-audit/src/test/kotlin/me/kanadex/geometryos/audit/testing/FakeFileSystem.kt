package me.kanadex.geometryos.audit.testing

import me.kanadex.geometryos.audit.port.AuditClock
import me.kanadex.geometryos.audit.port.AuditEventSink
import me.kanadex.geometryos.audit.port.AuditFileSystem
import me.kanadex.geometryos.audit.port.FsEntry
import me.kanadex.geometryos.audit.model.AuditEvent

/** 内存文件系统：策略/执行器的单测不需要设备，也不需要真的删东西。 */
class FakeFileSystem : AuditFileSystem {
    private class Node(var content: ByteArray, var modifiedAt: Long)

    private val files = LinkedHashMap<String, Node>()
    private val directories = LinkedHashSet<String>()

    var failRename = false
    var copyCalls = 0

    fun putFile(path: String, size: Long, modifiedAt: Long) {
        putBytes(path, ByteArray(size.toInt()) { it.toByte() }, modifiedAt)
    }

    fun putText(path: String, text: String, modifiedAt: Long = 0L) {
        putBytes(path, text.toByteArray(), modifiedAt)
    }

    fun putBytes(path: String, bytes: ByteArray, modifiedAt: Long = 0L) {
        makeDirectories(path.substringBeforeLast('/', ""))
        files[path] = Node(bytes, modifiedAt)
    }

    fun text(path: String): String? = files[path]?.content?.toString(Charsets.UTF_8)

    fun bytes(path: String): Int? = files[path]?.content?.size

    fun paths(): Set<String> = files.keys.toSet()

    fun directoryPaths(): Set<String> = directories.toSet()

    override fun exists(path: String): Boolean = files.containsKey(path) || isDirectory(path)

    override fun isDirectory(path: String): Boolean =
        directories.contains(path) || files.keys.any { it.startsWith("$path/") }

    override fun size(path: String): Long = files[path]?.content?.size?.toLong() ?: 0L

    override fun modifiedAt(path: String): Long = files[path]?.modifiedAt ?: 0L

    override fun list(directory: String): List<FsEntry> {
        val prefix = if (directory.isEmpty()) "" else "$directory/"
        val seen = LinkedHashMap<String, FsEntry>()
        for (path in directories) {
            if (path == directory || !path.startsWith(prefix)) continue
            val name = path.removePrefix(prefix)
            if (name.contains('/')) continue
            seen[path] = FsEntry(path, directory = true, bytes = 0, modifiedAt = 0)
        }
        for ((path, node) in files) {
            if (!path.startsWith(prefix)) continue
            val name = path.removePrefix(prefix)
            if (name.contains('/')) continue
            seen[path] = FsEntry(path, directory = false, node.content.size.toLong(), node.modifiedAt)
        }
        return seen.values.toList()
    }

    override fun walkFiles(directory: String): List<FsEntry> {
        val prefix = if (directory.isEmpty()) "" else "$directory/"
        return files.entries
            .filter { it.key.startsWith(prefix) }
            .map { FsEntry(it.key, false, it.value.content.size.toLong(), it.value.modifiedAt) }
    }

    override fun makeDirectories(directory: String) {
        if (directory.isEmpty()) return
        var current = directory
        while (current.isNotEmpty()) {
            directories += current
            current = current.substringBeforeLast('/', "")
        }
    }

    override fun copyFile(source: String, target: String): Long {
        copyCalls++
        val node = files[source] ?: error("missing source: $source")
        putBytes(target, node.content.copyOf(), node.modifiedAt)
        return node.content.size.toLong()
    }

    override fun copyTail(source: String, target: String, maxBytes: Long): Long {
        copyCalls++
        val node = files[source] ?: error("missing source: $source")
        val from = (node.content.size - maxBytes).coerceAtLeast(0L).toInt()
        val tail = node.content.copyOfRange(from, node.content.size)
        putBytes(target, tail, node.modifiedAt)
        return tail.size.toLong()
    }

    override fun writeText(path: String, text: String) = putText(path, text)

    override fun appendText(path: String, text: String) {
        val existing = files[path]?.content ?: ByteArray(0)
        putBytes(path, existing + text.toByteArray())
    }

    override fun readText(path: String, maxBytes: Long): String {
        val node = files[path] ?: return ""
        val length = minOf(node.content.size.toLong(), maxBytes).toInt()
        return String(node.content, 0, length, Charsets.UTF_8)
    }

    override fun rename(from: String, to: String): Boolean {
        if (failRename) return false
        val movingFiles = files.filterKeys { it == from || it.startsWith("$from/") }
        val movingDirs = directories.filter { it == from || it.startsWith("$from/") }
        if (movingFiles.isEmpty() && movingDirs.isEmpty()) return false
        files.keys.removeAll { it == from || it.startsWith("$from/") }
        directories.removeAll { it == from || it.startsWith("$from/") }
        makeDirectories(to.substringBeforeLast('/', ""))
        for ((path, node) in movingFiles) files[to + path.removePrefix(from)] = node
        for (dir in movingDirs) directories += to + dir.removePrefix(from)
        return true
    }

    override fun delete(path: String): Boolean {
        val existed = files.remove(path) != null
        return existed || directories.remove(path)
    }

    override fun deleteRecursively(path: String): Boolean {
        files.keys.removeAll { it == path || it.startsWith("$path/") }
        directories.removeAll { it == path || it.startsWith("$path/") }
        return true
    }

    override fun truncateToTail(path: String, keepBytes: Long): Long {
        val node = files[path] ?: return 0L
        val from = (node.content.size - keepBytes).coerceAtLeast(0L).toInt()
        node.content = node.content.copyOfRange(from, node.content.size)
        return node.content.size.toLong()
    }
}

/** 可控时钟。 */
class TestClock(var current: Long = 1_000L) : AuditClock {
    override fun now(): Long = current
}

/** 记录全部审计事件，供断言。 */
class RecordingSink : AuditEventSink {
    val events = ArrayList<AuditEvent>()

    override fun emit(event: AuditEvent) {
        events += event
    }

    fun kinds(): List<String> = events.map { it.kind }
}
