package me.kanadex.geometryos.audit.json

import me.kanadex.geometryos.audit.model.AuditEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 审计事件的 NDJSON 编码 —— 格式只在这一处定义（docs/log-audit/02 §4）。
 *
 * 值只接受 String / Long / Int / Boolean；其它类型按 `toString()` 落字符串，
 * 避免出现"一次写入把整条日志写坏"的嵌套结构。
 */
object AuditEventJson {
    fun encode(event: AuditEvent): String {
        val json = buildJsonObject {
            put("at", JsonPrimitive(event.at))
            put("kind", JsonPrimitive(event.kind))
            for ((key, value) in event.fields) {
                put(key, primitive(value))
            }
        }
        return json.toString()
    }

    private fun primitive(value: Any): JsonPrimitive = when (value) {
        is Boolean -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toLong())
        else -> JsonPrimitive(value.toString())
    }

    /** 给需要读回事件的场景（测试、审计页）用；单行解析失败返回 null。 */
    fun decode(line: String): AuditEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val element = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(trimmed) }.getOrNull()
        val obj = element as? JsonObject ?: return null
        val at = (obj["at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        val kind = (obj["kind"] as? JsonPrimitive)?.content ?: return null
        val fields = LinkedHashMap<String, Any>()
        for ((key, value) in obj) {
            if (key == "at" || key == "kind") continue
            val primitive = value as? JsonPrimitive ?: continue
            fields[key] = when {
                primitive.isString -> primitive.content
                primitive.content == "true" || primitive.content == "false" -> primitive.content.toBoolean()
                else -> primitive.content.toLongOrNull() ?: primitive.content
            }
        }
        return AuditEvent(at, kind, fields)
    }
}
