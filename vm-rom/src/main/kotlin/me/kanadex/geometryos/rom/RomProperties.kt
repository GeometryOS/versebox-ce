// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

/**
 * 一个 ROM 包的自述，就是 versebox_properties.yaml 里那些键。
 * 字段全是标量，解析规则和 io.a10vm.runtime.RomCatalog 的 yaml() 一样。
 * 不引完整 YAML 依赖，多一份实现就多一份会漂的真相。
 */
data class RomProperties(
    val schema: String,
    val id: String,
    val romVersion: String,
    val name: String,
    val androidVersion: String,
    val apiLevel: Int,
    val description: String,
    val inputs: String,
    val payloadRoot: String,
    val payloadBytes: Long,
    val payloadSha256: String,
    val libMode: String,
    val packedBy: String,
    val packedAt: String,
    /**
     * 占位包只有自述和清单，没有 payload。它让列表里能看到这个安卓版本，
     * 真要开机还得让用户导入完整包，占位包不许解包成运行形态
     */
    val placeholder: Boolean = false,
) {
    // 运行期主键，由 id 和 romVersion 拼成
    val key: String get() = RomKeys.key(id, romVersion)
}

/**
 * 自述的解析和第一层校验，只回答这份自述是不是一个合法的 VerseBox ROM 包。
 * payload 的内容校验在解包时逐条做，见 RomExtractor。
 */
object RomPropertiesYaml {

    const val SCHEMA = "versebox-rom/2"

    // 平铺的 key: value 解析，规则和 RomCatalog 一致
    fun parse(text: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
                .removeSurrounding("\"").removeSurrounding("'")
            if (key.isNotEmpty()) values[key] = value
        }
        return values
    }

    // 校验并规范化，失败时抛 RomException，消息就是给人看的原因
    fun validate(values: Map<String, String>): RomProperties {
        val schema = values["schema"].orEmpty()
        if (schema != SCHEMA) throw RomException("ROM 自述的 schema 不受支持：" + schema.ifBlank { "（空）" })
        val id = values["id"].orEmpty()
        if (!RomKeys.isValidId(id)) throw RomException("ROM 自述的 id 不合法：" + id.ifBlank { "（空）" })
        val version = values["romVersion"].orEmpty()
        if (!RomKeys.isValidVersion(version)) throw RomException("ROM 自述的 romVersion 不合法：" + version.ifBlank { "（空）" })
        val api = values["apiLevel"]?.toIntOrNull()
            ?: throw RomException("ROM 自述缺少 apiLevel 或不是数字")
        if (api !in 26..35) throw RomException("ROM 自述的 apiLevel 超出范围：" + api)
        val payloadRoot = (values["payloadRoot"].orEmpty()).let { if (it.endsWith("/")) it else it + "/" }
        if (payloadRoot != RomLayout.PAYLOAD_DIR) {
            throw RomException("payloadRoot 必须是 payload/（三个安卓版本共用同一结构）")
        }
        val payloadBytes = values["payloadBytes"]?.toLongOrNull()
            ?: throw RomException("ROM 自述缺少 payloadBytes 或不是数字")
        if (payloadBytes < 0L) throw RomException("ROM 自述的 payloadBytes 不能是负数")
        val sha = values["payloadSha256"].orEmpty().lowercase()
        if (sha.length != 64 || sha.any { it !in "0123456789abcdef" }) {
            throw RomException("ROM 自述的 payloadSha256 不是 64 位十六进制")
        }
        val inputs = values["inputs"].orEmpty().ifBlank { RomLayout.INPUTS }
        if (inputs.contains('/') || inputs.contains('\\') || inputs.contains("..")) {
            throw RomException("ROM 自述的 inputs 必须是 zip 根下的普通文件名：" + inputs)
        }
        val placeholder = values["placeholder"].orEmpty().lowercase() in setOf("true", "1", "yes")
        if (!placeholder && payloadBytes <= 0L) {
            throw RomException("ROM 自述的 payloadBytes 必须大于 0（占位包请显式写 placeholder: true）")
        }
        return RomProperties(
            schema = schema,
            id = id,
            romVersion = version,
            name = values["name"].orEmpty().ifBlank { id },
            androidVersion = values["androidVersion"].orEmpty(),
            apiLevel = api,
            description = values["description"].orEmpty(),
            inputs = inputs,
            payloadRoot = payloadRoot,
            payloadBytes = payloadBytes,
            payloadSha256 = sha,
            libMode = values["libMode"].orEmpty().ifBlank { "apk" }.lowercase(),
            packedBy = values["packedBy"].orEmpty(),
            packedAt = values["packedAt"].orEmpty(),
            placeholder = placeholder,
        )
    }

    // 自述文本变成规范化自述
    fun read(text: String): RomProperties = validate(parse(text))
}
