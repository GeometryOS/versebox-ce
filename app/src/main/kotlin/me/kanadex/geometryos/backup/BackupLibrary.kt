// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import io.a10vm.core.ApplicationSingleton
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kanadex.geometryos.rom.RomTexts

/*
 * The private library indexes verified ZIPs for the list and owns their staging area.
 * Callers run file operations off the UI thread. UUIDs are the only disk keys.
 */
internal class BackupLibrary(context: Context) {
    data class Row(val id: UUID, val name: String, val romName: String, val bytes: Long,
        val romBytes: Long?, val createdAt: Long) {
        val sizeLabel get() = me.kanadex.geometryos.rom.RomTexts.bytes(bytes) +
            (romBytes?.let { " / 含 ROM " + me.kanadex.geometryos.rom.RomTexts.bytes(it) } ?: "")
    }

    private val app = context.applicationContext
    private val home get() = File(app.filesDir, "backups")
    private val archives get() = File(home, "archives")
    private val lock = Any()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val database = object : SQLiteOpenHelper(app, "vm-backups.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE backup (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    rom_name TEXT NOT NULL,
                    bytes INTEGER NOT NULL,
                    rom_bytes INTEGER,
                    created_at INTEGER NOT NULL
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported backup library migration: $oldVersion to $newVersion")
        }
    }

    fun rows(): List<Row> = synchronized(lock) {
        database.readableDatabase.query("backup", null, null, null, null, null, "created_at DESC, id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Row(UUID.fromString(cursor.getString(0)), cursor.getString(1), cursor.getString(2),
                        cursor.getLong(3), if (cursor.isNull(4)) null else cursor.getLong(4), cursor.getLong(5)))
                }
            }
        }
    }

    fun newWorkspace(): File {
        val staging = File(home, "staging").toPath()
        Files.createDirectories(staging)
        return Files.createTempDirectory(staging, "operation-").toFile()
    }

    fun archive(id: UUID): File = File(archives, "$id.zip").also {
        require(it.isFile) { "备份文件不存在" }
    }

    fun publish(staged: File, metadata: BackupManifest): UUID = synchronized(lock) {
        val id = metadata.id
        val db = database.writableDatabase
        db.query("backup", arrayOf("id"), "id=?", arrayOf(id.toString()), null, null, null).use {
            require(!it.moveToFirst()) { "该备份已在列表中" }
        }
        Files.createDirectories(archives.toPath())
        val finalFile = File(archives, "$id.zip")
        Files.move(staged.toPath(), finalFile.toPath())
        try {
            db.insertOrThrow("backup", null, ContentValues().apply {
                put("id", id.toString())
                put("name", metadata.settings.name)
                put("rom_name", metadata.settings.rom.name)
                put("bytes", finalFile.length())
                put("rom_bytes", metadata.romArchive?.bytes)
                put("created_at", metadata.createdAtMillis)
            })
        } catch (failure: Exception) {
            try { Files.move(finalFile.toPath(), staged.toPath()) } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        changes.value++
        id
    }

    fun import(
        uri: Uri,
        progress: (BackupArchive.Progress) -> Unit,
        checkActive: () -> Unit,
        onStage: (String) -> Unit = {},
    ): UUID {
        val work = newWorkspace()
        try {
            val staged = File(work, "incoming.zip")
            val total = runCatching {
                app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            onStage(backupCopyStage("正在复制备份文件", 0L, total))
            requireNotNull(app.contentResolver.openInputStream(uri)) { "无法读取备份文件" }.use { input ->
                staged.outputStream().buffered().use { output ->
                    val bytes = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    var copied = 0L
                    var reported = 0L
                    while (true) {
                        checkActive()
                        val read = input.read(bytes)
                        if (read < 0) break
                        output.write(bytes, 0, read)
                        copied += read
                        val now = System.nanoTime()
                        if (now - reported >= 500_000_000L) {
                            reported = now
                            onStage(backupCopyStage("正在复制备份文件", copied, total))
                        }
                    }
                    onStage(backupCopyStage("正在复制备份文件", copied, total))
                }
            }
            onStage("正在校验备份内容")
            val metadata = BackupArchive.verify(staged, progress, checkActive)
            checkActive()
            onStage("正在登记备份")
            return publish(staged, metadata)
        } finally {
            work.deleteRecursively()
        }
    }

    fun readEmbeddedRom(id: UUID, checkActive: () -> Unit): me.kanadex.geometryos.rom.RomProperties {
        val work = newWorkspace()
        try {
            val rom = work.resolve("preview.zip")
            val manifest = BackupArchive.extractRom(archive(id), rom, checkActive = checkActive)
            val properties = me.kanadex.geometryos.rom.RomPackage.read(rom)
            require(properties.key == manifest.settings.rom.key &&
                properties.payloadSha256 == manifest.settings.rom.payloadSha256) { "备份 ROM 与所选版本不一致" }
            return properties
        } finally {
            work.deleteRecursively()
        }
    }

    fun export(id: UUID, uri: Uri) = synchronized(lock) {
        archive(id).inputStream().buffered().use { input ->
            requireNotNull(app.contentResolver.openOutputStream(uri, "wt")) { "无法写入导出文件" }.use { output ->
                input.copyTo(output)
            }
        }
        Unit
    }

    fun share(id: UUID) {
        val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", archive(id))
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(app.contentResolver, "备份", uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(android.content.Intent.createChooser(intent, "分享备份")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun delete(id: UUID) = synchronized(lock) {
        val original = File(archives, "$id.zip").toPath()
        val removed = File(archives, "$id.removing").toPath()
        val db = database.writableDatabase
        val moved = Files.exists(original)
        if (moved) Files.move(original, removed)
        try {
            check(db.delete("backup", "id=?", arrayOf(id.toString())) == 1) { "找不到该备份" }
        } catch (failure: Exception) {
            if (moved) try { Files.move(removed, original) } catch (rollback: Exception) {
                failure.addSuppressed(rollback)
            }
            throw failure
        }
        changes.value++
        Files.deleteIfExists(removed)
        Unit
    }

    companion object {
        private val shared = ApplicationSingleton { BackupLibrary(it) }

        fun get(context: Context): BackupLibrary = shared.of(context)
    }
}

// 复制阶段的进度文案：已复制字节总在，总量未知时只报已复制
internal fun backupCopyStage(label: String, done: Long, total: Long): String {
    val suffix = if (total > 0L) " / " + RomTexts.bytes(total) else ""
    return label + " " + RomTexts.bytes(done) + suffix
}
