// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.settings

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 设置的导出内容与还原校验（**纯逻辑**，不碰文件、不碰 Compose）。
 *
 * 边界：
 * - 导出：只打包 [AppPreferences] 那张扁平偏好表（虚拟机数据不进来；那是几 GB 的镜像）；
 * - 还原：先解析+校验，再交给 [SettingsBackupViewModel] 让用户确认，最后 [apply] 一次写入；
 * - 文件读写由 UI 侧的 SAF 负责（系统文件选择器），这里只产出/消费字符串。
 */
object SettingsBackup {
    /** 系统文件选择器用的 MIME；备份文件是 JSON。 */
    const val MIME_TYPE = "application/json"

    /** 当前格式。1 是旧版（键值不带类型前缀以外的元数据），仍可读。 */
    const val SCHEMA = "versebox-settings/2"
    private const val SCHEMA_V1 = "versebox-settings/1"

    /** 一条设置。type 是类型标记，value 是它的文本形式。 */
    data class Entry(val key: String, val type: String, val value: String)

    /** 解析结果：给确认框用的全部事实。 */
    data class Preview(
        val schema: String,
        val exportedAt: Long,
        val appVersion: String,
        val entries: List<Entry>,
    ) {
        val count: Int get() = entries.size
    }

    /** 应用结果：写进去几条、跳过几条（类型不认识）。 */
    data class Applied(val applied: Int, val ignored: Int)

    /** 文件不是本应用的设置备份（文案即原因，直接给用户看）。 */
    class Invalid(reason: String) : IllegalArgumentException(reason)

    /** 建议文件名：`versebox-settings-<时间>.json`。 */
    fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "versebox-settings-$stamp.json"
    }

    /** 生成备份内容。所有值都带类型标记，避免把 "true" 当字符串塞回去。 */
    fun exportPayload(context: Context, versionName: String, versionCode: Long): String {
        val prefs = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        val values = JSONObject()
        for ((key, value) in prefs.all) {
            val entry = when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                else -> continue
            }
            values.put(key, entry)
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("exportedAt", System.currentTimeMillis())
            .put("appVersion", versionName + " (" + versionCode + ")")
            .put("values", values)
            .toString(2)
    }

    /** 解析 + 校验。格式不对抛 [Invalid]；能读就返回带全部条目的 [Preview]。 */
    fun parse(raw: String): Preview {
        val json = runCatching { JSONObject(raw) }
            .getOrElse { throw Invalid("不是 VerseBox 的设置备份：内容不是合法 JSON") }
        val schema = json.optString("schema")
        if (schema != SCHEMA && schema != SCHEMA_V1) {
            throw Invalid("不是 VerseBox 的设置备份：格式是「" + schema.ifBlank { "空" } + "」")
        }
        val values = json.optJSONObject("values")
            ?: throw Invalid("备份里没有设置项（values 缺失）")
        val entries = mutableListOf<Entry>()
        for (key in values.keys()) {
            val item = values.optJSONObject(key) ?: continue
            val type = item.optString("t")
            val value = when (type) {
                "s" -> item.optString("v")
                "b" -> item.optBoolean("v").toString()
                "i" -> item.optInt("v").toString()
                "l" -> item.optLong("v").toString()
                "f" -> item.optDouble("v").toString()
                else -> ""
            }
            entries += Entry(key = key, type = type, value = value)
        }
        if (entries.isEmpty()) throw Invalid("备份里没有可还原的设置项")
        return Preview(
            schema = schema,
            exportedAt = json.optLong("exportedAt"),
            appVersion = json.optString("appVersion").ifBlank { "未知版本" },
            entries = entries.sortedBy { it.key },
        )
    }

    /** 一次写入全部条目。只认这五种类型，别的计入 ignored。 */
    fun apply(context: Context, preview: Preview): Applied {
        val prefs = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var applied = 0
        var ignored = 0
        for (entry in preview.entries) {
            when (entry.type) {
                "s" -> editor.putString(entry.key, entry.value)
                "b" -> editor.putBoolean(entry.key, entry.value.toBoolean())
                "i" -> editor.putInt(entry.key, entry.value.toIntOrNull() ?: 0)
                "l" -> editor.putLong(entry.key, entry.value.toLongOrNull() ?: 0L)
                "f" -> editor.putFloat(entry.key, entry.value.toFloatOrNull() ?: 0f)
                else -> {
                    ignored++
                    continue
                }
            }
            applied++
        }
        editor.apply()
        return Applied(applied = applied, ignored = ignored)
    }

    /** 确认框正文：说清"这次要写进去什么"，而不是只写"确定还原吗"。 */
    fun describe(preview: Preview): String = buildString {
        append("备份来自 ")
        append(preview.appVersion)
        if (preview.exportedAt > 0L) {
            append("，导出于 ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(preview.exportedAt)))
        }
        append("。\n将写入 ")
        append(preview.count)
        append(" 项设置（含外观、主题、角标等），覆盖当前值。")
    }
}