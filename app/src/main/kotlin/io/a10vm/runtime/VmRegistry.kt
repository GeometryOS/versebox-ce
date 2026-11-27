package io.a10vm.runtime

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kanadex.geometryos.backup.BackupVmSettings
import org.json.JSONArray

/**
 * 虚拟机注册表的 SQLite 实现。
 *
 * 为什么不是 SharedPreferences JSON：旧实现一次写整张表，没有事务、没有唯一约束、
 * 没有字段级更新，改一个名字要把所有字段搬一遍；任何一次异常都可能把整张注册表写坏。
 * 方案与验收见 docs/ui-rework/05-数据层/01-重命名与SQLite方案.md。
 *
 * 不变量：
 * 1. [VmDevice.id] 不可变，且等于 `files/devices/<id>` 的目录名——改名**不移动目录**；
 * 2. 所有写入串行化并包在事务里，写完在同一事务内读回自证；
 * 3. 每次成功写入 [revision] +1，供 [VmService] 之类已经在跑的对象刷新内存里的名字。
 */
class VmRegistry private constructor(context: Context) {
    companion object {
        private const val TAG = "A10VM_REGISTRY"
        private const val DB_NAME = "versebox.db"

        /**
         * 数据库版本。
         *
         * v1：初始版本（id/name/rom/三处路径/legacy）。
         * v2：`display_mode`——每台虚拟机自己的显示模式（分辨率/DPI）。
         * v3：`rom_payload` / `rom_source`——这台虚拟机用的是**哪一份** ROM
         *     （内容指纹 + 来源）。默认空串 = 老实例，按旧的宽松语义解析，不需要回填
         *     （`docs/rom-package/14-版本管理/04-身份与分类红线.md` R3）。
         *
         * **只许 ALTER TABLE 加列，禁止 DROP / 重建**：虚拟机的数据目录不可再生
         * （`docs/display-settings/05-数据库与统一连接.md` §2）。
         */
        private const val DB_VERSION = 3
        private const val TABLE = "vm_instance"
        private const val META = "schema_meta"
        private const val PREF_MIGRATED = "migrated_from_prefs"
        private const val PREFS = "vm-devices"
        private const val PREFS_KEY = "devices"
        private const val DIR_DEVICES = "devices"
        private const val DIR_REGISTRY = "registry"
        private const val LEGACY_A10 = "guest-runtime"
        private const val LEGACY_A9 = "a9-state"
        const val MAX_NAME_LENGTH = 24

        @Volatile private var instance: VmRegistry? = null

        fun get(context: Context): VmRegistry =
            instance ?: synchronized(this) {
                instance ?: VmRegistry(context.applicationContext).also { instance = it }
            }

        /**
         * 名字归一化：展示值与比较值一起返回。
         *
         * - 去掉 0x00-0x1F 与 0x7F 控制字符；
         * - 首尾空白去掉，内部连续空白折叠成一个空格；
         * - 比较值再做 NFC 归一化 + lowercase，这样全角/大小写/组合字符都算同一个名字。
         *
         * @return null 表示这个名字不可用（空、全是空白、超长）。
         */
        fun normalize(raw: String): Pair<String, String>? {
            val cleaned = buildString {
                for (ch in raw) if (ch.code > 0x1F && ch.code != 0x7F) append(ch)
            }
            val display = cleaned.trim().replace(Regex("[\\s\\u00A0\\u3000]+"), " ")
            if (display.isEmpty()) return null
            if (display.codePointCount(0, display.length) > MAX_NAME_LENGTH) return null
            val key = Normalizer.normalize(display, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            return display to key
        }
    }

    private val context = context.applicationContext
    private val files get() = context.filesDir
    private val writeLock = ReentrantLock()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val _revision = MutableStateFlow(0L)

    /** 每次成功写入 +1；观察者据此刷新内存里的名字（见方案 §5）。 */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val helper = object : SQLiteOpenHelper(context, dbFile().path, null, DB_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE (
                    id          TEXT    PRIMARY KEY NOT NULL,
                    name        TEXT    NOT NULL,
                    name_key    TEXT    NOT NULL,
                    rom_id      TEXT    NOT NULL,
                    root        TEXT    NOT NULL DEFAULT '',
                    runtime_dir TEXT    NOT NULL,
                    window_dir  TEXT    NOT NULL,
                    legacy      INTEGER NOT NULL DEFAULT 0,
                    display_mode TEXT   NOT NULL DEFAULT '',
                    rom_payload TEXT    NOT NULL DEFAULT '',
                    rom_source  TEXT    NOT NULL DEFAULT '',
                    created_at  INTEGER NOT NULL,
                    updated_at  INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_vm_instance_name_key ON $TABLE (name_key)")
            db.execSQL("CREATE TABLE IF NOT EXISTS $META (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 是初始版本。以后加列走 ALTER TABLE，禁止 DROP（数据不可再生）。
            Log.i(TAG, "数据库升级：$oldVersion → $newVersion")
            if (oldVersion < 2) {
                // v2：显示模式。默认空串 = 真机分辨率（竖屏），所以老实例不需要回填。
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN display_mode TEXT NOT NULL DEFAULT ''")
            }
            if (oldVersion < 3) {
                // v3：ROM 的内容指纹与来源。默认空串 = 老实例「只记了 rom_id」，
                // 解析时按旧语义（同 id 取最大可用版本）走，所以不需要回填、也不需要迁移数据。
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN rom_payload TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN rom_source TEXT NOT NULL DEFAULT ''")
            }
        }
    }

    init {
        // WAL：写入不阻塞读取，崩溃后可自动回放，不会留下半张表。
        // 必须在第一次 getWritableDatabase() 之前设置，所以在 init 里做。
        helper.setWriteAheadLoggingEnabled(true)
    }

    private fun dbFile(): File = File(files, DIR_REGISTRY).apply { mkdirs() }.resolve(DB_NAME)
    private fun db(): SQLiteDatabase = helper.writableDatabase

    // ------------------------------------------------------------------ 读

    fun list(): List<VmDevice> = writeLock.withLock {
        ensureMigratedLocked()
        val items = mutableListOf<VmDevice>()
        db().query(TABLE, null, null, null, null, null, "created_at ASC").use { cursor ->
            while (cursor.moveToNext()) items += cursor.toDevice()
        }
        // 历史实例只有在数据仍然存在时才算数（与旧实现一致）。
        val kept = items.filter { !it.legacy || File(files, it.runtimeDir).let { dir -> File(dir, "rootfs").isDirectory } }
        if (kept.size != items.size) {
            val drop = items.filter { it !in kept }.map { it.id }
            Log.i(TAG, "清理已失效的历史实例：$drop")
            db().transaction { for (id in drop) db().delete(TABLE, "id=?", arrayOf(id)) }
        }
        kept
    }

    fun find(id: String?): VmDevice? = if (id.isNullOrEmpty()) null else list().firstOrNull { it.id == id }

    // ------------------------------------------------------------------ 写

    /**
     * 新建一台虚拟机。
     *
     * [romPayload] / [romSource] 是「用的是哪一份 ROM」的身份（DB v3）。创建流程已经知道这两件事
     * （用户选的就是某一行），所以**创建时就写全**：只写 romKey 的话，同版本两份内容从第一刻起
     * 就分不开，后面每次启动都要靠"猜"（`docs/rom-package/14-版本管理/04` R3）。
     */
    fun create(romId: String, romPayload: String = "", romSource: String = ""): VmDevice = writeLock.withLock {
        ensureMigratedLocked()
        val items = list()
        val device = allocateDevice(items, nextName(items), romId, romPayload, romSource)
        ensureDirectories(device)
        insert(device)
        Log.i(TAG, "创建 ${device.name}（${device.id}，ROM $romId）→ ${device.root}")
        bumpRevision()
        device
    }

    internal fun commitRestored(
        settings: BackupVmSettings,
        stagingRoot: File,
    ): VmDevice = writeLock.withLock {
        ensureMigratedLocked()
        val (name, nameKey) = requireNotNull(normalize(settings.name)) { "虚拟机名称无效" }
        val items = list()
        require(items.none { normalize(it.name)?.second == nameKey }) { "已有同名虚拟机，请更改名称" }
        val rom = settings.rom
        val device = allocateDevice(items, name, rom.key, rom.payloadSha256, rom.source)
            .copy(displayMode = settings.displayMode)
        val target = File(files, device.root).toPath()
        val source = stagingRoot.toPath()
        for (directory in listOf(source, source.resolve("vm"), source.resolve("native-window"))) {
            require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "备份实例目录不完整" }
        }
        Files.createDirectories(target.parent)
        // The destination is allocated under the registry lock and is published only after readback.
        Files.move(source, target)
        try {
            val database = db()
            database.transaction {
                insert(device, nameKey)
                database.query(TABLE, null, "id=?", arrayOf(device.id), null, null, null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.toDevice() == device) { "恢复设置读回不一致" }
                }
            }
        } catch (failure: Exception) {
            try { Files.move(target, source) } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
        bumpRevision()
        device
    }

    private fun allocateDevice(
        items: List<VmDevice>, name: String, romId: String, romPayload: String, romSource: String,
    ): VmDevice {
        var stamp = System.currentTimeMillis()
        while (items.any { it.id == stamp.toString() } ||
            Files.exists(File(files, "$DIR_DEVICES/$stamp").toPath(), LinkOption.NOFOLLOW_LINKS)
        ) stamp++
        val id = stamp.toString()
        return VmDevice(
            id = id,
            name = name,
            romId = romId,
            root = "$DIR_DEVICES/$id",
            runtimeDir = "$DIR_DEVICES/$id/vm",
            windowDir = "$DIR_DEVICES/$id/native-window",
            romPayload = romPayload,
            romSource = romSource,
        )
    }

    fun delete(id: String): VmDevice? = writeLock.withLock {
        ensureMigratedLocked()
        val device = find(id) ?: return@withLock null
        val rows = db().delete(TABLE, "id=?", arrayOf(id))
        Log.i(TAG, "移除 ${device.name}（${device.id}）rows=$rows")
        if (rows > 0) bumpRevision()
        device
    }

    /**
     * 重命名。流程见 docs/ui-rework/05-数据层/01-重命名与SQLite方案.md §3.1：
     * 校验 → BEGIN → UPDATE → 读回自证 → COMMIT → revision+1。
     */
    fun rename(id: String, rawName: String): RenameResult = writeLock.withLock {
        ensureMigratedLocked()
        val (display, key) = normalize(rawName)
            ?: return@withLock if (rawName.isBlank()) RenameResult.EmptyName else RenameResult.TooLong
        val database = db()
        database.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("name", display)
                put("name_key", key)
                put("updated_at", System.currentTimeMillis())
            }
            val rows = database.update(TABLE, values, "id=?", arrayOf(id))
            if (rows == 0) {
                database.endTransaction()
                return@withLock RenameResult.NotFound
            }
            // 读回自证：只有提交前的最后一眼看到的是新名字，才算写成功。
            database.query(TABLE, arrayOf("name", "name_key"), "id=?", arrayOf(id), null, null, null)
                .use { cursor ->
                    if (!cursor.moveToFirst()) {
                        database.endTransaction()
                        return@withLock RenameResult.VerifyFailed
                    }
                    val stored = cursor.getString(0)
                    val storedKey = cursor.getString(1)
                    if (stored != display || storedKey != key) {
                        Log.e(TAG, "VMRENAME 读回不一致 id=$id want=$display got=$stored")
                        database.endTransaction()
                        return@withLock RenameResult.VerifyFailed
                    }
                }
            database.setTransactionSuccessful()
            database.endTransaction()
            val device = find(id) ?: return@withLock RenameResult.VerifyFailed
            Log.i(TAG, "VMRENAME id=$id rows=$rows name=$display")
            bumpRevision()
            RenameResult.Renamed(device)
        } catch (conflict: SQLiteConstraintException) {
            database.endTransaction()
            Log.i(TAG, "VMRENAME 重名被拒绝 id=$id name=$display")
            RenameResult.Duplicate
        } catch (failure: Exception) {
            runCatching { database.endTransaction() }
            Log.e(TAG, "VMRENAME 失败 id=$id：${failure.message}", failure)
            RenameResult.Failed(failure)
        }
    }

    /**
     * 写这台虚拟机的显示模式（分辨率 / DPI）。
     *
     * 与 [rename] 同一套纪律：BEGIN → UPDATE → **读回自证** → COMMIT → revision + 1。
     * 返回 false 只可能是三种情况：这台虚拟机已经不存在、写进去的值读回来不是它、
     * 或者写库本身抛异常。三种都必须让调用方提示失败，不许假装写成功
     * （`docs/ui-rework/04` §5 与 `docs/display-settings/05` §3）。
     *
     * **连接复用**：这里用的还是同一个 [helper]（进程内唯一的 SQLiteOpenHelper），
     * 既不开第二个数据库文件，也不自己建连接（`docs/display-settings/05` §2）。
     */
    fun setDisplayMode(id: String, mode: String): Boolean = writeLock.withLock {
        ensureMigratedLocked()
        val database = db()
        database.beginTransaction()
        try {
            val rows = database.update(
                TABLE,
                ContentValues().apply {
                    put("display_mode", mode)
                    put("updated_at", System.currentTimeMillis())
                },
                "id=?",
                arrayOf(id),
            )
            if (rows == 0) {
                database.endTransaction()
                Log.i(TAG, "VMDISPLAY 目标不存在 id=$id")
                return@withLock false
            }
            // 读回自证：只有提交前的最后一眼看到的是新值，才算写成功。
            database.query(TABLE, arrayOf("display_mode"), "id=?", arrayOf(id), null, null, null)
                .use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getString(0) != mode) {
                        Log.e(TAG, "VMDISPLAY 读回不一致 id=$id want=$mode")
                        database.endTransaction()
                        return@withLock false
                    }
                }
            database.setTransactionSuccessful()
            database.endTransaction()
            Log.i(TAG, "VMDISPLAY id=$id mode=$mode")
            bumpRevision()
            true
        } catch (failure: Exception) {
            runCatching { database.endTransaction() }
            Log.e(TAG, "VMDISPLAY 失败 id=$id：${failure.message}", failure)
            false
        }
    }

    /**
     * 写这台虚拟机使用的 ROM：**三列一起写**（`rom_id` + 内容指纹 + 来源）。
     *
     * 为什么要三列：`rom_id` 只说"哪个系统的哪一版"。同版本号的另一份内容（内置一份 + 本地导入一份）
     * 只写 `rom_id` 就会写成同一个字符串，于是「切换版本」变成空操作、列表里两行同时打勾
     * （`docs/rom-package/14-版本管理/04-身份与分类红线.md` R3，2026-11-12 真机原话）。
     *
     * 与 [setDisplayMode] 同一套纪律：BEGIN → UPDATE → **读回自证** → COMMIT → revision + 1。
     * 读回要把三列都核对：只核对 rom_id 时，指纹写坏了照样返回 true。
     * 返回 false 只可能是三种情况：这台虚拟机已经不存在、写进去的值读回来不是它、
     * 或者写库本身抛异常。三种都必须让调用方提示失败，不许假装写成功
     * （`docs/rom-package/14-版本管理/01` §4）。
     *
     * **只改这三列**：不动数据目录、不动 rootfs、不重建实例
     * （`docs/rom-package/07` §1：实例数据不可再生）。切换后第一次启动由
     * `RuntimeFiles.installData()` 的逐条比对把新 ROM 的差异补进实例目录。
     */
    fun setRomId(
        id: String,
        romKey: String,
        romPayload: String = "",
        romSource: String = "",
    ): Boolean = writeLock.withLock {
        ensureMigratedLocked()
        if (romKey.isBlank()) return@withLock false
        val database = db()
        database.beginTransaction()
        try {
            val rows = database.update(
                TABLE,
                ContentValues().apply {
                    put("rom_id", romKey)
                    put("rom_payload", romPayload)
                    put("rom_source", romSource)
                    put("updated_at", System.currentTimeMillis())
                },
                "id=?",
                arrayOf(id),
            )
            if (rows == 0) {
                database.endTransaction()
                Log.i(TAG, "VMROM 目标不存在 id=$id")
                return@withLock false
            }
            database.query(
                TABLE,
                arrayOf("rom_id", "rom_payload", "rom_source"),
                "id=?",
                arrayOf(id),
                null,
                null,
                null,
            ).use { cursor ->
                val ok = cursor.moveToFirst() &&
                    cursor.getString(0) == romKey &&
                    cursor.getString(1) == romPayload &&
                    cursor.getString(2) == romSource
                if (!ok) {
                    Log.e(TAG, "VMROM 读回不一致 id=$id want=$romKey/$romPayload/$romSource")
                    database.endTransaction()
                    return@withLock false
                }
            }
            database.setTransactionSuccessful()
            database.endTransaction()
            Log.i(TAG, "VMROM id=$id rom=$romKey")
            bumpRevision()
            true
        } catch (failure: Exception) {
            runCatching { database.endTransaction() }
            Log.e(TAG, "VMROM 失败 id=$id：${failure.message}", failure)
            false
        }
    }

    // ------------------------------------------------------------------ 路径

    fun rootOf(device: VmDevice): File =
        if (device.root.isEmpty()) files.canonicalFile else File(files, device.root).canonicalFile

    fun runtimeOf(device: VmDevice): File = File(files, device.runtimeDir).canonicalFile

    fun windowOf(device: VmDevice): File = File(files, device.windowDir).canonicalFile

    fun ensureDirectories(device: VmDevice) {
        rootOf(device).mkdirs()
        runtimeOf(device).mkdirs()
        windowOf(device).mkdirs()
    }

    // ------------------------------------------------------------------ 内部

    private fun bumpRevision() { _revision.value = _revision.value + 1 }

    private fun SQLiteDatabase.transaction(block: () -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun Cursor.toDevice(): VmDevice = VmDevice(
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        romId = getString(getColumnIndexOrThrow("rom_id")),
        root = getString(getColumnIndexOrThrow("root")),
        runtimeDir = getString(getColumnIndexOrThrow("runtime_dir")),
        windowDir = getString(getColumnIndexOrThrow("window_dir")),
        legacy = getInt(getColumnIndexOrThrow("legacy")) != 0,
        displayMode = getColumnIndex("display_mode").let { index ->
            if (index < 0) "" else getString(index)
        },
        // 列缺失只可能出现在"还没跑完 onUpgrade"的中间态：按旧实例（空串）读，不许崩。
        romPayload = getColumnIndex("rom_payload").let { index -> if (index < 0) "" else getString(index) },
        romSource = getColumnIndex("rom_source").let { index -> if (index < 0) "" else getString(index) },
    )

    private fun insert(device: VmDevice, nameKey: String? = null) {
        val now = System.currentTimeMillis()
        val (_, key) = normalize(device.name) ?: (device.name to device.name.lowercase(Locale.ROOT))
        val values = ContentValues().apply {
            put("id", device.id)
            put("name", device.name)
            put("name_key", nameKey ?: key)
            put("rom_id", device.romId)
            put("root", device.root)
            put("runtime_dir", device.runtimeDir)
            put("window_dir", device.windowDir)
            put("legacy", if (device.legacy) 1 else 0)
            put("display_mode", device.displayMode)
            put("rom_payload", device.romPayload)
            put("rom_source", device.romSource)
            put("created_at", now)
            put("updated_at", now)
        }
        db().insertOrThrow(TABLE, null, values)
    }

    private fun meta(key: String): String? =
        db().query(META, arrayOf("value"), "key=?", arrayOf(key), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun putMeta(key: String, value: String) {
        db().insertWithOnConflict(META, null, ContentValues().apply {
            put("key", key)
            put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun count(): Int =
        db().rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    /**
     * 列表/查找/写入之前统一走这里：把 SharedPreferences 里的旧注册表搬进 SQLite。
     * 幂等：成功一次就写 meta 标记；失败整事务回滚，下次再试。
     */
    private fun ensureMigratedLocked() {
        if (meta(PREF_MIGRATED) == "1") return
        if (count() > 0) {
            putMeta(PREF_MIGRATED, "1")
            return
        }
        val devices = readLegacyPrefs() ?: seedFromDisk()
        val database = db()
        database.beginTransaction()
        try {
            val used = mutableSetOf<String>()
            for (device in devices) {
                var name = device.name
                var attempt = 2
                while (normalize(name)?.second?.let { it in used } == true) {
                    name = "${device.name} ($attempt)"
                    attempt++
                }
                val key = normalize(name)?.second ?: name.lowercase(Locale.ROOT)
                used += key
                if (name != device.name) Log.i(TAG, "VMMIGRATE_RENAMED id=${device.id} ${device.name} → $name")
                insert(device.copy(name = name), key)
            }
            putMeta(PREF_MIGRATED, "1")
            database.setTransactionSuccessful()
            Log.i(TAG, "注册表迁移完成：${devices.size} 台")
        } catch (failure: Exception) {
            Log.e(TAG, "注册表迁移失败，下次重试：${failure.message}", failure)
        } finally {
            database.endTransaction()
        }
    }

    private fun readLegacyPrefs(): List<VmDevice>? {
        val raw = prefs.getString(PREFS_KEY, null) ?: return null
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val runtime = item.optString("runtimeDir")
                val window = item.optString("windowDir")
                if (runtime.isBlank() || window.isBlank()) return@mapNotNull null
                VmDevice(
                    id = id,
                    name = item.optString("name", id),
                    romId = item.optString("romId"),
                    root = item.optString("root"),
                    runtimeDir = runtime,
                    windowDir = window,
                    legacy = item.optBoolean("legacy"),
                )
            }
        } catch (failure: Exception) {
            Log.w(TAG, "旧注册表解析失败，按首次启动处理：${failure.message}")
            null
        }
    }

    /** 与旧实现完全一致的首次启动探测：只有数据目录真的存在才登记历史实例。 */
    private fun seedFromDisk(): List<VmDevice> {
        val devices = mutableListOf<VmDevice>()
        if (File(files, "$LEGACY_A10/rootfs").isDirectory) {
            devices += VmDevice(
                id = "legacy-a10",
                name = "虚拟机 1",
                romId = "android10",
                root = "",
                runtimeDir = LEGACY_A10,
                windowDir = "native-window",
                legacy = true,
            )
        }
        if (File(files, "$LEGACY_A9/guest-runtime-a9/rootfs").isDirectory) {
            devices += VmDevice(
                id = "legacy-a9",
                name = "虚拟机 ${devices.size + 1}",
                romId = "android9",
                root = LEGACY_A9,
                runtimeDir = "$LEGACY_A9/guest-runtime-a9",
                windowDir = "$LEGACY_A9/native-window-a9",
                legacy = true,
            )
        }
        if (devices.isNotEmpty()) Log.i(TAG, "沿用已有数据目录：${devices.joinToString { "${it.name}=${it.root.ifEmpty { "files" }}" }}")
        return devices
    }

    private fun nextName(items: List<VmDevice>): String {
        val used = items.map { normalize(it.name)?.second ?: it.name.lowercase(Locale.ROOT) }.toSet()
        var number = 1
        while (("虚拟机 $number").lowercase(Locale.ROOT) in used) number++
        return "虚拟机 $number"
    }
}
