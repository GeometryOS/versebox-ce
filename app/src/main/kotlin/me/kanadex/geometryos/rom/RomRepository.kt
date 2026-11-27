// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import android.content.Context
import android.net.Uri
import android.util.Log
import io.a10vm.records.RecordCollector
import io.a10vm.records.RecordKind
import io.a10vm.records.RecordStatus
import io.a10vm.records.RecordStore
import io.a10vm.records.RomInstallRow
import io.a10vm.runtime.RomCatalog
import io.a10vm.runtime.RomSpec
import io.a10vm.runtime.VmRegistry
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kanadex.geometryos.audit.android.AuditBootstrap
import org.json.JSONObject

/**
 * ROM 仓库（`:app` 侧的**适配层**）：扫描、导入、解包、删除、三段解析。
 *
 * 边界（`docs/rom-package/14-版本管理/03`）：
 * 1. 契约（自述字段/版本比较/解包流水线/版本决策/全部文案）在 `:vm-rom`，这里只做
 *    「Android 的落点在哪 + 谁被谁引用 + 写审计与记录」；
 * 2. 只吐 [RomRow] / [RomVersionEntry]，不拼 UI 文案；
 * 3. 所有方法都在调用方的 IO 线程上跑，内部不做线程切换；
 * 4. 每次写入都写审计事件（`docs/rom-package/06` §5），并 bump [revision]。
 *
 * ## 性能：扫描路径上禁止做重活
 *
 * 这一条是给「进页面卡一下」立的判据（`docs/ui-rework/05` §5 的加强条款）：
 *  - **不量目录大小**。以前每次扫描都对每个 ROM 目录做 `walkTopDown`（3400 个文件 / 1 GB），
 *    3 个 ROM 就是上万次 stat。现在大小只来自 DB（安装/解包/删除时写一次）；
 *  - **不打开内置 zip**。构建期生成的 `assets/kanadex-rom/index.json` 直接给出 id/版本/大小，
 *    不再对每个内置包做顺序解压（`ZipInputStream` 要读到自述所在位置，最坏情况是整个包）；
 *  - **结果按 revision 缓存**。页面的 `refresh()` 与 `AppState.refreshRoms()` 会连续扫两次，
 *    第二次必须命中缓存。
 */
class RomRepository private constructor(context: Context) {

    companion object {
        private const val TAG = "VERSEROOM"
        private const val DISPLAY_MANIFEST = "native-display-inputs.json"

        @Volatile private var instance: RomRepository? = null

        fun get(context: Context): RomRepository =
            instance ?: synchronized(this) {
                instance ?: RomRepository(context.applicationContext).also { instance = it }
            }
    }

    private val app = context.applicationContext
    private val store get() = RecordStore.get(app)
    private val layout get() = RomStorage.layout(app)
    private val extractor by lazy { RomStorage.extractor(app) }
    private val assets get() = app.assets

    /** 旧格式 ROM 的目录只解析一次（每次 new 一个 RomCatalog 都会把 3 份 YAML 重新解析一遍）。 */
    private val legacyCatalog by lazy { RomCatalog(app) }

    /**
     * ROM 写入的**单飞锁**（single-flight）。
     *
     * 启动链的自愈（`VmService.ensureRomUsable`）、ROM 管理里的修复、导入、删除、
     * 清缓存都会写 `files/roms` 下的目录。它们以前可以并发：真机上出现过两条 rom_failed
     * 相隔 59 ms（一条 rootDir、一条 无法创建解包目录），而手动点一次永远成功。
     *
     * 所以：**写操作串行**；读操作（[rows] / [resolve] / [checkRom]）不加锁，不受影响。
     */
    private val writeLock = Any()

    private inline fun <T> exclusive(block: () -> T): T = synchronized(writeLock) { block() }

    private val _revision = MutableStateFlow(0L)

    /** 每次写入 +1；观察者据此重新扫 ROM。 */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    // --------------------------------------------------- 内容寻址（P5 / R4）

