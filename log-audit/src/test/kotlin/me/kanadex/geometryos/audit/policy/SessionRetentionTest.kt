package me.kanadex.geometryos.audit.policy

import me.kanadex.geometryos.audit.model.AuditPolicy
import me.kanadex.geometryos.audit.model.SessionFile
import me.kanadex.geometryos.audit.model.SessionId
import me.kanadex.geometryos.audit.model.SessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRetentionTest {
    private fun session(
        id: String,
        end: Long,
        bytes: Long,
        active: Boolean = false,
        legacy: Boolean = false,
        files: Int = 44,
    ) = SessionSnapshot(
        id = SessionId(id),
        active = active,
        legacy = legacy,
        startedAt = end - 1_000,
        endedAt = if (active) null else end,
        groupDir = if (legacy) null else "/sessions/$id",
        files = (1..files).map { index ->
            SessionFile("/sessions/$id/application-1-$index.log", bytes / files, end)
        },
    )

    private val policy = AuditPolicy(keepSessions = 3, sessionBytesCap = 1_000_000_000L)

    @Test
    fun `keeps newest sessions and evicts older ones as whole groups`() {
        val plan = SessionRetention.plan(
            listOf(session("s1", 1_000, 100), session("s2", 2_000, 100), session("s3", 3_000, 100), session("s4", 4_000, 100)),
            policy,
        )
        assertEquals(setOf("s1"), plan.evictions.map { it.session.value }.toSet())
        assertEquals("over_count", plan.evictions.single().reason)
        // 一次淘汰必须是整组 44 个文件，不是"删到 200 个文件为止"
        assertEquals(44, plan.evictions.single().files.size)
        assertEquals(3, plan.keptSessions)
    }

    @Test
    fun `never evicts an active session even when it is the oldest`() {
        val plan = SessionRetention.plan(
            listOf(
                session("live", 1_000, 100, active = true),
                session("s2", 2_000, 100),
                session("s3", 3_000, 100),
                session("s4", 4_000, 100),
                session("s5", 5_000, 100),
            ),
            policy,
        )
        assertTrue(plan.evictions.none { it.session.value == "live" })
        assertEquals(setOf("s2"), plan.evictions.map { it.session.value }.toSet())
    }

    @Test
    fun `legacy groups go first`() {
        val plan = SessionRetention.plan(
            listOf(
                session("legacy-20260101", 500, 900, legacy = true),
                session("s1", 1_000, 100),
                session("s2", 2_000, 100),
            ),
            policy,
        )
        assertEquals(listOf("legacy-20260101"), plan.evictions.map { it.session.value })
        assertEquals("legacy", plan.evictions.single().reason)
    }

    @Test
    fun `byte cap evicts oldest but keeps the newest session`() {
        val tiny = policy.copy(keepSessions = 3, sessionBytesCap = 300)
        val plan = SessionRetention.plan(
            listOf(session("s1", 1_000, 200), session("s2", 2_000, 200)),
            tiny,
        )
        assertEquals(listOf("s1"), plan.evictions.map { it.session.value })
        assertEquals("over_bytes", plan.evictions.single().reason)
        // 最新一次永远保留：即便它自己就超过上限
        assertEquals(1, plan.keptSessions)
    }

    @Test
    fun `session without end marker is not evictable`() {
        val open = SessionSnapshot(
            id = SessionId("never-ended"),
            active = false,
            legacy = false,
            startedAt = 0,
            endedAt = null,
            groupDir = "/sessions/never-ended",
            files = listOf(SessionFile("/sessions/never-ended/application-1-1.log", 10, 10)),
        )
        val plan = SessionRetention.plan(listOf(open), policy.copy(sessionBytesCap = 1))
        assertTrue(plan.evictions.isEmpty())
    }
}
