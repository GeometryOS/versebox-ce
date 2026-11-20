package me.kanadex.geometryos.audit.model

/** `manifest.json` 里的一条文件记录（docs/log-audit/03 §2）。 */
data class ManifestFile(
    /** 归档内的相对路径（`vm/framework-server.log`）。 */
    val path: String,
    /** 原始文件大小（归档时的真实值）。 */
    val bytes: Long,
    /** 实际写进归档的字节数。 */
    val stored: Long,
    /** null = 完整；`tail@2MB` = 只保留了尾部 2 MB。 */
    val truncated: String?,
    val sha256: String? = null,
)

/** 白名单里应该有、但归档时不存在或读不到的文件。 */
data class ManifestMissing(val path: String, val reason: String)

/**
 * 一份快照的自述。没有 manifest 的旧归档一律视为"完整性未知"（03 §1）。
 */
data class ArchiveManifest(
    val recordId: Long,
    val vmId: String,
    val sessionId: SessionId,
    val kind: String,
    val status: String,
    val createdAt: Long,
    val files: List<ManifestFile>,
    val missing: List<ManifestMissing>,
    val complete: Boolean,
    val schema: String = SCHEMA,
) {
    val storedBytes: Long get() = files.sumOf { it.stored }
    val truncatedCount: Int get() = files.count { it.truncated != null }

    companion object {
        /** 结构版本；不认识这个值的归档按"完整性未知"处理，不删、不改写。 */
        const val SCHEMA = "versebox-record-manifest/1"
    }
}
