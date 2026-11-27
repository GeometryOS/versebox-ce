package io.a10vm.records

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import io.a10vm.core.ApplicationSingleton
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 记录中心的数据入口。与 UI 完全解耦：UI 只拿到 [VmRecord] 列表，不读文件、不拼 SQL。 */
class RecordStore private constructor(context: Context) {
    companion object {
        private const val TAG = "A10VM_RECORDS"
        private const val DB_NAME = "records.db"

        /**
         * 2：新增 child_limit_state（子进程限制检测结论），见 [saveLimitState]。
         * 3：新增 rom_install（ROM 登记，见 docs/rom-package/06 §4）——**只加表，不 DROP**。
         */
        private const val DB_VERSION = 4
        private const val TABLE = "vm_record"
        private const val TABLE_LIMIT = "child_limit_state"
        private const val TABLE_ROM = "rom_install"
        private const val TABLE_ROM_SIZE = "rom_extract_size"
        private const val MAX_ROWS = 500

        private val shared = ApplicationSingleton { RecordStore(it) }

        fun get(context: Context): RecordStore = shared.of(context)
    }

    private val appContext: Context = context.applicationContext
    private val files: File get() = appContext.filesDir
    private val lock = ReentrantLock()
    private val _changes = MutableStateFlow(0L)

