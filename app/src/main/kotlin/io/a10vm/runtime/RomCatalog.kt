package io.a10vm.runtime

import android.content.Context
import android.util.Log

/**
 * 一个可以安装成虚拟机的客体平台。
 *
 * 只描述“在哪、是什么版本”，不参与运行库哈希校验：真正的输入清单是
 * `romRoot/versebox_inputs.json`（ROM 包）或 `assets/<runtimeManifest>`（旧格式）。
 *
 * [romRoot] 是本轮新增的唯一字段：**null = 走原来的 assets 路径**（旧实例、旧格式 ROM
 * 逐字不变），非 null = 从这个已解包的 ROM 目录读清单与 payload
 * （`docs/rom-package/03` §3.1）。[id] 对新包装是 romKey（`android15@1.0.0`），
 * 对旧格式就是 `android15`。
 */
data class RomSpec(
    val id: String,
    val name: String,
    val androidVersion: String,
    val apiLevel: Int,
    val assetPrefix: String,
    val runtimeManifest: String,
    val displayManifest: String,
    val description: String,
    /** 已解包的 ROM 目录（`files/roms/<romKey>/`）；null = assets 旧路径。 */
    val romRoot: java.io.File? = null,
    /** `rom` / `apk` / `both`：客体 .so 的来源（`docs/rom-package/04` §4）。 */
    val libMode: String = "apk",
) {
    /** 管理页、开机画面与导入提示统一使用的名称。 */
    val title: String get() = "$name · API $apiLevel"
}

/** Discovers the guest platforms packaged in this APK from their YAML descriptors. */
class RomCatalog(private val context: Context) {
    private companion object {
        const val TAG = "A10VM_ROM"
        const val DIRECTORY = "roms"
        const val SCHEMA = "versebox-rom/1"
        val ID = Regex("[a-z0-9][a-z0-9._-]*")
    }

    private val assets = context.applicationContext.assets
    private val lock = Any()
    private var cached: List<RomSpec>? = null

    fun list(): List<RomSpec> = synchronized(lock) { cached ?: load().also { cached = it } }

    fun find(id: String?): RomSpec? = list().firstOrNull { it.id == id }

    fun first(): RomSpec? = list().firstOrNull()

    private fun load(): List<RomSpec> {
        val names = runCatching { assets.list(DIRECTORY)?.toList().orEmpty() }.getOrDefault(emptyList())
        val roms = names.filter { it.endsWith(".yaml") || it.endsWith(".yml") }
            .sorted()
            .mapNotNull { name ->
                runCatching { read("$DIRECTORY/$name") }
                    .onFailure { Log.w(TAG, "忽略 ROM 描述 $name：${it.message}") }
                    .getOrNull()
            }
            .distinctBy { it.id }
        Log.i(TAG, "ROM 目录：${roms.size} 个可用" +
            (if (roms.isEmpty()) "" else "（" + roms.joinToString { "${it.id}=${it.androidVersion}" } + "）"))
        return roms
    }

    private fun read(path: String): RomSpec {
        val text = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        val values = yaml(text)
        fun value(key: String): String = values[key]?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("$path 缺少 $key")
        require(value("schema") == SCHEMA) { "$path 的 schema 不受支持" }
        val id = value("id")
        require(ID.matches(id)) { "$path 的 id 不合法" }
        val api = values["apiLevel"]?.toIntOrNull() ?: error("$path 的 apiLevel 不合法")
        require(api in 26..35) { "$path 的 apiLevel 超出范围" }
        val prefix = value("assetPrefix").let { if (it.endsWith("/")) it else "$it/" }
        val runtime = value("runtimeManifest")
        val display = value("displayManifest")
        listOf(runtime, display).forEach { asset ->
            runCatching { assets.open(asset).close() }
                .onFailure { error("$path 指向的 assets/$asset 不存在") }
        }
        return RomSpec(
            id = id,
            name = value("name"),
            androidVersion = value("androidVersion"),
            apiLevel = api,
            assetPrefix = prefix,
            runtimeManifest = runtime,
            displayManifest = display,
            description = values["description"].orEmpty(),
        )
    }

    /** ROM 描述只用到平铺的 `key: value`，不需要为此引入完整的 YAML 依赖。 */
    private fun yaml(text: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
            if (key.isNotEmpty()) values[key] = value
        }
        return values
    }
}
