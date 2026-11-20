package me.kanadex.geometryos.audit.port

import me.kanadex.geometryos.audit.model.AuditEvent

/**
 * 审计事件出口（`files/audit/audit.log`）。
 *
 * 实现必须吞掉自身异常：审计写不进去不能反过来把清理搅黄，
 * 但也**不允许**为了省事提供"清理时不写事件"的快捷路径（docs/log-audit/05 §回滚）。
 */
interface AuditEventSink {
    fun emit(event: AuditEvent)
}

/** 测试与"还没有适配层"时用的空实现。 */
object NoopAuditEventSink : AuditEventSink {
    override fun emit(event: AuditEvent) = Unit
}