    /** 记录集合变化计数，UI 据此重新查询。 */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private val helper = object : SQLiteOpenHelper(appContext, dbFile().path, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    vm_id TEXT NOT NULL,
                    vm_name TEXT NOT NULL,
                    rom_id TEXT NOT NULL,
                    rom_name TEXT,
                    android_version TEXT,
                    api_level INTEGER NOT NULL DEFAULT 0,
                    kind TEXT NOT NULL,
                    status TEXT NOT NULL,
                    failure TEXT,
                    summary TEXT,
                    started_at INTEGER NOT NULL,
                    finished_at INTEGER,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    app_version TEXT NOT NULL DEFAULT '',
                    host_release TEXT NOT NULL DEFAULT '',
                    host_model TEXT NOT NULL DEFAULT '',
                    log_dir TEXT,
                    event_count INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_record_vm ON $TABLE (vm_id, started_at DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_record_kind ON $TABLE (kind, started_at DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_record_status ON $TABLE (status, started_at DESC)")
            createLimitTable(db)
            createRomTable(db)
            createRomSizeTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Log.i(TAG, "记录库升级：$oldVersion → $newVersion")
            // 只加表，不重建、不清空既有记录（数据不可再生）。两条都是 IF NOT EXISTS，可重复执行。
            createLimitTable(db)
            if (oldVersion < 3) createRomTable(db)
            if (oldVersion == 3) db.execSQL("ALTER TABLE $TABLE_ROM ADD COLUMN archive_bytes INTEGER NOT NULL DEFAULT 0")
            if (oldVersion < 4) createRomSizeTable(db)
        }

        /**
         * ROM 登记表（docs/rom-package/06 §4.2）。
         *
         * 只放「这个 ROM 已可用」这件事与它的状态机；ROM 的真实内容在
         * `files/roms/<romKey>/`，两者靠 rom_key 对上。
         */
        private fun createRomTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_ROM (
                    rom_key         TEXT PRIMARY KEY NOT NULL,
                    rom_id          TEXT NOT NULL,
                    rom_version     TEXT NOT NULL,
                    source          TEXT NOT NULL,
                    api_level       INTEGER NOT NULL,
                    display_name    TEXT NOT NULL,
                    description     TEXT NOT NULL DEFAULT '',
                    android_version TEXT NOT NULL DEFAULT '',
                    zip_sha256      TEXT,
                    payload_sha256  TEXT NOT NULL,
                    payload_bytes   INTEGER NOT NULL DEFAULT 0,
                    archive_bytes   INTEGER NOT NULL DEFAULT 0,
                    extracted_bytes INTEGER NOT NULL DEFAULT 0,
                    lib_mode        TEXT NOT NULL DEFAULT 'apk',
                    state           TEXT NOT NULL,
                    failure         TEXT,
                    installed_at    INTEGER NOT NULL,
                    last_used_at    INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_rom_id ON $TABLE_ROM (rom_id, installed_at DESC)")
        }

        private fun createRomSizeTable(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE_ROM_SIZE (payload_sha256 TEXT PRIMARY KEY NOT NULL, extracted_bytes INTEGER NOT NULL, measured_at INTEGER NOT NULL)",
            )
        }

        /** 单行 KV：id 恒为 1，写入用 REPLACE。 */
        private fun createLimitTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_LIMIT (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    kind TEXT NOT NULL,
                    detail TEXT NOT NULL,
                    origin TEXT NOT NULL,
                    at INTEGER NOT NULL,
                    muted INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    private fun dbFile(): File = File(files, "registry").apply { mkdirs() }.resolve(DB_NAME)
    private fun db(): SQLiteDatabase = helper.writableDatabase

    fun insert(values: ContentValues): Long = lock.withLock {
        val id = db().insert(TABLE, null, values)
        trim()
        bump()
        id
    }

    fun update(id: Long, values: ContentValues) = lock.withLock {
        db().update(TABLE, values, "id=?", arrayOf(id.toString()))
        bump()
    }

    fun find(id: Long): VmRecord? = lock.withLock {
        db().query(TABLE, null, "id=?", arrayOf(id.toString()), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRecord() else null
        }
    }

    fun query(filter: RecordFilter = RecordFilter()): List<VmRecord> = lock.withLock {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        filter.vmId?.let { where += "vm_id=?"; args += it }
        filter.kind?.let { where += "kind=?"; args += it.name }
        filter.status?.let { where += "status=?"; args += it.name }
        filter.from?.let { where += "started_at>=?"; args += it.toString() }
        filter.to?.let { where += "started_at<=?"; args += it.toString() }
        filter.search?.takeIf { it.isNotBlank() }?.let {
            where += "(vm_name LIKE ? OR rom_name LIKE ? OR failure LIKE ? OR summary LIKE ?)"
            repeat(4) { args += "%$it%" }
        }
        val rows = mutableListOf<VmRecord>()
        db().query(
            TABLE, null, where.joinToString(" AND ").takeIf { it.isNotEmpty() },
            args.toTypedArray().takeIf { it.isNotEmpty() },
            null, null, "started_at DESC", filter.limit.toString(),
        ).use { cursor -> while (cursor.moveToNext()) rows += cursor.toRecord() }
        rows
    }

    fun delete(ids: Collection<Long>) = lock.withLock {
        if (ids.isEmpty()) return@withLock
        val placeholders = ids.joinToString(",") { "?" }
        db().delete(TABLE, "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
        bump()
    }

    fun clear() = lock.withLock {
        db().delete(TABLE, null, null)
        bump()
    }

    /** 筛选器用的「哪几台虚拟机有记录」。 */
    fun vmNames(): List<Pair<String, String>> = lock.withLock {
        db().rawQuery("SELECT vm_id, MAX(vm_name) FROM $TABLE GROUP BY vm_id ORDER BY MAX(started_at) DESC", null)
            .use { cursor ->
                val rows = mutableListOf<Pair<String, String>>()
                while (cursor.moveToNext()) rows += cursor.getString(0) to cursor.getString(1)
                rows
            }
    }

    fun count(): Int = lock.withLock {
        db().rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // ------------------------------------------- 子进程限制结论（单行 KV，id = 1）

    /** 读「系统限制子进程」的上次结论；从未落盘时返回 null。 */
    fun loadLimitState(): LimitStateRow? = lock.withLock {
        db().query(TABLE_LIMIT, null, "id = 1", null, null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) return@withLock null
            LimitStateRow(
                kind = cursor.getString(cursor.getColumnIndexOrThrow("kind")),
                detail = cursor.getString(cursor.getColumnIndexOrThrow("detail")),
                origin = cursor.getString(cursor.getColumnIndexOrThrow("origin")),
                at = cursor.getLong(cursor.getColumnIndexOrThrow("at")),
                muted = cursor.getInt(cursor.getColumnIndexOrThrow("muted")) != 0,
            )
        }
    }

    fun saveLimitState(row: LimitStateRow) {
        lock.withLock {
            val values = ContentValues().apply {
                put("id", 1)
                put("kind", row.kind)
                put("detail", row.detail)
                put("origin", row.origin)
                put("at", row.at)
                put("muted", if (row.muted) 1 else 0)
            }
            db().insertWithOnConflict(TABLE_LIMIT, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    // ------------------------------------------- ROM 登记（rom_install，单表）
    //
    // 语义判定（scan/解包/删除顺序）在 me.kanadex.geometryos.rom.RomRepository；
    // 这里只负责存储：一次读写一行的字段，不认识任何 ROM 规则。

    fun romRows(): List<RomInstallRow> = lock.withLock {
        db().query(TABLE_ROM, null, null, null, null, null, "installed_at DESC").use { cursor ->
            val rows = mutableListOf<RomInstallRow>()
            while (cursor.moveToNext()) rows += cursor.toRomInstallRow()
            rows
        }
    }

    fun romRow(key: String): RomInstallRow? = lock.withLock {
        db().query(TABLE_ROM, null, "rom_key=?", arrayOf(key), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRomInstallRow() else null
        }
    }

    fun saveRomRow(row: RomInstallRow) = lock.withLock {
        db().insertWithOnConflict(TABLE_ROM, null, row.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    fun romExtractedBytes(payloadSha256: String): Long? = lock.withLock {
        if (payloadSha256.isBlank()) return@withLock null
        db().query(TABLE_ROM_SIZE, arrayOf("extracted_bytes"), "payload_sha256=?", arrayOf(payloadSha256), null, null, null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    fun saveRomExtractedBytes(payloadSha256: String, extractedBytes: Long) = lock.withLock {
        if (payloadSha256.isBlank() || extractedBytes <= 0L) return@withLock
        db().insertWithOnConflict(
            TABLE_ROM_SIZE,
            null,
            ContentValues().apply {
                put("payload_sha256", payloadSha256)
                put("extracted_bytes", extractedBytes)
                put("measured_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** 只更新状态机与大小的列，不动 rom_key / payload 哈希（那两项是身份）。 */
    fun updateRomState(
        key: String,
        state: String,
        failure: String? = null,
        extractedBytes: Long? = null,
        lastUsedAt: Long? = null,
    ) = lock.withLock {
        val values = ContentValues().apply {
            put("state", state)
            put("failure", failure)
            if (extractedBytes != null) put("extracted_bytes", extractedBytes)
            if (lastUsedAt != null) put("last_used_at", lastUsedAt)
        }
        db().update(TABLE_ROM, values, "rom_key=?", arrayOf(key))
        bump()
    }

    fun deleteRomRow(key: String) = lock.withLock {
        db().delete(TABLE_ROM, "rom_key=?", arrayOf(key))
        bump()
    }

    private fun Cursor.toRomInstallRow(): RomInstallRow = RomInstallRow(
        key = getString(getColumnIndexOrThrow("rom_key")),
        id = getString(getColumnIndexOrThrow("rom_id")),
        version = getString(getColumnIndexOrThrow("rom_version")),
        source = getString(getColumnIndexOrThrow("source")),
        apiLevel = getInt(getColumnIndexOrThrow("api_level")),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        description = getString(getColumnIndexOrThrow("description")),
        androidVersion = getString(getColumnIndexOrThrow("android_version")),
        zipSha256 = getColumnIndexOrThrow("zip_sha256").let { if (isNull(it)) null else getString(it) },
        payloadSha256 = getString(getColumnIndexOrThrow("payload_sha256")),
        payloadBytes = getLong(getColumnIndexOrThrow("payload_bytes")),
        archiveBytes = getLong(getColumnIndexOrThrow("archive_bytes")),
        extractedBytes = getLong(getColumnIndexOrThrow("extracted_bytes")),
        libMode = getString(getColumnIndexOrThrow("lib_mode")),
        state = getString(getColumnIndexOrThrow("state")),
        failure = getColumnIndexOrThrow("failure").let { if (isNull(it)) null else getString(it) },
        installedAt = getLong(getColumnIndexOrThrow("installed_at")),
        lastUsedAt = getColumnIndexOrThrow("last_used_at").let { if (isNull(it)) null else getLong(it) },
    )

    private fun RomInstallRow.toValues(): ContentValues = ContentValues().apply {
        put("rom_key", key)
        put("rom_id", id)
        put("rom_version", version)
        put("source", source)
        put("api_level", apiLevel)
        put("display_name", displayName)
        put("description", description)
        put("android_version", androidVersion)
        put("zip_sha256", zipSha256)
        put("payload_sha256", payloadSha256)
        put("payload_bytes", payloadBytes)
        put("archive_bytes", archiveBytes)
        put("extracted_bytes", extractedBytes)
        put("lib_mode", libMode)
        put("state", state)
        put("failure", failure)
        put("installed_at", installedAt)
        put("last_used_at", lastUsedAt)
    }

    private fun bump() { _changes.value = _changes.value + 1 }

    /** 只保留最近 [MAX_ROWS] 条索引（归档文件由导出/清理逻辑自己管）。 */
    private fun trim() {
        runCatching {
            db().execSQL(
                "DELETE FROM $TABLE WHERE id NOT IN (SELECT id FROM $TABLE ORDER BY started_at DESC LIMIT $MAX_ROWS)",
            )
        }.onFailure { Log.w(TAG, "裁剪记录失败：${it.message}") }
    }

    private fun Cursor.toRecord(): VmRecord {
        fun text(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
        return VmRecord(
            id = getLong(getColumnIndexOrThrow("id")),
            vmId = getString(getColumnIndexOrThrow("vm_id")),
            vmName = getString(getColumnIndexOrThrow("vm_name")),
            romId = getString(getColumnIndexOrThrow("rom_id")),
            romName = text("rom_name"),
            androidVersion = text("android_version"),
            apiLevel = getInt(getColumnIndexOrThrow("api_level")),
            kind = runCatching { RecordKind.valueOf(getString(getColumnIndexOrThrow("kind"))) }
                .getOrDefault(RecordKind.LAUNCH),
            status = runCatching { RecordStatus.valueOf(getString(getColumnIndexOrThrow("status"))) }
                .getOrDefault(RecordStatus.SUCCESS),
            failure = text("failure"),
            summary = text("summary"),
            startedAt = getLong(getColumnIndexOrThrow("started_at")),
            finishedAt = text("finished_at")?.toLongOrNull(),
            durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
            appVersion = getString(getColumnIndexOrThrow("app_version")),
            hostRelease = getString(getColumnIndexOrThrow("host_release")),
            hostModel = getString(getColumnIndexOrThrow("host_model")),
            logDir = text("log_dir"),
            eventCount = getInt(getColumnIndexOrThrow("event_count")),
        )
    }
}

/**
 * 「系统限制子进程」检测结论的持久化行（与运行记录共用同一个库文件与同一把锁）。
 *
 * 语义判定在 `runtime/limits/StoredLimit`；这里只放存储字段，数据层不认识检测规则。
 */
data class LimitStateRow(
    val kind: String,
    val detail: String,
    val origin: String,
    val at: Long,
    val muted: Boolean,
)

/**
 * 一条 ROM 登记（`rom_install` 表的行，字段契约见 `docs/rom-package/06` §4.2）。
 *
 * 状态用字符串存（pending/extracting/ready/failed/missing），由 `RomState` 解释；
 * 数据层不认识状态语义，只保证"写进去什么、读出来什么"。
 */
data class RomInstallRow(
    val key: String,
    val id: String,
    val version: String,
    val source: String,
    val apiLevel: Int,
    val displayName: String,
    val description: String = "",
    val androidVersion: String = "",
    val zipSha256: String? = null,
    val payloadSha256: String,
    val payloadBytes: Long = 0L,
    val archiveBytes: Long = 0L,
    val extractedBytes: Long = 0L,
    val libMode: String = "apk",
    val state: String,
    val failure: String? = null,
    val installedAt: Long,
    val lastUsedAt: Long? = null,
)

/** 记录中心的筛选条件。全部为 null 表示不过滤。 */
data class RecordFilter(
    val vmId: String? = null,
    val kind: RecordKind? = null,
    val status: RecordStatus? = null,
    val from: Long? = null,
    val to: Long? = null,
    val search: String? = null,
    val limit: Int = 300,
) {
    val isActive: Boolean get() = vmId != null || kind != null || status != null || from != null || to != null || !search.isNullOrBlank()
}
