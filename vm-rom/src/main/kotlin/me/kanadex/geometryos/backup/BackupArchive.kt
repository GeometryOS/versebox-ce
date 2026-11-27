// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import me.kanadex.geometryos.rom.toHex

/*
 * The archive owns ZIP I/O, logical paths and integrity checks. Callers freeze the source
 * and own private staging paths. Only the caller can publish a staged VM or backup.
 *
 * The instance is described by an inventory rather than by ZIP entry types. Links whose
 * target stays inside the instance root are stored root-relative; links that leave it keep
 * their original text, because a guest resolves an absolute path inside its own namespace.
 * Runtime endpoints (sockets, FIFOs, device nodes) live only while a guest process runs, so
 * they are listed as omissions instead of failing the capture.
 */
object BackupArchive {
    enum class Phase { Write, Verify, Restore, ReadRom }
    data class Progress(val phase: Phase, val files: Int, val totalFiles: Int, val bytes: Long)

    // 条目被省略的原因与条数，供记录与审计展示；省略不等于失败
    data class Omissions(val runtimeEntries: Int, val derivedLinks: Int, val literalLinks: Int)

    private enum class Kind { Directory, File, Symlink, Link, Hardlink, Runtime }
    private enum class RuntimeKind { Socket, Fifo, Device, Other }

    private data class Node(
        val path: String,
        val kind: Kind,
        val mode: Int,
        val modified: Long,
        val blob: BackupBlob? = null,
        val link: String? = null,
        val runtime: RuntimeKind? = null,
    ) {
        val entry: String get() = "instance/tree/$path"
    }

    private data class Source(val node: Node, val file: Path)
    private data class Scanned(val sources: List<Source>, val omitted: List<Node>, val omissions: Omissions)
    private data class Index(
        val manifest: BackupManifest,
        val nodes: List<Node>,
        val zipEntries: Map<String, ZipEntry>,
        val omissions: Omissions,
    )

    private const val MAX_NODES = 250_000
    private const val MAX_INVENTORY = 32 * 1024 * 1024
    private const val MAX_PATH_BYTES = 4096
    private const val UNIX_TYPE_MASK = 0xF000
    private const val UNIX_SOCKET = 0xC000
    private const val UNIX_FIFO = 0x1000
    private const val UNIX_CHARACTER = 0x2000
    private const val UNIX_BLOCK = 0x6000
    private val permissions = PosixFilePermission.values().toList()

