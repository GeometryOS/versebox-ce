// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

// 清单里要逐条校验的一条
data class RomEntry(val path: String, val bytes: Long, val sha256: String)

/**
 * 解包器，内置包和本地包共用。做三件事：解到临时目录、逐条校验、原子改名。
 * 失败一律删掉半成品再抛 RomException。
 *
 * 剩余空间和可执行位由外面注入，所以整条流程能在 JVM 单测里跑，不用靠真机试。
 */
class RomExtractor(
    private val layout: RomLayout,
    private val freeBytes: () -> Long,
    private val chmodExecutable: (File) -> Unit,
) {

    // 空间检查多留 5%
    private fun requireSpace(needed: Long) {
        val available = freeBytes()
        val required = (needed * 1.05).toLong()
        if (available in 0 until required) {
            throw RomException(
                "空间不足：需要 " + RomTexts.bytes(required) + "，剩余 " + RomTexts.bytes(available),
            )
        }
    }

    // 把 source 解成运行形态，返回解包后的目录。entries 是清单里要校验的条目
    fun extract(
        source: File,
        properties: RomProperties,
        entries: List<RomEntry>,
        progress: (RomProgress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): File {
        val key = properties.key
        if (!layout.isValidKey(key)) throw RomException("ROM 标识不合法：" + key)
        if (properties.placeholder) {
            throw RomException("这是一个占位包（只有自述与清单），需要导入完整的 " + key + " 才能使用")
        }
        requireSpace(properties.payloadBytes)
        layout.root.mkdirs()
        /**
         * 每次解包用独立的临时目录。共用 ".tmp-"+key 时，两个调用方同时进来，
         * 后进的会把先进的正在写的树删掉，先进的报 ENOENT 或哈希不符。
         * 目录名带内容指纹，同一个 romKey 的两份内容各解各的，不会互相踩。
         */
        val dirName = layout.dirName(key, properties.payloadSha256)
        // 校验的是带指纹的目录名，只看 key 会放过超长的名字
        if (!layout.isValidDirName(dirName)) throw RomException("ROM 标识不合法：" + dirName)
        val tmp = layout.tmpDir(dirName, System.nanoTime())
        if (tmp.exists()) tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw RomException("无法创建解包目录：" + tmp.name)

        val expected = LinkedHashMap<String, RomEntry>()
        for (entry in entries) expected[entry.path] = entry

        try {
            val hashes = HashMap<String, String>()
            ZipFile(source).use { zip ->
                val entriesInZip = zip.entries().toList()
                    .filter { !it.isDirectory && it.name.startsWith(RomLayout.PAYLOAD_DIR) }
                var done = 0
                for (entry in entriesInZip) {
                    if (isCancelled()) throw RomCancelled()
                    val target = File(tmp, entry.name)
                    val parent = target.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                        throw RomException("无法创建目录：" + entry.name)
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(1 shl 20)
                            while (true) {
                                val count = input.read(buffer)
                                if (count <= 0) break
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    hashes[entry.name] = digest.digest().toHex()
                    done++
                    if (done % 64 == 0 || done == entriesInZip.size) {
                        progress(RomProgress("正在解包", done, entriesInZip.size))
                    }
                }
                // 自述和清单也落进目录，运行时读的是目录不是 zip
                copyEntry(zip, RomLayout.PROPERTIES, File(tmp, RomLayout.PROPERTIES))
                copyEntry(zip, properties.inputs, File(tmp, properties.inputs))
            }

            // 清单里的每一条都要出现在写出的文件里，哈希也要一致
            for ((name, entry) in expected) {
                val actual = hashes[name] ?: throw RomException(
                    "ROM 缺文件：" + name + "（该 ROM 可能解包不完整，请重新安装）",
                )
                if (actual != entry.sha256) {
                    throw RomException("ROM 校验失败：" + name + " 内容不一致，包可能已损坏")
                }
            }
            progress(RomProgress("正在校验", 1, 1))

            val computed = RomHash.payloadSha256(File(tmp, RomLayout.PAYLOAD), isCancelled)
            if (computed.first != properties.payloadSha256) {
                throw RomException(
                    "ROM 校验失败：payload 哈希不符（期望 " + properties.payloadSha256.take(8) +
                        "…，实际 " + computed.first.take(8) + "…），包可能已损坏",
                )
            }

            // payload/lib 下的文件必须可执行，linker 要 exec 它们
            val lib = File(tmp, RomLayout.LIB_DIR)
            if (lib.isDirectory) {
                lib.walkTopDown().forEach { file ->
                    if (file.isFile) runCatching { chmodExecutable(file) }
                }
            }

            /**
             * 落盘到内容寻址目录。两份不同内容落在两个目录，否则切换版本只是把同一份重解一遍，
             * 用户以为切了，其实没切。
             */
            val target = layout.dirFor(key, properties.payloadSha256)
            if (target.exists()) {
                val trash = layout.trashDir(dirName, System.currentTimeMillis())
                if (!target.renameTo(trash)) throw RomException("无法替换旧解包目录：" + dirName)
                trash.deleteRecursively()
            }
            if (!tmp.renameTo(target)) throw RomException("无法落盘解包目录：" + dirName)
            // 旧布局里如果是同一份内容，指纹目录写好之后把它删掉，同一份内容不留两份。
            retireLegacyDir(key, properties.payloadSha256)
            return target
        } catch (failure: Throwable) {
            tmp.deleteRecursively()
            throw failure
        }
    }

    /**
     * 旧布局目录里如果是同一份内容，指纹目录写好之后删掉它。
     * 判据是目录自己的自述指纹，不是名字像；内容不同必须留着，删掉等于把别人的 ROM 删了。
     */
    private fun retireLegacyDir(key: String, payloadSha256: String) {
        val legacy = layout.dir(key)
        if (legacy == layout.dirFor(key, payloadSha256)) return
        if (!layout.isExtracted(legacy)) return
        val own = runCatching {
            RomPropertiesYaml.validate(
                RomPropertiesYaml.parse(layout.properties(legacy).readText(Charsets.UTF_8)),
            ).payloadSha256
        }.getOrNull().orEmpty()
        if (own.isEmpty() || own != payloadSha256) return
        val trash = layout.trashDir(key, System.currentTimeMillis())
        if (legacy.renameTo(trash)) trash.deleteRecursively()
    }

    private fun copyEntry(zip: ZipFile, name: String, target: File) {
        val entry = zip.getEntry(name) ?: return
        target.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
    }
}
