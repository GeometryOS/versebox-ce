package me.kanadex.geometryos.audit.json

import me.kanadex.geometryos.audit.model.ArchiveManifest
import me.kanadex.geometryos.audit.model.ManifestFile
import me.kanadex.geometryos.audit.model.ManifestMissing
import me.kanadex.geometryos.audit.model.SessionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add

/**
 * `manifest.json` 的编解码（结构见 docs/log-audit/03 §2）。
 *
 * 只有一处实现：写入时由 [ArchiveWriter] 调，读取时由记录详情页的数据层调，
 * 所以"完整性"在两边永远指同一件事。
 */
object ManifestJson {
    fun encode(manifest: ArchiveManifest): String {
        val json = buildJsonObject {
            put("schema", JsonPrimitive(manifest.schema))
            put("recordId", JsonPrimitive(manifest.recordId))
            put("vmId", JsonPrimitive(manifest.vmId))
            put("sessionId", JsonPrimitive(manifest.sessionId.value))
            put("kind", JsonPrimitive(manifest.kind))
            put("status", JsonPrimitive(manifest.status))
            put("createdAt", JsonPrimitive(manifest.createdAt))
            put("complete", JsonPrimitive(manifest.complete))
            put("files", buildJsonArray {
                for (file in manifest.files) {
                    add(buildJsonObject {
                        put("path", JsonPrimitive(file.path))
                        put("bytes", JsonPrimitive(file.bytes))
                        put("stored", JsonPrimitive(file.stored))
                        file.truncated?.let { put("truncated", JsonPrimitive(it)) }
                        file.sha256?.let { put("sha256", JsonPrimitive(it)) }
                    })
                }
            })
            put("missing", buildJsonArray {
                for (item in manifest.missing) {
                    add(buildJsonObject {
                        put("path", JsonPrimitive(item.path))
                        put("reason", JsonPrimitive(item.reason))
                    })
                }
            })
        }
        return json.toString()
    }

    /**
     * 读回清单。结构不认识（schema 不符 / 关键字段缺失）时返回 null ——
     * 调用方据此显示"完整性未知"，**不删、不改写**（04 §6）。
     */
    fun decode(text: String): ArchiveManifest? {
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        val root = element as? JsonObject ?: return null
        if (root.string("schema") != ArchiveManifest.SCHEMA) return null
        val recordId = root.long("recordId") ?: return null
        val createdAt = root.long("createdAt") ?: 0L
        val files = (root["files"] as? JsonArray)?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            ManifestFile(
                path = obj.string("path") ?: return@mapNotNull null,
                bytes = obj.long("bytes") ?: 0L,
                stored = obj.long("stored") ?: 0L,
                truncated = obj.string("truncated"),
                sha256 = obj.string("sha256"),
            )
        }.orEmpty()
        val missing = (root["missing"] as? JsonArray)?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            ManifestMissing(
                path = obj.string("path") ?: return@mapNotNull null,
                reason = obj.string("reason").orEmpty(),
            )
        }.orEmpty()
        val complete = (root["complete"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        return ArchiveManifest(
            recordId = recordId,
            vmId = root.string("vmId").orEmpty(),
            sessionId = SessionId.of(root.string("sessionId")),
            kind = root.string("kind").orEmpty(),
            status = root.string("status").orEmpty(),
            createdAt = createdAt,
            files = files,
            missing = missing,
            complete = complete,
            schema = ArchiveManifest.SCHEMA,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
}
