package me.kanadex.geometryos.audit.model

/**
 * 审计事件：清理动作本身的留痕（docs/log-audit/02 §4）。
 *
 * 字段用 `Map<String, Any>`：NDJSON 一行一条，值只允许 String/Long/Boolean，
 * 编码器在 [me.kanadex.geometryos.audit.json.AuditEventJson] 里只有一处实现。
 */
data class AuditEvent(val at: Long, val kind: String, val fields: Map<String, Any>)

/** 事件类型常量。字符串只在这里出现一次，页面与文档引用同一份。 */
object AuditEventKind {
    const val SESSION_EVICT = "session_evict"
    const val SESSION_SKIP = "session_skip"
    const val SESSION_STALE_CLOSED = "session_stale_closed"
    const val ARCHIVE_EVICT = "archive_evict"
    const val ARCHIVE_WRITTEN = "archive_written"
    const val ARCHIVE_INCOMPLETE = "archive_incomplete"
    const val ARCHIVE_FAILED = "archive_failed"
    const val CAP_REACHED = "cap_reached"
    const val WINDOW_LOG_TRUNCATED = "window_log_truncated"
    const val TRASH_FAILED = "trash_failed"
    const val POLICY = "policy"
}
