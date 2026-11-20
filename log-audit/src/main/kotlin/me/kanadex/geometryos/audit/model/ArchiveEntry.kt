package me.kanadex.geometryos.audit.model

/**
 * 归档在淘汰策略眼里的保护级别（docs/log-audit/02 §3.1）。
 *
 * 与 `io.a10vm.records.RecordStatus` 的映射由 app 适配层做：
 * 本模块不认识记录业务，只认识"能不能删"。
 */
enum class ArchiveOutcome {
    /** 成功：第一顺位淘汰，最旧的先。 */
    SUCCESS,

    /** 已取消（进程被杀等）：第二顺位。 */
    CANCELLED,

    /** 失败 / 崩溃：受保护，永不自动淘汰。 */
    PROTECTED,
}

/** 一条记录在策略眼里的样子：id、字节、结束时刻、归档目录。 */
data class ArchiveEntry(
    val recordId: Long,
    val vmId: String,
    val outcome: ArchiveOutcome,
    val bytes: Long,
    val finishedAt: Long?,
    /** 归档目录绝对路径；没有归档（旧记录/未结束）时为空串。 */
    val dir: String,
) {
    val protected: Boolean get() = outcome == ArchiveOutcome.PROTECTED
}