    fun write(
        output: File,
        source: File,
        settings: BackupVmSettings,
        client: BackupClient,
        originalRom: File?,
        progress: (Progress) -> Unit = {},
        checkActive: () -> Unit = {},
        isDerivedLink: (String, String) -> Boolean = { _, _ -> false },
        onOmitted: (Omissions) -> Unit = {},
    ): BackupManifest {
        val root = source.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Missing instance directory" }
        require(!output.toPath().toAbsolutePath().normalize().startsWith(root))
        val scanned = scan(root, checkActive, isDerivedLink)
        onOmitted(scanned.omissions)
        val total = scanned.sources.size + scanned.omitted.size
        var owned = false
        try {
            val manifest = Files.newOutputStream(output.toPath(), CREATE_NEW, WRITE).let { raw ->
                owned = true
                ZipOutputStream(raw.buffered()).use { zip ->
                    var bytes = 0L
                    val nodes = scanned.sources.mapIndexed { index, (node, path) ->
                        checkActive()
                        val updated = if (node.kind == Kind.File) {
                            val blob = zip.put(node.entry) {
                                Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                                    transfer(input, zip, Files.size(path), checkActive) { count ->
                                        progress(Progress(Phase.Write, index, total, bytes + count))
                                    }
                                }
                            }
                            bytes = Math.addExact(bytes, blob.bytes)
                            node.copy(blob = blob)
                        } else node
                        progress(Progress(Phase.Write, index + 1, total, bytes))
                        updated
                    } + scanned.omitted
                    val rom = originalRom?.let { romFile ->
                        zip.put(BackupManifestYaml.ROM) {
                            romFile.inputStream().use { input ->
                                transfer(input, zip, romFile.length(), checkActive) {
                                    progress(Progress(Phase.Write, nodes.size, nodes.size, bytes + it))
                                }
                            }
                        }
                    }
                    val inventory = encodeInventory(nodes)
                    zip.put(BackupManifestYaml.INVENTORY) { zip.write(inventory) }
                    BackupManifest(UUID.randomUUID(), System.currentTimeMillis(), settings, client, blobOf(inventory), rom)
                        .also { metadata ->
                            zip.put(BackupManifestYaml.HEADER) { zip.write(BackupManifestYaml.encode(metadata)) }
                        }
                }
            }
            check(verify(output, progress, checkActive) == manifest)
            return manifest
        } catch (failure: Throwable) {
            if (owned) cleanup(failure) { Files.deleteIfExists(output.toPath()) }
            throw failure
        }
    }

    fun readManifest(archive: File): BackupManifest = ZipFile(archive).use { zip -> index(zip).manifest }

    fun verify(
        archive: File,
        progress: (Progress) -> Unit = {},
        checkActive: () -> Unit = {},
        onOmitted: (Omissions) -> Unit = {},
    ): BackupManifest = ZipFile(archive).use { zip ->
        val index = index(zip)
        onOmitted(index.omissions)
        copyTree(zip, index, null, progress, checkActive)
        index.manifest.romArchive?.let { blob ->
            copyChecked(zip, index, BackupManifestYaml.ROM, blob, null, checkActive) {
                progress(Progress(Phase.ReadRom, 0, 1, it))
            }
            progress(Progress(Phase.ReadRom, 1, 1, blob.bytes))
        }
        index.manifest
    }

    fun extractRom(
        archive: File,
        destination: File,
        progress: (Progress) -> Unit = {},
        checkActive: () -> Unit = {},
    ): BackupManifest = ZipFile(archive).use { zip ->
        val index = index(zip)
        val blob = requireNotNull(index.manifest.romArchive) { "Backup has no ROM package" }
        val path = destination.toPath()
        val output = Files.newOutputStream(path, CREATE_NEW, WRITE)
        try {
            output.use {
                copyChecked(zip, index, BackupManifestYaml.ROM, blob, it, checkActive) { count ->
                    progress(Progress(Phase.ReadRom, 0, 1, count))
                }
            }
            progress(Progress(Phase.ReadRom, 1, 1, blob.bytes))
            index.manifest
        } catch (failure: Throwable) {
            cleanup(failure) { Files.deleteIfExists(path) }
            throw failure
        }
    }

    fun restoreTree(
        archive: File,
        destination: File,
        progress: (Progress) -> Unit = {},
        checkActive: () -> Unit = {},
    ): BackupManifest = ZipFile(archive).use { zip ->
        val index = index(zip)
        val root = Files.createDirectory(destination.toPath().toAbsolutePath().normalize())
        try {
            copyTree(zip, index, root, progress, checkActive)
            index.manifest
        } catch (failure: Throwable) {
            cleanup(failure) { deleteTree(root) }
            throw failure
        }
    }

    private fun scan(root: Path, checkActive: () -> Unit, isDerivedLink: (String, String) -> Boolean): Scanned {
        val hardlinks = mutableMapOf<Any, String>()
        val sources = mutableListOf<Source>()
        val omitted = mutableListOf<Node>()
        var derivedLinks = 0
        var literalLinks = 0
        Files.walk(root).use { paths ->
            for (path in paths.iterator()) {
                checkActive()
                if (path == root) continue
                require(sources.size + omitted.size < MAX_NODES) { "Too many instance entries" }
                val logical = logicalPath(root, path)
                validatePath(logical)
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val modified = attrs.lastModifiedTime().toMillis()
                if (attrs.isDirectory) {
                    sources += Source(Node(logical, Kind.Directory, mode(path), modified), path)
                } else if (attrs.isSymbolicLink) {
                    val rawTarget = Files.readSymbolicLink(path).toString()
                    val target = path.parent.resolve(rawTarget).normalize()
                    if (isDerivedLink(logical, rawTarget)) {
                        // 启动时按 ROM 清单重建，备份只需要报数
                        derivedLinks++
                    } else if (target.startsWith(root) && target != root) {
                        val link = logicalPath(root, target)
                        validatePath(link)
                        sources += Source(Node(logical, Kind.Symlink, 0, modified, link = link), path)
                    } else {
                        // 客体按自己的命名空间解析绝对路径，原样保存后语义不变
                        validateLink(rawTarget)
                        literalLinks++
                        sources += Source(Node(logical, Kind.Link, 0, modified, link = rawTarget), path)
                    }
                } else if (attrs.isRegularFile) {
                    val previous = attrs.fileKey()?.let { hardlinks.putIfAbsent(it, logical) }
                    val node = if (previous == null) Node(logical, Kind.File, mode(path), modified)
                    else Node(logical, Kind.Hardlink, 0, modified, link = previous)
                    sources += Source(node, path)
                } else {
                    omitted += Node(logical, Kind.Runtime, 0, modified, runtime = runtimeKind(path))
                }
            }
        }
        return Scanned(sources, omitted, Omissions(omitted.size, derivedLinks, literalLinks))
    }

    private fun copyTree(
        zip: ZipFile, index: Index, root: Path?, progress: (Progress) -> Unit, checkActive: () -> Unit,
    ) {
        val phase = if (root == null) Phase.Verify else Phase.Restore
        val total = index.nodes.size
        var bytes = 0L
        for ((offset, node) in index.nodes.withIndex()) {
            checkActive()
            val target = root?.resolve(node.path)
            when (node.kind) {
                Kind.Directory -> if (target != null) Files.createDirectory(target) else Unit
                Kind.File -> {
                    val blob = checkNotNull(node.blob)
                    openOutput(target).use { output ->
                        copyChecked(zip, index, node.entry, blob, output, checkActive) {
                            progress(Progress(phase, offset, total, bytes + it))
                        }
                    }
                    if (target != null) applyMetadata(target, node)
                    bytes = Math.addExact(bytes, blob.bytes)
                }
                Kind.Symlink, Kind.Link, Kind.Hardlink, Kind.Runtime -> Unit
            }
            progress(Progress(phase, offset + 1, total, bytes))
        }
        if (root != null) {
            // Links are installed after all ordinary writes and can never be parents in the inventory.
            index.nodes.forEach { node ->
                checkActive()
                when (node.kind) {
                    Kind.Symlink -> {
                        val path = root.resolve(node.path)
                        Files.createSymbolicLink(path, path.parent.relativize(root.resolve(checkNotNull(node.link))))
                    }
                    Kind.Link -> Files.createSymbolicLink(root.resolve(node.path), Paths.get(checkNotNull(node.link)))
                    Kind.Hardlink -> Files.createLink(root.resolve(node.path), root.resolve(checkNotNull(node.link)))
                    else -> Unit
                }
            }
            index.nodes.asReversed().filter { it.kind == Kind.Directory }.forEach {
                applyMetadata(root.resolve(it.path), it)
            }
        }
    }

    private fun openOutput(path: Path?): OutputStream? =
        path?.let { Files.newOutputStream(it, CREATE_NEW, WRITE).buffered() }

    private fun index(zip: ZipFile): Index {
        require(zip.size() in 2..MAX_NODES + 3) { "Invalid ZIP entry count" }
        val entries = linkedMapOf<String, ZipEntry>()
        for (entry in zip.entries()) {
            require(!entry.isDirectory && entries.put(entry.name, entry) == null) { "Invalid ZIP entry: "+ entry.name }
        }
        fun read(name: String, limit: Int): ByteArray {
            val entry = requireNotNull(entries[name]) { "Missing ZIP entry: $name" }
            require(entry.size in 0..limit.toLong()) { "Oversized ZIP metadata: $name" }
            return ByteArrayOutputStream().also { output ->
                zip.getInputStream(entry).use { transfer(it, output, entry.size, {}, {}) }
            }.toByteArray()
        }
        val manifest = BackupManifestYaml.decode(read(BackupManifestYaml.HEADER, BackupManifestYaml.MAX_BYTES))
        val inventory = read(BackupManifestYaml.INVENTORY, MAX_INVENTORY)
        require(blobOf(inventory) == manifest.inventory) { "Instance inventory checksum mismatch" }
        val nodes = decodeInventory(inventory)
        val expected = mutableSetOf(BackupManifestYaml.HEADER, BackupManifestYaml.INVENTORY)
        fun expect(name: String, bytes: Long) {
            require(expected.add(name) && entries[name]?.size == bytes) { "ZIP entry size differs: $name" }
        }
        nodes.forEach { node -> node.blob?.let { expect(node.entry, it.bytes) } }
        manifest.romArchive?.let { expect(BackupManifestYaml.ROM, it.bytes) }
        require(entries.keys == expected) { "Unexpected ZIP entries" }
        return Index(manifest, nodes, entries,
            Omissions(nodes.count { it.kind == Kind.Runtime }, 0, nodes.count { it.kind == Kind.Link }))
    }

    private fun encodeInventory(nodes: List<Node>): ByteArray = buildString {
        for (node in nodes) {
            append(node.kind.name).append('\t').append(node.mode).append('\t').append(node.modified).append('\t')
            append(encodePath(node.path)).append('\t')
            append(node.blob?.bytes ?: 0).append('\t').append(node.blob?.sha256.orEmpty()).append('\t')
            append(node.runtime?.name ?: node.link?.let(::encodePath).orEmpty()).append('\n')
        }
    }.toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_INVENTORY) }

    private fun decodeInventory(bytes: ByteArray): List<Node> {
        val seen = linkedMapOf<String, Node>()
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        require(text.isEmpty() || text.endsWith('\n'))
        for (line in text.removeSuffix("\n").lineSequence().filter { it.isNotEmpty() }) {
            require(seen.size < MAX_NODES)
            val fields = line.split('\t', limit = 8)
            require(fields.size == 7)
            val kind = Kind.valueOf(fields[0])
            val mode = fields[1].toInt().also { require(it in 0..511) }
            val path = decodePath(fields[3])
            val size = fields[4].toLong()
            val blob = if (kind == Kind.File) BackupBlob(size, fields[5]) else null
            require(blob != null || (size == 0L && fields[5].isEmpty()))
            val extra = fields[6]
            val node = when (kind) {
                Kind.File -> Node(path, kind, mode, fields[2].toLong(), blob)
                Kind.Directory -> {
                    require(extra.isEmpty())
                    Node(path, kind, mode, fields[2].toLong())
                }
                Kind.Symlink -> Node(path, kind, 0, fields[2].toLong(), link = decodePath(extra))
                Kind.Link -> Node(path, kind, 0, fields[2].toLong(), link = decodeLink(extra))
                Kind.Hardlink -> Node(path, kind, 0, fields[2].toLong(), link = decodePath(extra))
                Kind.Runtime -> Node(path, kind, 0, fields[2].toLong(), runtime = RuntimeKind.valueOf(extra))
            }
            if (kind != Kind.Runtime) {
                val parent = path.substringBeforeLast('/', "")
                require(parent.isEmpty() || seen[parent]?.kind == Kind.Directory) { "Missing parent directory: $path" }
            }
            if (kind == Kind.Hardlink) require(node.link?.let { seen[it]?.kind } == Kind.File)
            require(seen.put(path, node) == null)
        }
        return seen.values.toList()
    }

    private fun copyChecked(
        zip: ZipFile, index: Index, name: String, expected: BackupBlob, output: OutputStream?,
        checkActive: () -> Unit, progress: (Long) -> Unit,
    ) {
        val actual = zip.getInputStream(index.zipEntries.getValue(name)).use {
            transfer(it, output, expected.bytes, checkActive, progress)
        }
        require(actual == expected) { "Backup checksum mismatch: $name" }
    }

    private fun transfer(
        input: InputStream, output: OutputStream?, size: Long, checkActive: () -> Unit, progress: (Long) -> Unit,
    ): BackupBlob {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        var total = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            total = Math.addExact(total, count.toLong())
            require(total <= size) { "Backup entry exceeds its declared size" }
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
            progress(total)
        }
        require(total == size) { "Backup entry is truncated" }
        return BackupBlob(total, digest.digest().toHex())
    }

    private fun logicalPath(root: Path, path: Path): String = root.relativize(path).joinToString("/")

    private fun validatePath(path: String) {
        require(path.toByteArray(Charsets.UTF_8).size in 1..MAX_PATH_BYTES)
        require(path.none { it == '\\' || it == ':' || it.isISOControl() }) { "Invalid instance path" }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid instance path" }
    }

    private fun encodePath(path: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(path.toByteArray(Charsets.UTF_8))

    private fun decodePath(value: String): String {
        require(value.length <= MAX_PATH_BYTES * 2)
        val path = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))).toString()
        validatePath(path)
        return path
    }

    // 客体自定义的链接目标可能带空格或任意路径字符，只约束长度与控制字符
    private fun validateLink(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size in 1..MAX_PATH_BYTES) { "Invalid link target" }
        require(text.none { it.isISOControl() }) { "Invalid link target" }
    }

    private fun decodeLink(value: String): String {
        require(value.length <= MAX_PATH_BYTES * 2)
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))).toString()
        validateLink(text)
        return text
    }

    // 取不到 unix:mode 的平台按其他处理，绝不因为无法分类而中止
    private fun runtimeKind(path: Path): RuntimeKind = try {
        when ((Files.getAttribute(path, "unix:mode") as Int) and UNIX_TYPE_MASK) {
            UNIX_SOCKET -> RuntimeKind.Socket
            UNIX_FIFO -> RuntimeKind.Fifo
            UNIX_CHARACTER, UNIX_BLOCK -> RuntimeKind.Device
            else -> RuntimeKind.Other
        }
    } catch (_: Exception) {
        RuntimeKind.Other
    }

    private fun mode(path: Path): Int = try {
        val actual = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        permissions.foldIndexed(0) { i, bits, permission -> bits or (if (permission in actual) 1 shl (8 - i) else 0) }
    } catch (_: UnsupportedOperationException) {
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) 448 else 384
    }

    private fun applyMetadata(path: Path, node: Node) {
        Files.setLastModifiedTime(path, FileTime.fromMillis(node.modified))
        try {
            Files.setPosixFilePermissions(path, permissions.filterIndexed { i, _ -> node.mode and (1 shl (8 - i)) != 0 }.toSet())
        } catch (_: UnsupportedOperationException) {
            // Platforms without POSIX permissions use their default access model.
        }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    private inline fun cleanup(failure: Throwable, action: () -> Unit) {
        try { action() } catch (cleanupFailure: Exception) { failure.addSuppressed(cleanupFailure) }
    }

    private fun blobOf(bytes: ByteArray) = BackupBlob(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).toHex())

    private inline fun <T> ZipOutputStream.put(name: String, write: () -> T): T {
        putNextEntry(ZipEntry(name))
        val result = write()
        closeEntry()
        return result
    }
}
