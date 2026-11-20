// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import android.content.res.AssetManager
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * app 侧的 ROM 包读取，只负责从哪里读字节。
 *
 * 字段契约与校验在 :vm-rom 的 RomPropertiesYaml。三个入口分别是已落盘的 zip、
 * 已解包目录里的自述、APK assets 里的内置包。
 */
object RomPackage {

    const val SCHEMA = RomPropertiesYaml.SCHEMA

    fun parseYaml(text: String): Map<String, String> = RomPropertiesYaml.parse(text)

    fun validate(values: Map<String, String>): RomProperties = RomPropertiesYaml.validate(values)

    // 从已落盘的 zip 读自述并校验
    fun read(file: File): RomProperties {
        if (!file.isFile) throw RomException("ROM 包不存在：" + file.name)
        val text: String
        val names: List<String>
        try {
            ZipFile(file).use { zip ->
                names = zip.entries().toList().map { it.name }
                val properties = zip.getEntry(RomStorage.PROPERTIES)
                    ?: throw RomException("不是 VerseBox ROM 包：缺少 " + RomStorage.PROPERTIES)
                text = zip.getInputStream(properties).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (failure: RomException) {
            throw failure
        } catch (failure: Exception) {
            throw RomException("不是可读的 zip：" + file.name, failure)
        }
        val properties = validate(parseYaml(text))
        if (!names.contains(properties.inputs)) {
            throw RomException("不是 VerseBox ROM 包：缺少 " + properties.inputs)
        }
        if (names.none { it == properties.payloadRoot }) {
            throw RomException("不是 VerseBox ROM 包：缺少 " + properties.payloadRoot + " 目录")
        }
        names.firstOrNull { it.startsWith("/") || it.contains("..") || it.contains(':') }?.let {
            throw RomException("ROM 包含非法条目（绝对路径/上跳/盘符）：" + it)
        }
        return properties
    }

    // 读已解包目录里的自述，不校验 payload
    fun readPropertiesFile(file: File): RomProperties {
        if (!file.isFile) throw RomException("ROM 目录缺少自述：" + file.parentFile?.name)
        return RomPropertiesYaml.read(file.readText(Charsets.UTF_8))
    }

    /**
     * 直接从 APK assets 读自述，不复制整个 zip
     * 只能顺序解压到自述所在位置，构建期索引存在时走索引
     */
    fun readFromAsset(assets: AssetManager, assetName: String): RomProperties {
        try {
            assets.open(assetName).use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name == RomStorage.PROPERTIES) {
                            val bytes = zip.readLimited(64 * 1024)
                            return validate(parseYaml(bytes.toString(Charsets.UTF_8)))
                        }
                        zip.closeEntry()
                    }
                }
            }
        } catch (failure: RomException) {
            throw failure
        } catch (failure: Exception) {
            throw RomException("读取内置 ROM 自述失败：" + assetName, failure)
        }
        throw RomException("不是 VerseBox ROM 包：缺少 " + RomStorage.PROPERTIES)
    }

    /**
     * 取出要逐条校验的条目，也就是 data[] 和带 libPath 的 files[]，交给 RomExtractor
     * 清单里的 guestSdk 必须和自述的 apiLevel 一致
     */
    fun entries(zip: ZipFile, properties: RomProperties): List<RomEntry> {
        val inputs = zip.getEntry(properties.inputs)
            ?: throw RomException("ROM 不完整：缺少 " + properties.inputs)
        val text = zip.getInputStream(inputs).use { it.readBytes().toString(Charsets.UTF_8) }
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw RomException("清单不是合法 JSON：" + properties.inputs)
        }
        if (root.optInt("guestSdk", -1) != properties.apiLevel) {
            throw RomException(
                "ROM 与描述不一致：清单 guestSdk=" + root.optInt("guestSdk", -1) +
                    "，描述 apiLevel=" + properties.apiLevel,
            )
        }
        val result = mutableListOf<RomEntry>()
        val data = root.optJSONArray("data") ?: throw RomException("清单缺少 data[]")
        for (index in 0 until data.length()) {
            val item = data.getJSONObject(index)
            val name = item.optString("name")
            if (!name.startsWith(RomStorage.PAYLOAD_DIR)) {
                throw RomException("清单 data[" + index + "] 的路径不在 payload/ 下：" + name)
            }
            result += RomEntry(name, item.optLong("bytes"), item.optString("sha256"))
        }
        val files = root.optJSONArray("files")
        for (index in 0 until (files?.length() ?: 0)) {
            val item = files!!.getJSONObject(index)
            val libPath = item.optString("libPath")
            if (libPath.isNotEmpty() && libPath.startsWith(RomStorage.PAYLOAD_DIR)) {
                result += RomEntry(libPath, item.optLong("bytes"), item.optString("sha256"))
            }
        }
        return result
    }

    private fun ZipInputStream.readLimited(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(chunk)
            if (count <= 0) break
            total += count
            if (total > limit) throw RomException("ROM 自述过大（超过 " + limit + " 字节）")
            buffer.write(chunk, 0, count)
        }
        return buffer.toByteArray()
    }
}
