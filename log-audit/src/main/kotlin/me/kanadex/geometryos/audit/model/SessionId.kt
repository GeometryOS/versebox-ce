package me.kanadex.geometryos.audit.model

/**
 * 会话标识值类型（docs/log-audit/02 §2.1、04 §6）。
 *
 * 一次"启动 → 停止/崩溃"= 一个会话；运行期日志以**会话组**为淘汰单位，
 * 所以每一组都必须能回答"我属于哪次启动"。
 *
 * 名字会被当作目录名用（`vm/sessions/<id>/`），所以这里负责把任何来源的字符串
 * 收敛成 `[A-Za-z0-9_-]`，不允许出现路径分隔符 —— 策略层不该有机会拼出越界路径。
 */
@JvmInline
value class SessionId(val value: String) {
    /** 没有会话标识的旧文件（升级前的 `application-<pid>-<seq>.log`）。 */
    val isLegacy: Boolean get() = value == LEGACY || value.startsWith(LEGACY_PREFIX)

    val isSafeForPath: Boolean
        get() = value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    override fun toString(): String = value

    companion object {
        const val LEGACY = "legacy"
        private const val LEGACY_PREFIX = "legacy-"

        val Legacy = SessionId(LEGACY)

        /**
         * 宿主侧生成会话标识。
         *
         * 不允许"猜一个时间戳当 id"：时间戳只用来排序，真正的唯一性由 [nonce] 提供，
         * 同一个毫秒里的两次启动也不会撞名。
         */
        fun generate(nowMillis: Long, nonce: String): SessionId {
            val stamp = java.lang.Long.toString(nowMillis.coerceAtLeast(0L), 36)
            val suffix = nonce.filter { it.isLetterOrDigit() }.lowercase().take(8)
            return SessionId(if (suffix.isEmpty()) stamp else "$stamp-$suffix")
        }

        /** 把外部来源（旧目录名、台账字段）收敛成合法 id；不可用时退回 legacy。 */
        fun of(raw: String?): SessionId {
            val cleaned = raw?.trim().orEmpty()
            if (cleaned.isEmpty()) return Legacy
            val safe = cleaned.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            if (safe.isEmpty()) return Legacy
            return SessionId(safe.take(64))
        }

        /** 旧文件按天分桶时用的伪会话 id（`legacy-20261104`）。 */
        fun legacyBucket(dayKey: String): SessionId = SessionId(LEGACY_PREFIX + dayKey)
    }
}
