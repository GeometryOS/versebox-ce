package me.kanadex.geometryos.audit.port

/** 文件系统里的一项（目录或文件）。 */
data class FsEntry(
    val path: String,
    val directory: Boolean,
    val bytes: Long,
    val modifiedAt: Long,
)

/**
 * 文件系统出口（docs/log-audit/04 §2）。
 *
 * 策略层只产出"计划"，所有真正落盘的动作都从这里走：
 * 删除、复制尾部、原子 rename、截断到尾部。实现放在 :app 的
 * `me.kanadex.geometryos.audit.android`（唯一允许 import android.* 的地方）。
 */
interface AuditFileSystem {
    fun exists(path: String): Boolean

    fun isDirectory(path: String): Boolean

    /** 不存在返回 0。 */
    fun size(path: String): Long

    /** 不存在返回 0。 */
    fun modifiedAt(path: String): Long

    /** 单层列举；目录不存在返回空表。 */
    fun list(directory: String): List<FsEntry>

    /** 递归列举文件（不含目录）；目录不存在返回空表。 */
    fun walkFiles(directory: String): List<FsEntry>

    fun makeDirectories(directory: String)

    /** 整份复制；返回写入字节数。 */
    fun copyFile(source: String, target: String): Long

    /** 只复制尾部 [maxBytes] 字节（崩溃现场在末尾）；返回写入字节数。 */
    fun copyTail(source: String, target: String, maxBytes: Long): Long

    fun writeText(path: String, text: String)

    fun appendText(path: String, text: String)

    /** 读文本，最多 [maxBytes]（从文件头读）；不存在返回空串。 */
    fun readText(path: String, maxBytes: Long): String

    /** 同目录内 rename；成功返回 true。 */
    fun rename(from: String, to: String): Boolean

    fun delete(path: String): Boolean

    fun deleteRecursively(path: String): Boolean

    /** 把文件截断成"只保留尾部 [keepBytes]"；返回保留后的字节数。 */
    fun truncateToTail(path: String, keepBytes: Long): Long
}