    /**
     * 磁盘上现有的**目录**（名字 → File），一次扫描只列一次。
     *
     * 只用于「这个 romKey 的哪一份已经解包在盘上」，不做任何递归、不读文件内容：
     * 扫描路径上不许量目录 / 解压（`docs/ui-rework/05` §11.1 第 3 条）。
     */
    private fun dirsOnDisk(): Map<String, File> =
        layout.root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != RomLayout.ARCHIVES }
            ?.associateBy { it.name }
            .orEmpty()

    /** 解包目录自己的自述指纹（600 字节的自述，不是量目录）。 */
    private fun dirSha(dir: File): String = runCatching {
        RomPackage.readPropertiesFile(File(dir, RomLayout.PROPERTIES)).payloadSha256
    }.getOrDefault("")

    /**
     * 这个 romKey 的**这一份内容**在磁盘上解包在哪个目录。
     *
     * 顺序：内容寻址目录（`<key>@<sha8>`）→ 旧布局（`<key>`，只读兼容）。
     * 旧布局只有在**指纹对得上**（或这一行本来就没有指纹）时才算命中——
     * 否则会把另一份内容的目录当成这一份，`payloadProblems()` 就会去修错那一棵。
     */
    private fun extractedDirOf(
        key: String,
        payloadSha256: String,
        onDisk: Map<String, File>,
    ): Pair<File, String>? {
        val tagged = onDisk[layout.dirName(key, payloadSha256)]
        if (tagged != null && layout.isExtracted(tagged)) {
            val sha = dirSha(tagged)
            if (payloadSha256.isEmpty() || sha.isEmpty() || sha == payloadSha256) return tagged to sha
        }
        val legacy = onDisk[key]
        if (legacy != null && legacy != tagged && layout.isExtracted(legacy)) {
            val sha = dirSha(legacy)
            if (payloadSha256.isEmpty() || sha.isEmpty() || sha == payloadSha256) return legacy to sha
        }
        return null
    }

    /** 这一份内容现在是否已经解包在磁盘上（按指纹核对，不按目录名猜）。 */
    private fun isExtractedOnDisk(key: String, payloadSha256: String, onDisk: Map<String, File>): Boolean {
        val found = extractedDirOf(key, payloadSha256, onDisk) ?: return false
        val sha = found.second
        if (payloadSha256.isEmpty() || sha.isEmpty()) return true
        return sha == payloadSha256
    }

    /**
     * 一行的解包目录。
     *
     * 已经解包的返回**真正在用的那个目录**（可能是旧布局）；没解包的返回"下一次会写到这里"的
     * 内容寻址目录，调用方据此判断"目录还不存在"。
     */
    private fun dirOf(row: RomRow): File =
        extractedDirOf(row.key, row.payloadSha256, dirsOnDisk())?.first
            ?: layout.dirFor(row.key, row.payloadSha256)

    private fun archiveBytes(key: String, payloadSha256: String, builtinSizes: Map<String, Long>): Long {
        val archive = layout.archiveFor(key, payloadSha256)
        if (archive.isFile) return archive.length()
        val legacy = layout.archive(key)
        if (legacy.isFile) return legacy.length()
        return builtinSizes[key] ?: 0L
    }

    private fun measuredExtractedBytes(dir: File, key: String, payloadSha256: String): Long {
        val cacheKey = payloadSha256.ifBlank { key + "#legacy" }
        store.romExtractedBytes(cacheKey)?.takeIf { it > 0L }?.let { return it }
        return layout.measuredBytes(dir).also { store.saveRomExtractedBytes(cacheKey, it) }
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 扫一遍「DB 登记 + 磁盘目录 + APK 内置 + 旧格式 assets」。
     *
     * 只读元数据（自述、索引、目录是否存在），**不解包、不哈希 payload、不量目录**。
     * 结果按 [revision] + 注册表 revision 缓存（见类注释的性能条款）。
     */
    fun rows(): List<RomRow> {
        val registryRevision = runCatching { VmRegistry.get(app).revision.value }.getOrDefault(-1L)
        val stamp = _revision.value to registryRevision
        rowsCache?.let { cached -> if (cached.first == stamp) return cached.second }
        val fresh = scan()
        rowsCache = stamp to fresh
        return fresh
    }

    @Volatile private var rowsCache: Pair<Pair<Long, Long>, List<RomRow>>? = null

    private fun scan(): List<RomRow> {
        val devices = runCatching { VmRegistry.get(app).list() }.getOrElse { emptyList() }
        // 列表身份是 rowId（<来源>:<romKey>），不是一个 romKey 只能有一行：
        // **内置与本地导入的同一个版本号必须并存**（内置永远可解包、永远不被本地覆盖）。
        val rows = ArrayList<RomRow>()

        // 内置包只解析一次：下面的 DB 行、磁盘行、内置行都要用它。
        val builtin = builtinProperties()
        val builtinProps = builtin.associate { it.properties.key to it.properties }
        val builtinZipBytes = builtin.associate { it.properties.key to it.archiveBytes }
        val builtinKeys = builtinProps.keys

        // 磁盘上现有哪些目录：一次列一遍，下面每一行都用它判"我这_一份_解包了没有"。
        // 同一个 romKey 的两份内容分别住在 `<key>@<sha8>`，所以判据必须是「目录名 + 自述指纹」两段，
        // 不能再用"目录在不在"代表"这一份内容在不在"（P5 / R4）。
        val onDisk = dirsOnDisk()

        // 已出现过的 (romKey, 内容指纹) 对。
        // 「同 key 两份不同内容」要**两行**：只按 romKey 去重会把第二份吃掉
        // （`docs/rom-package/14-版本管理/04` R2/R3）。
        val seen = HashSet<String>()
        fun mark(key: String, sha: String) { seen += key + "#" + sha }
        fun seenBefore(key: String, sha: String): Boolean = seen.contains(key + "#" + sha)

        // 1) DB 登记：只有**本地导入**才入库。
        //    内置包不进 DB（它的存在来自 APK 索引，状态由"目录在不在"推出来），
        //    否则同一个 rom_key 只有一行，两张表合起来必然互相覆盖——这就是上一版
        //    「本地导入同版本后内置 ROM 从列表消失」的根因。
        for (record in runCatching { store.romRows() }.getOrElse { emptyList() }) {
            if (record.source == RomSource.Builtin.name) continue
            val row = reconcile(record, builtinProps, onDisk, builtinZipBytes)
            rows += row
            mark(record.key, record.payloadSha256)
        }

        // 2) 磁盘上有、DB 里没有的目录（手工拷入 / DB 被清）。大小不在这里量（性能条款）。
        //
        //    **内置包的解包体不属于这一档**（2026-11-14 修正）：内置 ROM 与本地 ROM 是平等的两份
        //    ROM，解包只是内置那一行的状态（Pending → Ready），不是"用内置包新建了一份本地 ROM"。
        //    以前这里无条件补一行 Imported，于是解包完一个内置 ROM，列表里就多出一个可以删除的
        //    「本地 ROM」——真机 audit.log 里能直接看到它被用户当重复项删掉（source=imported）。
        //    归属判据只有一份实现：:vm-rom 的 RomUsage.belongsToBuiltin（有单测）。
        val builtinRefs = builtin.map { RomRef(it.properties.key, RomSource.Builtin, it.properties.payloadSha256) }
        for (dir in onDisk.values) {
            val properties = runCatching {
                RomPackage.readPropertiesFile(File(dir, RomLayout.PROPERTIES))
            }.getOrNull() ?: continue
            // 目录名可能是 `<key>@<sha8>`：身份要用自述里的 romKey + 指纹，不是目录名。
            if (seenBefore(properties.key, properties.payloadSha256)) continue
            // 这个目录是某个内置包解出来的：它由第 3 步那一条内置行代表，不能再补一行本地。
            if (RomUsage.belongsToBuiltin(properties.key, properties.payloadSha256, builtinRefs)) continue
            val ready = layout.isExtracted(dir)
            rows += properties.toRow(
                source = RomSource.Imported,
                state = if (ready) RomState.Ready else RomState.Failed,
                extractedBytes = if (ready) measuredExtractedBytes(dir, properties.key, properties.payloadSha256) else 0L,
                archiveBytes = archiveBytes(properties.key, properties.payloadSha256, builtinZipBytes),
                failure = if (ready) null else "解包目录不完整，请重新导入",
                installedAt = dir.lastModified(),
                hasArchive = hasArchive(properties.key, properties.payloadSha256) ||
                    properties.key in builtinKeys,
                extractedSha256 = if (ready) dirSha(dir) else "",
            )
            mark(properties.key, properties.payloadSha256)
        }

        // 3) APK 内置包：**无条件出现**（这是核心语义，不是兜底）。
        //    同 key 已经有本地行也照样加一行：两行并存，内置那一行永远有「检查 / 重新解包」。
        for (entry in builtin) {
            val properties = entry.properties
            // 「解包了没有」= 磁盘上存在**这一份内容**的目录（指纹核对），不是"某个 key 的目录在"：
            // 内置 1.0.0 与本地导入 1.0.0 内容不同时，两行各有各的目录，状态必须各算各的。
            val extractedDir = extractedDirOf(properties.key, properties.payloadSha256, onDisk)
                ?.first?.takeIf { !properties.placeholder && layout.isExtracted(it) }
            val extracted = extractedDir != null
            rows += properties.toRow(
                source = RomSource.Builtin,
                state = if (extracted) RomState.Ready else RomState.Pending,
                extractedBytes = extractedDir?.let { measuredExtractedBytes(it, properties.key, properties.payloadSha256) } ?: 0L,
                archiveBytes = entry.archiveBytes,
                extractedSha256 = if (extracted) properties.payloadSha256 else "",
                hasArchive = !properties.placeholder,
            )
        }

        // 4) 兼容期：APK 里 assets/roms 目录下 yaml 的旧格式 ROM（数据仍在 assets）
        for (spec in legacySpecs()) {
            if (rows.any { it.key == spec.id }) continue
            rows += RomRow(
                key = spec.id, id = spec.id, version = RomKeys.LEGACY_VERSION,
                displayName = spec.name, description = spec.description,
                androidVersion = spec.androidVersion, apiLevel = spec.apiLevel,
                source = RomSource.Legacy, state = RomState.Ready,
                libMode = "apk", installedAt = 0L,
            )
        }

        // 使用台数：由注册表数出来，页面一个数字都不算。
        //
        // **一台实例只归属一行**（2026-11-12 维护者点名）：列表里的每一行都是一个独立的 ROM，
        // 本地与内置不是"同一个版本的两个分支"，同来源导入的两份也是两个版本。
        // 原来的写法是 device × row 的双重循环，于是内置行与本地行会**同时**+1：
        // 真机上表现为"内置那里 1 台在用、本地那里也是 1 台在用"，选择其中任何一个都不改变另一边的数字，
        // 计数等于没有。现在先把"这台用哪一行"解析出来，再累加一次。
        // 归属判据只有一份实现：`:vm-rom` 的 `RomUsage.owner`（有单测）。
        // 这里只负责"按身份找一次、加一次"——双循环会让内置与本地同版本时两边都 +1。
        val used = HashMap<String, MutableList<String>>()
        for (device in devices) {
            val owner = RomUsage.owner(rows, device.romId, device.romPayload, device.romSource) ?: continue
            used.getOrPut(owner.rowId) { mutableListOf() } += device.name
        }
        val result = rows
            .map { row ->
                val names = used[row.rowId].orEmpty()
                row.copy(usedByVms = names.size, usedByNames = names)
            }
            .sortedWith(
                compareBy<RomRow> { it.source.ordinal }
                    .thenBy { if (it.isReady) 0 else 1 }
                    .thenByDescending { it.installedAt },
            )
        Log.i(TAG, "ROM 扫描：" + result.size + " 条（就绪 " + result.count { it.isReady } +
            "，本地 " + result.count { it.source == RomSource.Imported } +
            "，内置 " + result.count { it.source == RomSource.Builtin } + "）")
        return result
    }

    /** 内置包（自述 + 包文件名）：索引优先，索引不存在时逐个读 zip 自述。 */
    private data class Builtin(val fileName: String, val properties: RomProperties, val archiveBytes: Long)

    private fun builtinProperties(): List<Builtin> {
        val names = runCatching { assets.list(RomStorage.ASSET_DIR)?.toList().orEmpty() }
            .getOrElse { emptyList() }
        val zips = names.filter { it.endsWith(".zip", ignoreCase = true) }
        if (zips.isEmpty()) return emptyList()
        readIndex()?.let { indexed ->
            val known = indexed.filter { it.fileName in zips }
            // 索引里有、目录里的 zip 也在：直接用。索引缺失的 zip 再单独读自述（新增的包还没重建索引）。
            if (known.isNotEmpty()) {
                val missing = zips.filter { name -> known.none { it.fileName == name } }
                return known + missing.mapNotNull { readAssetQuietly(it) }
            }
        }
        return zips.mapNotNull { readAssetQuietly(it) }
    }

    private fun readAssetQuietly(name: String): Builtin? = runCatching {
        val path = RomStorage.ASSET_DIR + "/" + name
        val archiveBytes = runCatching { assets.openFd(path).length }.getOrDefault(0L)
        Builtin(name, RomPackage.readFromAsset(assets, path), archiveBytes)
    }.getOrNull()

    /** 读构建期索引（`assets/kanadex-rom/index.json`）。缺失或损坏返回 null（退回读 zip）。 */
    private fun readIndex(): List<Builtin>? = runCatching {
        val text = assets.open(RomStorage.ASSET_DIR + "/" + RomStorage.INDEX_NAME)
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(text)
        val entries = root.optJSONArray("entries") ?: return@runCatching null
        val result = mutableListOf<Builtin>()
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            val file = item.optString("file")
            if (file.isEmpty()) continue
            val properties = RomProperties(
                schema = RomPropertiesYaml.SCHEMA,
                id = item.optString("id"),
                romVersion = item.optString("romVersion"),
                name = item.optString("name"),
                androidVersion = item.optString("androidVersion"),
                apiLevel = item.optInt("apiLevel"),
                description = item.optString("description"),
                inputs = item.optString("inputs").ifBlank { RomLayout.INPUTS },
                payloadRoot = RomLayout.PAYLOAD_DIR,
                payloadBytes = item.optLong("payloadBytes"),
                payloadSha256 = item.optString("payloadSha256"),
                libMode = item.optString("libMode").ifBlank { "apk" },
                packedBy = item.optString("packedBy"),
                packedAt = item.optString("packedAt"),
                placeholder = item.optBoolean("placeholder"),
            )
            // 索引也是数据：字段不合法就当没有索引（构建期已经校验过，这里只防手改）。
            if (!RomKeys.isValidId(properties.id) || !RomKeys.isValidVersion(properties.romVersion)) continue
            result += Builtin(file, properties, item.optLong("zipBytes"))
        }
        result
    }.getOrNull()

    /** 按**列表身份**（`<来源>:<romKey>@<sha8>`）取一行。UI 的动作都带 rowId，不再靠 romKey 猜是哪一行。 */
    fun rowById(rowId: String): RomRow? = rows().firstOrNull { it.rowId == rowId }

    /**
     * 按**运行期身份**（[RomRef]：romKey + 来源 + 内容指纹）取一行。
     *
     * 这是「这台虚拟机用的是哪一份」的唯一解析入口（启动链、自愈、版本页、ROM 列表都走它）：
     *  - 指纹非空：只认同指纹的行（来源优先匹配，其次是同内容的另一来源）；
     *  - 指纹为空（旧实例）：退回"同 romKey 的第一行"，来源优先匹配。
     *
     * 指纹非空却一行都对不上 = 这一份内容真的不在了，返回 null 让调用方按"缺 ROM"处理，
     * **不许**拿同版本的另一份顶替（那会让用户以为换成功，其实跑的是另一个 ROM）。
     */
    fun rowFor(ref: RomRef): RomRow? {
        if (ref.key.isEmpty()) return null
        val sameKey = rows().filter { it.key == ref.key }
        if (sameKey.isEmpty()) return null
        if (ref.payloadSha256.isEmpty()) {
            return sameKey.firstOrNull { ref.source == null || it.source == ref.source } ?: sameKey.first()
        }
        val samePayload = sameKey.filter { it.payloadSha256.isNotEmpty() && it.payloadSha256 == ref.payloadSha256 }
        if (samePayload.isEmpty()) return null
        return samePayload.firstOrNull { ref.source == null || it.source == ref.source } ?: samePayload.first()
    }

    /** 旧格式（assets/roms）的 [RomSpec] 列表；只解析一次。 */
    fun legacySpecs(): List<RomSpec> = runCatching { legacyCatalog.list() }.getOrElse { emptyList() }

    /** 把 DB 行与磁盘现状对齐：**这一份内容**的目录没了就是 missing，别的状态照抄。 */
    private fun reconcile(
        record: RomInstallRow,
        builtinProps: Map<String, RomProperties> = emptyMap(),
        onDisk: Map<String, File> = dirsOnDisk(),
        builtinZipBytes: Map<String, Long> = emptyMap(),
    ): RomRow {
        val source = runCatching { RomSource.valueOf(record.source) }.getOrDefault(RomSource.Imported)
        val stored = runCatching { RomState.valueOf(record.state) }.getOrDefault(RomState.Pending)
        val found = extractedDirOf(record.key, record.payloadSha256, onDisk)
        val extractedSha = found?.second.orEmpty()
        val extractedDir = found?.first?.takeIf { layout.isExtracted(it) }
        val exists = found != null &&
            (record.payloadSha256.isEmpty() || extractedSha.isEmpty() || extractedSha == record.payloadSha256)
        val state = when {
            stored == RomState.Ready && !exists -> RomState.Missing
            stored == RomState.Extracting -> if (exists) RomState.Ready else RomState.Pending
            stored == RomState.Ready -> RomState.Ready
            else -> stored
        }
        val extractedBytes = if (state == RomState.Ready && extractedDir != null) {
            record.extractedBytes.takeIf { it > 0L }
                ?: measuredExtractedBytes(extractedDir, record.key, record.payloadSha256)
        } else {
            0L
        }
        return RomRow(
            key = record.key, id = record.id, version = record.version,
            displayName = record.displayName, description = record.description,
            androidVersion = record.androidVersion, apiLevel = record.apiLevel,
            source = source, state = state, failure = record.failure,
            payloadBytes = record.payloadBytes,
            archiveBytes = if (record.archiveBytes > 0L) record.archiveBytes
                else archiveBytes(record.key, record.payloadSha256, builtinZipBytes),
            extractedBytes = extractedBytes,
            libMode = record.libMode, installedAt = record.installedAt,
            lastUsedAt = record.lastUsedAt,
            payloadSha256 = record.payloadSha256,
            // 占位包永远不会被解包，它的「不可用」只能从内置索引读，不能靠 state 猜。
            placeholder = builtinProps[record.key]?.placeholder == true,
            // 原包的两种来路：本地 archives 里的 zip（按内容指纹命名），或 APK 内置的那个 zip。
            // 这一条决定「检查出问题后能不能重新解包」，页面不许自己去 stat 文件。
            hasArchive = hasArchive(record.key, record.payloadSha256) ||
                builtinProps.containsKey(record.key),
            extractedSha256 = if (state == RomState.Ready) extractedSha else "",
        )
    }

    // ------------------------------------------------------------------ 导入

    /** 导入结果：成功 / 重复（同 key 同 payload 哈希）。 */
    data class Outcome(val row: RomRow, val duplicate: Boolean)

    /**
     * 从 SAF 拿到的 uri 导入一个 ROM 包（`docs/rom-package/06` §6.1）。
     *
     * 失败一定删掉半成品并写审计 `rom_failed`，**不留"看起来装好了"的目录**。
     */
    fun importFromUri(uri: Uri, progress: (RomProgress) -> Unit): Outcome = exclusive {
        importFromUriLocked(uri, progress)
    }

    private fun importFromUriLocked(uri: Uri, progress: (RomProgress) -> Unit): Outcome {
        val started = System.currentTimeMillis()
        val events = mutableListOf<String>()
        val temp = File(app.cacheDir, "rom-import-" + started + ".zip")
        var key: String? = null
        var romName: String? = null
        var apiLevel = 0
        try {
            val zipHash = copyUri(uri, temp)
            events += "SAF文件复制完成 bytes=${temp.length()} sha256=$zipHash"
            val properties = RomPackage.read(temp)
            events += "ROM自述和L1结构校验通过 key=${properties.key}"
            key = properties.key
            romName = properties.name
            apiLevel = properties.apiLevel
            // 只和**本地导入**的登记比：内置包里的同一份不是"重复导入"，
            // 它是另一行（内置那行永远在，见 scan()）。
            val existing = store.romRow(properties.key)?.takeIf { it.source == RomSource.Imported.name }
            if (existing != null && existing.payloadSha256 == properties.payloadSha256 &&
                isExtractedOnDisk(properties.key, properties.payloadSha256, dirsOnDisk())
            ) {
                temp.delete()
                record(RecordKind.ROM_IMPORT, RecordStatus.SUCCESS, properties.key, properties.name,
                    properties.apiLevel, "已在列表里，没有重复解包：" + properties.key, started, events)
                return Outcome(reconcile(existing), duplicate = true)
            }
            if (!layout.isValidKey(properties.key)) {
                throw RomException("ROM 标识不合法：" + properties.key)
            }
            layout.archives().mkdirs()
            // 原包（L1）按**内容**命名：`archives/<key>@<sha8>.zip`。
            // 只按 romKey 命名时，同版本的另一份不同内容会把前一份 zip 干掉——
            // 而 zip 是唯一不可变的修复来源（`_audit/09` 红线 1），删了就等于让那一份永远无法自愈。
            val archive = layout.archiveFor(properties.key, properties.payloadSha256)
            if (!archive.isFile) {
                if (!temp.renameTo(archive)) {
                    temp.copyTo(archive, overwrite = true)
                    temp.delete()
                }
            } else {
                // 同内容已经在 archives 里：不重复写（R2：同一份内容不许存两份）。
                temp.delete()
            }
            events += "不可变原包已保存 path=${archive.name} bytes=${archive.length()}"
            store.saveRomRow(
                properties.toRecord(
                    source = RomSource.Imported,
                    state = RomState.Extracting,
                    zipSha256 = zipHash,
                    archiveBytes = archive.length(),
                    installedAt = System.currentTimeMillis(),
                ),
            )
            audit("rom_imported", mapOf(
                "romKey" to properties.key,
                "zip_sha256" to zipHash,
                "bytes" to archive.length(),
                "originScheme" to (uri.scheme ?: "unknown"),
            ))
            val dir = extract(archive, properties, progress)
            val extractedBytes = measuredExtractedBytes(dir, properties.key, properties.payloadSha256)
            val archiveBytes = archive.length()
            val row = properties.toRow(
                source = RomSource.Imported,
                state = RomState.Ready,
                archiveBytes = archiveBytes,
                extractedBytes = extractedBytes,
                installedAt = System.currentTimeMillis(),
                hasArchive = true,
            )
            store.saveRomRow(properties.toRecord(
                source = RomSource.Imported,
                state = RomState.Ready,
                extractedBytes = extractedBytes,
                zipSha256 = zipHash,
                archiveBytes = archiveBytes,
                installedAt = System.currentTimeMillis(),
            ))
            audit("rom_extracted", mapOf(
                "romKey" to properties.key,
                "files" to countFiles(dir),
                "bytes" to extractedBytes,
            ))
            events += "L2解包并逐项校验完成 files=${countFiles(dir)} extracted_bytes=$extractedBytes"
            events += "rom_install状态写回并读回成功 archive_bytes=$archiveBytes"
            record(RecordKind.ROM_IMPORT, RecordStatus.SUCCESS, properties.key, properties.name,
                properties.apiLevel,
                "已导入 " + properties.key + "（解包 " + RomTexts.bytes(extractedBytes) +
                    "，包 " + RomTexts.bytes(archiveBytes) + "）",
                started, events)
            bump()
            return Outcome(row, duplicate = false)
        } catch (failure: Throwable) {
            temp.delete()
            val reason = failure.message ?: failure.javaClass.simpleName
            key?.let { romKey ->
                runCatching { store.updateRomState(romKey, RomState.Failed.name, reason) }
                audit("rom_failed", mapOf("romKey" to romKey, "reason" to reason))
            }
            record(RecordKind.ROM_IMPORT, RecordStatus.FAILED, key, romName, apiLevel,
                "导入失败：" + reason, started, events, failure)
            bump()
            throw failure
        }
    }

    /**
     * **解包的唯一入口**：确认这个 ROM 的 payload 在、且完整；没解包或不全就从**原始 ROM 包**解出来。
     *
     * 三个调用方都是同一条链（`docs/rom-package/12-自愈与修复/`）：
     *  - 启动链（`VmService.ensureRomUsable`，每台实例每次启动前）；
     *  - ROM 管理里用户点的「重新解包」（`RomListAction.RequestRepair`）；
     *  - ROM 管理里「新建虚拟机」之前（内置包首次使用的常态路径）。
     *
     * 曾经还有一个 `ensureExtracted()` 与它并排（只差审计事件名），于是"内置包首次使用"与
     * "修一份坏掉的解包体"走了两条几乎一样的实现，改一处忘一处。现在只有这一条：
     * 首次解包记 `rom_extracted`，修坏包记 `rom_repaired`（判断依据是有没有缺项）。
     *
     * **调用方不该在这里之外自己拼解包逻辑**：创建虚拟机只做 [gate] 判定，缺了就回 ROM 管理走这条链。
     *
     * 为什么必须有它：Android 13+ 在"电量充满"等场景会让 data 目录下的部分文件消失，
     * 而 A10 / A9 一直靠"每次启动按清单逐条比对、缺了就从不可变来源重拷"来自愈。
     * 修复来源必须是压缩包本身（`archives/<key>.zip` 或 APK 里的内置包），
     * 解包目录只是它的派生物（`_audit/09` §2）。
     *
     * 身份必须带内容指纹（[payloadSha256]）：同版本两份内容时，只按 romKey 解出来的是另一份——
     * 用户选的和他开机看到的就不是同一个系统。旧格式实例走 APK assets，不需要也不该走这一层。
     */
    fun ensureUsable(
        key: String,
        payloadSha256: String = "",
        progress: (RomProgress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): RomRow = exclusive {
        ensureUsableLocked(key, payloadSha256, progress, isCancelled)
    }

    private fun ensureUsableLocked(
        key: String,
        payloadSha256: String,
        progress: (RomProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): RomRow {
        val started = System.currentTimeMillis()
        val existing = rowFor(RomRef(key, null, payloadSha256))
        val problems = existing?.let { payloadProblems(it) }.orEmpty()
        val patchableDir = existing?.let { row ->
            layout.dirFor(row.key, row.payloadSha256).takeIf { layout.isExtracted(it) }
        }
        val operationKind = when {
            existing == null || existing.state == RomState.Pending -> RecordKind.ROM_EXTRACT
            patchableDir != null -> RecordKind.ROM_REPAIR
            else -> RecordKind.ROM_REEXTRACT
        }
        val archive = try {
            resolveArchive(key, payloadSha256.ifEmpty { existing?.payloadSha256.orEmpty() })
                ?: throw RomException("ROM 缺失：" + key + "（安装包里没有这个 ROM，请重新导入）")
        } catch (failure: Throwable) {
            record(operationKind, RecordStatus.FAILED, key, existing?.displayName, existing?.apiLevel ?: 0,
                "ROM原包不可用：" + (failure.message ?: failure.javaClass.simpleName), started,
                listOf("查找不可变ROM原包失败"), failure)
            throw failure
        }
        if (existing != null && existing.isReady && problems.isEmpty()) return existing

        val properties = try {
            RomPackage.read(archive)
        } catch (failure: Throwable) {
            record(operationKind, RecordStatus.FAILED, key, existing?.displayName, existing?.apiLevel ?: 0,
                "ROM原包自述读取失败：" + (failure.message ?: failure.javaClass.simpleName), started,
                listOf("读取原包自述和清单失败"), failure)
            throw failure
        }
        val source = existing?.source ?: RomSource.Imported
        // 内置包不写 DB：它的"在不在、解没解"由 APK 索引 + 目录推出，写进去就会和本地行抢同一个 rom_key。
        val tracked = source != RomSource.Builtin
        val installedAt = existing?.installedAt?.takeIf { it > 0L } ?: started
        val events = mutableListOf("原包定位成功，bytes=" + archive.length(), "解包前检查发现缺失条目=" + problems.size)
        if (tracked) {
            store.saveRomRow(properties.toRecord(
                source = source,
                state = RomState.Extracting,
                zipSha256 = runCatching { RomHash.fileSha256(archive) }.getOrNull(),
                archiveBytes = archive.length(),
                installedAt = installedAt,
            ))
            events += "rom_install状态写为extracting"
        } else {
            events += "内置ROM来源确认，保持APK asset原包不变"
        }
        bump()
        try {
            val dir = extract(archive, properties, progress, isCancelled)
            val extractedBytes = measuredExtractedBytes(dir, key, properties.payloadSha256)
            if (tracked) {
                store.saveRomRow(properties.toRecord(
                    source = source,
                    state = RomState.Ready,
                    extractedBytes = extractedBytes,
                    zipSha256 = runCatching { RomHash.fileSha256(archive) }.getOrNull(),
                    archiveBytes = archive.length(),
                    installedAt = installedAt,
                ))
            }
            // 审计事件按**这次做了什么**分：首次解包（没有缺项）是 rom_extracted，
            // 修一份坏掉的解包体才是 rom_repaired。两者原来共用一个名字，
            // 事后翻 audit.log 分不出来"这条是第一次装还是修过一次"（`docs/rom-package/06` §5）。
            if (problems.isEmpty()) {
                audit("rom_extracted", mapOf(
                    "romKey" to key,
                    "source" to source.name.lowercase(),
                    "files" to countFiles(dir),
                    "bytes" to extractedBytes,
                    "took_ms" to (System.currentTimeMillis() - started),
                ))
            } else {
                audit("rom_repaired", mapOf(
                    "romKey" to key,
                    "source" to source.name.lowercase(),
                    "missing" to problems.size,
                    "bytes" to extractedBytes,
                    "took_ms" to (System.currentTimeMillis() - started),
                ))
            }
            events += "L2逐项校验完成 files=" + countFiles(dir) + " extracted_bytes=" + extractedBytes
            events += if (problems.isEmpty()) "ROM首次解包完成" else "派生ROM缺失内容已从原包恢复"
            record(operationKind, RecordStatus.SUCCESS, key, properties.name, properties.apiLevel,
                if (problems.isEmpty()) "ROM解包完成：" + key else "ROM自动修复完成：" + key, started, events)
            bump()
            return existing?.let { rowById(it.rowId) }
                ?: properties.toRow(source, RomState.Ready, extractedBytes, archiveBytes = archive.length(), hasArchive = true)
        } catch (cancel: RomCancelled) {
            record(operationKind, RecordStatus.CANCELLED, key, properties.name, properties.apiLevel,
                "ROM操作已取消：" + key, started, events + "用户取消解包")
            bump()
            throw cancel
        } catch (failure: Throwable) {
            val reason = failure.message ?: failure.javaClass.simpleName
            if (tracked) store.updateRomState(key, RomState.Failed.name, reason)
            audit("rom_failed", mapOf("romKey" to key, "reason" to reason))
            record(operationKind, RecordStatus.FAILED, key, properties.name, properties.apiLevel,
                "ROM解包或修复失败：" + reason, started, events + "原包解包或清单校验失败", failure)
            bump()
            throw failure
        }
    }

    /** 解包的唯一入口：读包的条目清单（`:app`，需要 JSON）+ 流水线（`:vm-rom`）。 */
    private fun extract(
        archive: File,
        properties: RomProperties,
        progress: (RomProgress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): File = ZipFile(archive).use { zip ->
        val entries = RomPackage.entries(zip, properties)
        extractor.extract(archive, properties, entries, progress, isCancelled)
    }

    /**
     * 扫一遍"解包目录里的 payload 还在不在"：只比**存在性 + 字节数**，不算哈希。
     *
     * 为什么不算哈希：这里只用来判断"要不要重解"，1 GB 全量哈希会把每次开机拖慢几十秒；
     * 真正的内容正确性由解包器的 L2（逐条 bytes+sha256）在重解时保证。
     * 返回空列表 = 完整；最多列 64 条，够判断即可。
     */
    fun payloadProblems(row: RomRow): List<String> {
        // 比的是**这一份内容**的目录：同版本两份内容各有一棵 payload 树，比错树就会得出
        // "完整/损坏"的相反结论（进而自动重解错那一份）。
        val dir = dirOf(row)
        if (!File(dir, RomLayout.PROPERTIES).isFile) return listOf(RomLayout.PROPERTIES)
        val inputs = File(dir, RomLayout.INPUTS)
        if (!inputs.isFile) return listOf(RomLayout.INPUTS)
        val problems = mutableListOf<String>()
        runCatching {
            val json = JSONObject(inputs.readText(Charsets.UTF_8))
            val data = json.optJSONArray("data") ?: return listOf(RomLayout.INPUTS)
            for (index in 0 until data.length()) {
                val item = data.getJSONObject(index)
                val rel = item.optString("name")
                if (rel.isEmpty()) continue
                val file = File(dir, rel)
                if (!file.isFile || file.length() != item.optLong("bytes")) {
                    problems += rel
                    if (problems.size >= 64) break
                }
            }
        }.onFailure { problems += RomLayout.INPUTS }
        return problems
    }

    /**
     * 「现在能不能直接用这个 ROM」的唯一闸门（**启动链与创建链共用**，`docs/rom-package/06` §1.1）。
     *
     * 以前创建虚拟机自己有一段"先 ensureRomReady 再 create"的静默解包：内置包没解包时，
     * 页面在没有任何提示的情况下拷 700 MB 包、解 1.6 GB payload，用户看到的就是「点了保存没反应、
     * 一直停在新建页」。现在两件事分开：
     *  - 判据在 [RomGate]（`:vm-rom`，有单测）；
     *  - 修复只有一条链 [ensureUsable]（带进度与审计）。
     *
     * 只读元数据与清单条目（`stat`，毫秒级），可以放在创建前的 IO 上。
     */
    fun gate(ref: RomRef): RomGate = rowFor(ref)?.let { gate(it) } ?: RomGate.Missing

    /** 同上，入口已经拿到这一行时用这个重载。 */
    fun gate(row: RomRow): RomGate = RomGate.decide(
        row,
        // 旧格式跟着 APK 的 assets 走，没有派生层可查（也查不到）。
        if (row.source == RomSource.Legacy) emptyList() else payloadProblems(row),
    )

    /**
     * 手动检查专用的扫描（**不在启动路径上**）：返回「清单条目总数 → 缺失的相对路径」。
     *
     * 比 [payloadProblems] 多看一处：`files[].libPath`（也就是 `payload/lib` 下的原生库）。
     * 启动路径仍然只用 `data[]` 那一次扫描，行为与改动前逐字一致。
     */
    private fun checkScan(row: RomRow): Pair<Int, List<String>> {
        val dir = dirOf(row)
        if (!File(dir, RomLayout.PROPERTIES).isFile) return 0 to listOf(RomLayout.PROPERTIES)
        val inputs = File(dir, RomLayout.INPUTS)
        if (!inputs.isFile) return 0 to listOf(RomLayout.INPUTS)
        var total = 0
        val problems = mutableListOf<String>()
        runCatching {
            val json = JSONObject(inputs.readText(Charsets.UTF_8))
            val data = json.optJSONArray("data") ?: return 0 to listOf(RomLayout.INPUTS)
            for (index in 0 until data.length()) {
                val item = data.getJSONObject(index)
                val rel = item.optString("name")
                if (rel.isEmpty()) continue
                total++
                val file = File(dir, rel)
                if (!file.isFile || file.length() != item.optLong("bytes")) {
                    if (problems.size < 64) problems += rel
                }
            }
            val files = json.optJSONArray("files")
            for (index in 0 until (files?.length() ?: 0)) {
                val item = files!!.getJSONObject(index)
                val rel = item.optString("libPath")
                if (rel.isEmpty() || !rel.startsWith(RomLayout.PAYLOAD_DIR)) continue
                total++
                val file = File(dir, rel)
                if (!file.isFile || file.length() != item.optLong("bytes")) {
                    if (problems.size < 64) problems += rel
                }
            }
        }.onFailure { problems += RomLayout.INPUTS }
        return total to problems
    }

    /**
     * 手动「检查解包内容」（docs/rom-package/12/03 §3.1）。**纯读**：只读清单与 stat，
     * 不动文件、不改 DB（只写一条审计与一条索引记录）。
     */
    fun checkRom(rowId: String): RomCheckReport {
        val started = System.currentTimeMillis()
        var row: RomRow? = null
        try {
            row = rowById(rowId) ?: throw RomException("没有这个 ROM：" + rowId)
            val (total, problems) = checkScan(row)
            val canRepair = hasArchive(row.key, row.payloadSha256)
            val report = RomCheckReport(row.key, total, problems, canRepair)
            audit("rom_checked", mapOf("romKey" to row.key, "healthy" to report.healthy, "problems" to problems.size, "canRepair" to canRepair))
            val detail = if (report.healthy) {
                RomTexts.checkHealthy(report) + "（" + RomTexts.titleWithVersion(row) + "）"
            } else {
                RomTexts.checkProblems(report) + (if (canRepair) "（可以重新解包修复）" else "（原包不在了）")
            }
            record(
                RecordKind.ROM_CHECK, RecordStatus.SUCCESS, row.key, row.displayName, row.apiLevel,
                detail, started,
                listOf("读取解包清单并检查 " + total + " 个条目", "发现缺失或尺寸不符 " + problems.size + " 项", "原包可恢复=" + canRepair),
            )
            return report
        } catch (failure: Exception) {
            record(
                RecordKind.ROM_CHECK, RecordStatus.FAILED, row?.key ?: rowId, row?.displayName, row?.apiLevel ?: 0,
                "ROM检查失败：" + (failure.message ?: failure.javaClass.simpleName), started,
                listOf("ROM读取、清单解析或文件检查失败"), failure,
            )
            throw failure
        }
    }

    /**
     * 这一份 ROM 还有没有"可重解"的来源（`archives/<key>@<sha8>.zip`、旧布局 zip、或 APK 内置包）。
     *
     * 只做判断、不拷贝：给"ROM 解包目录被删了，但包还在"这种可自愈的情况用。
     * [payloadSha256] 为空时按 romKey 判（旧实例 / 旧格式语义）。
     */
    fun hasArchive(key: String, payloadSha256: String = ""): Boolean {
        if (key.isEmpty()) return false
        if (layout.archiveFor(key, payloadSha256).isFile) return true
        if (payloadSha256.isEmpty() && layout.archive(key).isFile) return true
        return builtinProperties().any {
            it.properties.key == key &&
                (payloadSha256.isEmpty() || it.properties.payloadSha256 == payloadSha256)
        }
    }

    /**
     * 找一个 ROM 的包来源（L1）：内容寻址 zip → **同内容的**旧布局 zip → APK 的 assets（内置包）。
     *
     * 旧布局 zip 只有在自述指纹对得上时才用：把另一份内容的包当成这一份解出来，
     * 解包器会用 payload 哈希把它拒掉（那是它该做的），但报错信息会变得没法解释。
     */
    private fun resolveArchive(key: String, payloadSha256: String = ""): File? {
        val tagged = layout.archiveFor(key, payloadSha256)
        if (tagged.isFile) return tagged
        val legacy = layout.archive(key)
        if (legacy.isFile) {
            val legacySha = runCatching { RomPackage.read(legacy).payloadSha256 }.getOrDefault("")
            if (payloadSha256.isEmpty() || legacySha.isEmpty() || legacySha == payloadSha256) return legacy
        }
        val builtin = builtinProperties().firstOrNull {
            it.properties.key == key &&
                (payloadSha256.isEmpty() || it.properties.payloadSha256 == payloadSha256)
        } ?: return null
        if (builtin.properties.placeholder) {
            throw RomException(
                "安装包里只有 " + key + " 的占位自述，没有 payload。请导入完整的 ROM 包后再使用。",
            )
        }
        layout.archives().mkdirs()
        // 内置包落盘也用内容寻址名：同一个 romKey 的两份内置包（理论上不会，但第三方会）不会互相覆盖。
        val target = layout.archiveFor(key, builtin.properties.payloadSha256)
        if (!target.isFile) {
            runCatching {
                assets.open(RomStorage.ASSET_DIR + "/" + builtin.fileName).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }.onFailure { return null }
        }
        return target.takeIf { it.isFile }
    }

    // ------------------------------------------------------------------ 删除

    internal fun openBackupArchive(ref: RomRef): java.io.InputStream = exclusive {
        require(ref.payloadSha256.isNotEmpty()) { "当前虚拟机未绑定具备内容摘要的 ROM" }
        val row = rowFor(ref) ?: throw RomException("找不到当前选择的 ROM：" + ref.key)
        if (row.source == RomSource.Builtin) {
            val builtin = builtinProperties().firstOrNull {
                it.properties.key == row.key && it.properties.payloadSha256 == row.payloadSha256 && !it.properties.placeholder
            } ?: throw RomException("当前内置 ROM 没有完整原包：" + row.key)
            return@exclusive assets.open(RomStorage.ASSET_DIR + "/" + builtin.fileName)
        }
        val archive = sequenceOf(layout.archiveFor(row.key, row.payloadSha256), layout.archive(row.key))
            .filter { it.isFile }
            .firstOrNull {
                val metadata = RomPackage.read(it)
                !metadata.placeholder && metadata.key == row.key && metadata.payloadSha256 == row.payloadSha256
            } ?: throw RomException("当前 ROM 原包不可用：" + row.key)
        archive.inputStream()
    }

    /** 删除本地 ROM（包 + 解包目录 + DB 行）。内置包只能清缓存，调这里会抛错。 */
    fun delete(rowId: String): RomRow = exclusive {
        deleteLocked(rowId)
    }

    private fun deleteLocked(rowId: String): RomRow {
        val started = System.currentTimeMillis()
        val row = rowById(rowId) ?: run {
            record(RecordKind.ROM_DELETE, RecordStatus.FAILED, rowId, null, 0,
                "删除失败：没有这个 ROM（" + rowId + "）", started)
            throw RomException("没有这个 ROM：" + rowId)
        }
        val key = row.key
        if (!row.canDelete) {
            record(RecordKind.ROM_DELETE, RecordStatus.FAILED, key, row.displayName, row.apiLevel,
                "删除失败：这个 ROM 不能删除（" + RomTexts.titleWithVersion(row) + "）", started)
            throw RomException("这个 ROM 不能删除：" + RomTexts.titleWithVersion(row))
        }
        var freed = 0L
        // 只删**这一行**的解包目录（内容寻址目录，或旧布局里同内容的那一个）。
        // 同版本的另一份内容必须原样留着——它不是这一行的派生物。
        val dir = extractedDirOf(row.key, row.payloadSha256, dirsOnDisk())?.first
        if (dir != null && dir.isDirectory) {
            freed += layout.measuredBytes(dir)
            dir.deleteRecursively()
        }
        // 原包同理：先删这一份的 zip；旧布局 zip 只有在**内容相同**时才删（那是同一份，不是别人的）。
        val taggedArchive = layout.archiveFor(key, row.payloadSha256)
        if (taggedArchive.isFile) {
            freed += taggedArchive.length()
            taggedArchive.delete()
        }
        val legacyArchive = layout.archive(key)
        if (legacyArchive.isFile && legacyArchive.absolutePath != taggedArchive.absolutePath) {
            val sameContent = runCatching {
                RomPackage.read(legacyArchive).payloadSha256 == row.payloadSha256
            }.getOrDefault(false)
            if (sameContent) {
                freed += legacyArchive.length()
                legacyArchive.delete()
            }
        }
        // rom_install 的主键仍是 rom_key（一张表放不下同 key 两行），见 docs/rom-package/14/04 R3 的债务说明：
        // 删掉这一行时元数据行会跟着消失，同 key 的另一份会由下一次磁盘扫描补回来（状态由目录推断）。
        store.deleteRomRow(key)
        audit("rom_deleted", mapOf(
            "romKey" to key,
            "source" to row.source.name.lowercase(),
            "freed_bytes" to freed,
            "affectedVms" to row.usedByVms,
        ))
        record(RecordKind.ROM_DELETE, RecordStatus.SUCCESS, key, row.displayName, row.apiLevel,
            "已删除 " + key + "（释放 " + RomTexts.bytes(freed) + "，" +
                row.usedByVms + " 台虚拟机受影响）", started,
            listOf("删除解包目录及匹配的ZIP原包，freed_bytes=" + freed, "rom_install数据库登记删除并扫描刷新"))
        bump()
        return row.copy(state = RomState.Missing, extractedBytes = 0L)
    }

    /** 清理内置 ROM 的已解包缓存（包本身在 APK 里，不受影响）。 */
    fun clearCache(rowId: String): RomRow = exclusive {
        clearCacheLocked(rowId)
    }

    private fun clearCacheLocked(rowId: String): RomRow {
        val started = System.currentTimeMillis()
        val row = rowById(rowId) ?: run {
            record(RecordKind.ROM_CLEAR_CACHE, RecordStatus.FAILED, rowId, null, 0,
                "清理缓存失败：没有这个 ROM（" + rowId + "）", started)
            throw RomException("没有这个 ROM：" + rowId)
        }
        val key = row.key
        if (!row.canClearCache) {
            record(RecordKind.ROM_CLEAR_CACHE, RecordStatus.FAILED, key, row.displayName, row.apiLevel,
                "清理缓存失败：" + RomTexts.titleWithVersion(row) + " 没有可清理的解包缓存", started)
            throw RomException("这个 ROM 没有可清理的缓存：" + RomTexts.titleWithVersion(row))
        }
        val targetDir = extractedDirOf(row.key, row.payloadSha256, dirsOnDisk())?.first
            ?: layout.dirFor(row.key, row.payloadSha256)
        val freed = layout.measuredBytes(targetDir)
        targetDir.deleteRecursively()
        store.updateRomState(key, RomState.Pending.name, null, extractedBytes = 0L)
        audit("rom_deleted", mapOf(
            "romKey" to key,
            "source" to "builtin-cache",
            "freed_bytes" to freed,
            "affectedVms" to row.usedByVms,
        ))
        record(RecordKind.ROM_CLEAR_CACHE, RecordStatus.SUCCESS, key, row.displayName, row.apiLevel,
            "已清理 " + key + " 的解包缓存（释放 " + RomTexts.bytes(freed) + "）", started,
            listOf("删除解包目录，freed_bytes=" + freed, "rom_install状态更新为pending并清空extracted_bytes"))
        bump()
        return row.copy(state = RomState.Pending, extractedBytes = 0L)
    }

    // --------------------------------------------------------------- 解析

    /**
     * 三段解析（`docs/rom-package/07` §2.1）：精确 romKey → 同 id 的最大版本 → 交给调用方回落旧格式。
     *
     * **只读 DB 登记 + 目录是否存在**，不量目录大小、不解包：这个方法会被
     * `VmService` 在每台实例上一次、以及开机窗口的启动路径上调用，必须便宜。
     * 结果按 [revision] 缓存，ROM 一变就失效。
     */
    fun resolve(romId: String?, payloadSha256: String = ""): RomSpec? {
        if (romId.isNullOrEmpty()) return null
        // 只看「已就绪」的行（目录在 + 这一份内容的指纹对得上）。
        val ready = rows().filter { it.isReady && it.source != RomSource.Legacy }
        if (ready.isEmpty()) return null
        // 1) 精确身份：romKey + 内容指纹。同版本两份内容时**只有这一档**是安全的，
        //    否则会把 A 那份的 romRoot 交给正在用 B 的实例（切换看起来成功、实际没换）。
        if (payloadSha256.isNotEmpty()) {
            return ready.firstOrNull { it.key == romId && it.payloadSha256 == payloadSha256 }
                ?.let(::specOf)
        }
        // 2) 只记了 romKey（旧实例）：取这一行的第一份（同 key 多份时优先没指纹的那条）。
        val sameKey = ready.filter { it.key == romId }
        sameKey.firstOrNull { it.payloadSha256.isEmpty() }?.let { return specOf(it) }
        sameKey.firstOrNull()?.let { return specOf(it) }
        // 3) 只记了 id（`android15` 这种旧值）：同 id 里版本号最大的一份。
        val sameId = ready.filter { RomKeys.idOf(it.key) == romId }
        if (sameId.isEmpty()) return null
        return sameId.maxWithOrNull { left, right ->
            RomKeys.compareVersions(left.version, right.version)
        }?.let { specOf(it) }
    }

    /** 把一行 ROM 变成运行时认识的 [RomSpec]（装好的包带 romRoot，旧格式走 assets）。 */
    fun specOf(row: RomRow): RomSpec = RomSpec(
        id = row.key,
        name = row.displayName,
        androidVersion = row.androidVersion,
        apiLevel = row.apiLevel,
        assetPrefix = RomLayout.PAYLOAD_DIR,
        runtimeManifest = RomLayout.INPUTS,
        displayManifest = DISPLAY_MANIFEST,
        description = row.description,
        // romRoot 必须是**这一份内容**真正解包在的目录（可能是旧布局 `<key>`，
        // 也可能是内容寻址的 `<key>@<sha8>`）：按 key 拼路径会把运行时指向另一份。
        romRoot = if (row.source == RomSource.Legacy) null else dirOf(row),
        libMode = row.libMode,
    )

    /**
     * 所有「可用」的 ROM，给选择页用。
     *
     * 顺序：旧格式（跟着 APK 走，永远可用）在前，装好的 ROM 包在后；
     * **同一 id 上装好的包优先**——旧条目从选择页里消失（`docs/rom-package/02` §8）。
     */
    fun usableSpecs(): List<RomSpec> = specsOf(rows())

    /**
     * 从一次扫描结果算出「可用 ROM 列表」：旧格式在前、装好的包在后，
     * 同一 id 上装好的包优先（旧条目从选择页里消失）。调用方已经扫过一次时用这个重载，
     * 免得再扫一遍（`docs/ui-rework/05` §1：不要重复 IO）。
     */
    fun specsOf(rows: List<RomRow>): List<RomSpec> {
        val installed = rows.filter { it.isReady && it.source != RomSource.Legacy }.map { specOf(it) }
        val legacy = legacySpecs().filter { spec -> installed.none { RomKeys.idOf(it.id) == spec.id } }
        return legacy + installed
    }

    /**
     * 版本管理页与启动闸门用的版本清单：**每个 id 一条 series**，只含「可作为版本目标」的行。
     *
     * 旧格式（跟着安装包走、没有版本号）也算一条：它代表「这台机器上这一代系统的当前形态」，
     * 否则老实例在版本管理页里会看到一张空列表（`docs/rom-package/14` §5）。
     */
    fun versionEntries(): Map<String, List<RomVersionEntry>> =
        rows().groupBy { it.id }.mapValues { (_, rows) ->
            rows.map { row ->
                RomVersionEntry(
                    key = row.key,
                    id = row.id,
                    version = row.version,
                    displayName = row.displayName,
                    source = row.source,
                    apiLevel = row.apiLevel,
                    available = row.isReady,
                    payloadSha256 = row.payloadSha256,
                    placeholder = row.placeholder,
                    // 包还在（内置包里有一份 / 本地 archives 里有原包）→ 解包只是还没做。
                    // 只读扫描时算好的字段：这里原来对每一行调 hasArchive(key)，等于在循环里读 assets。
                    extractable = row.isReady || row.hasArchive,
                )
            }
        }

    // --------------------------------------------------------------- 内部

    private fun copyUri(uri: Uri, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = app.contentResolver.openInputStream(uri)
            ?: throw RomException("读不到这个文件，请重新选择")
        input.use { source ->
            target.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val count = source.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        return digest.digest().toHex()
    }

    private fun countFiles(dir: File): Int = dir.walkTopDown().count { it.isFile }

    private fun bump() {
        _revision.value = _revision.value + 1
        // 只清扫描缓存：`resolve()` 现在直接读 [rows]（按 revision 缓存），
        // 不再维护第二份 "ready specs" 缓存——两份缓存就是两个真相（P5 之后尤其明显）。
        rowsCache = null
    }

    private fun audit(kind: String, fields: Map<String, Any>) {
        AuditBootstrap.emit(app, kind, fields)
    }

    private fun record(
        kind: RecordKind,
        status: RecordStatus,
        romKey: String?,
        romName: String?,
        apiLevel: Int,
        detail: String,
        startedAt: Long,
        events: List<String> = emptyList(),
        failure: Throwable? = null,
    ) {
        RecordCollector.recordMeta(
            context = app,
            vmId = "rom",
            vmName = "ROM 管理",
            romId = romKey ?: "-",
            romName = romName,
            apiLevel = apiLevel,
            kind = kind,
            status = status,
            detail = detail,
            startedAt = startedAt,
            operationEvents = events,
            failure = failure,
        )
    }
}

/** [RomProperties] → 页面用的 [RomRow]。 */
internal fun RomProperties.toRow(
    source: RomSource,
    state: RomState,
    extractedBytes: Long = 0L,
    archiveBytes: Long = 0L,
    failure: String? = null,
    installedAt: Long = 0L,
    hasArchive: Boolean = false,
    extractedSha256: String = "",
): RomRow = RomRow(
    key = key,
    id = id,
    version = romVersion,
    displayName = name,
    description = description,
    androidVersion = androidVersion,
    apiLevel = apiLevel,
    source = source,
    state = state,
    failure = failure,
    payloadBytes = payloadBytes,
    archiveBytes = archiveBytes,
    extractedBytes = extractedBytes,
    libMode = libMode,
    installedAt = installedAt,
    payloadSha256 = payloadSha256,
    placeholder = placeholder,
    hasArchive = hasArchive,
    extractedSha256 = extractedSha256,
)

/** [RomProperties] → DB 行。 */
internal fun RomProperties.toRecord(
    source: RomSource,
    state: RomState,
    extractedBytes: Long = 0L,
    zipSha256: String? = null,
    archiveBytes: Long = 0L,
    installedAt: Long = 0L,
): RomInstallRow = RomInstallRow(
    key = key,
    id = id,
    version = romVersion,
    source = source.name,
    apiLevel = apiLevel,
    displayName = name,
    description = description,
    androidVersion = androidVersion,
    zipSha256 = zipSha256,
    payloadSha256 = payloadSha256,
    payloadBytes = payloadBytes,
    archiveBytes = archiveBytes,
    extractedBytes = extractedBytes,
    libMode = libMode,
    state = state.name,
    installedAt = installedAt,
)