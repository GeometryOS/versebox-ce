package me.kanadex.geometryos.audit.model

/** 一个待淘汰的文件。 */
data class SessionFile(val path: String, val bytes: Long, val modifiedAt: Long)

/**
 * 一个会话组（淘汰的最小单位，见 docs/log-audit/02 §2.2）。
 *
 * [legacy] 的组不是真的会话：它是升级前的无标识文件，按天分桶，
 * 完整性无从判定，因此在第一轮淘汰里优先清理。
 */
data class SessionSnapshot(
    val id: SessionId,
    val active: Boolean,
    val legacy: Boolean,
    val startedAt: Long,
    val endedAt: Long?,
    /** 会话目录（`vm/sessions/<id>`）；legacy 桶为 null。 */
    val groupDir: String?,
    val files: List<SessionFile>,
) {
    val bytes: Long get() = files.sumOf { it.bytes }

    /** 最后一次写入时刻：排序、"是不是还在写"都看它，不看目录 mtime。 */
    val lastWriteAt: Long get() = files.maxOfOrNull { it.modifiedAt } ?: (endedAt ?: startedAt)

    /** 有结束记录且不在跑 —— 只有这种会话允许被淘汰。 */
    val isClosed: Boolean get() = !active && endedAt != null
}

/** 会话台账里的一行（宿主写盘：`vm/sessions/<id>/meta`）。 */
data class SessionRecord(
    val id: SessionId,
    val startedAt: Long,
    val endedAt: Long?,
    val active: Boolean,
)
