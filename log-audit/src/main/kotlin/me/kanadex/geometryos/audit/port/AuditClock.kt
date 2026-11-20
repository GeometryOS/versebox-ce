package me.kanadex.geometryos.audit.port

/** 时间注入：策略单测里固定时间，避免"看着像过了 24 小时"。 */
interface AuditClock {
    fun now(): Long
}
