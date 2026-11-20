package me.kanadex.geometryos.audit.model

/**
 * 全部阈值的**唯一来源**（docs/log-audit/04 §4）。
 *
 * 页面、Service、RecordCollector 里再出现 `2 * 1024 * 1024` 这类字面量即视为缺陷：
 * 一旦阈值散落，UI 与库就会各算一套"还剩多少"，最后没人知道哪个是对的。
 *
 * 默认值与 docs/log-audit/02-保留策略.md 的条文一致；实测依据见 01-现状与实测.md。
 */
data class AuditPolicy(
    /** 运行期日志保留最近 N 次会话（整组）。 */
    val keepSessions: Int = 3,
    /** 运行期日志总量兜底上限，超出按会话从旧到新整组淘汰。 */
    val sessionBytesCap: Long = 300L * 1024 * 1024,
    /** 归档软上限：超过后自动淘汰"成功且最旧"的整条记录。 */
    val archiveSoftCap: Long = 400L * 1024 * 1024,
    /** 归档硬上限：达到后停止自动淘汰，转为"待用户确认"。 */
    val archiveHardCap: Long = 500L * 1024 * 1024,
    /** 单条记录归档总量预算。 */
    val recordBytesBudget: Long = 12L * 1024 * 1024,
    /** 单文件超过它就只取尾部这么多字节（崩溃现场在末尾）。 */
    val fileTailBytes: Long = 2L * 1024 * 1024,
    /** 审计日志自身的上限（超出保留尾部）。 */
    val auditLogBytesCap: Long = 1L * 1024 * 1024,
    /**
     * `native-window/gpu-host.log` 的尾部保留上限。
     *
     * 该文件由客体侧 `fopen(path, "a")` 只追加写（DesktopGpuServer.cpp:30），
     * 跨会话累积且从不轮转；宿主在每次会话开始时把它截断到尾部这个大小，
     * 效果与在 cpp 里改成截断写一致，且不需要重编客体 native 链。
     */
    val windowLogTailBytes: Long = 8L * 1024 * 1024,
    /** `.trash-*` 目录超过这个年龄还删不掉就记审计事件并上报（阶段 4）。 */
    val trashGraceMillis: Long = 24L * 60 * 60 * 1000,
) {
    /**
     * 归一化：设置里读到越界值（0、负数、硬上限小于软上限）时，用它换回一套自洽的阈值。
     * 策略函数只接收归一化后的对象，因此不必在纯函数里写防御分支。
     */
    fun normalized(): AuditPolicy = copy(
        keepSessions = keepSessions.coerceAtLeast(1),
        sessionBytesCap = sessionBytesCap.coerceAtLeast(0L),
        archiveSoftCap = archiveSoftCap.coerceAtLeast(0L),
        // 硬上限低于软上限时以软上限为准：否则"软上限自动淘汰"永远追不上。
        archiveHardCap = archiveHardCap.coerceAtLeast(archiveSoftCap),
        recordBytesBudget = recordBytesBudget.coerceAtLeast(0L),
        fileTailBytes = fileTailBytes.coerceAtLeast(1L),
        auditLogBytesCap = auditLogBytesCap.coerceAtLeast(0L),
        windowLogTailBytes = windowLogTailBytes.coerceAtLeast(0L),
        trashGraceMillis = trashGraceMillis.coerceAtLeast(0L),
    )

    companion object {
        val Default = AuditPolicy()
    }
}
