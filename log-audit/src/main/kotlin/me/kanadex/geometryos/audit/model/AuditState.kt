package me.kanadex.geometryos.audit.model

/**
 * 容量视图 —— **唯一真相**（docs/log-audit/02 §5）。
 *
 * 记录页、设置页、日志审计页都只读这一个状态，不各自 `du`、不各自算阈值；
 * 因此这里只有数字和一个布尔，没有任何 Android/Compose 类型。
 */
data class AuditState(
    val sessionBytes: Long = 0,
    val archiveBytes: Long = 0,
    val protectedCount: Int = 0,
    val pendingCleanup: Boolean = false,
    val lastMaintainedAt: Long = 0,
    val policy: AuditPolicy = AuditPolicy.Default,
) {
    /** 硬上限时给用户看的那句话（文案属逻辑层，页面只渲染）。 */
    val pendingLabel: String?
        get() = if (!pendingCleanup) {
            null
        } else {
            "记录已占用 " + formatMegabytes(archiveBytes) + "（上限 " +
                formatMegabytes(policy.archiveHardCap) + "）。已保护 " + protectedCount +
                " 条失败记录，是否清理成功记录？"
        }

    companion object {
        /** 容量文案的唯一实现：页面与提示都调它，避免两处各写一遍取整。 */
        fun formatMegabytes(bytes: Long): String {
            val mega = bytes / (1024.0 * 1024.0)
            return if (mega >= 1024) String.format(java.util.Locale.ROOT, "%.1f GB", mega / 1024.0)
            else String.format(java.util.Locale.ROOT, "%.0f MB", mega)
        }
    }
}
