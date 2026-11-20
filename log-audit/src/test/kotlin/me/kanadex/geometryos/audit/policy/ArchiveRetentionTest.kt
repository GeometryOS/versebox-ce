package me.kanadex.geometryos.audit.policy

import me.kanadex.geometryos.audit.model.ArchiveEntry
import me.kanadex.geometryos.audit.model.ArchiveOutcome
import me.kanadex.geometryos.audit.model.AuditPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveRetentionTest {
    private fun entry(id: Long, outcome: ArchiveOutcome, bytes: Long, at: Long) = ArchiveEntry(
        recordId = id,
        vmId = "vm",
        outcome = outcome,
        bytes = bytes,
        finishedAt = at,
        dir = "/records/vm/$id",
    )

    private val policy = AuditPolicy(
        archiveSoftCap = 100,
        archiveHardCap = 200,
    )

    @Test
    fun `under soft cap nothing is evicted`() {
        val plan = ArchiveRetention.plan(listOf(entry(1, ArchiveOutcome.SUCCESS, 40, 1)), policy)
        assertTrue(plan.evictions.isEmpty())
        assertFalse(plan.pendingUser)
    }

    @Test
    fun `over soft cap evicts oldest success first and never touches failures`() {
        val plan = ArchiveRetention.plan(
            listOf(
                entry(1, ArchiveOutcome.SUCCESS, 60, 1_000),
                entry(2, ArchiveOutcome.SUCCESS, 60, 2_000),
                entry(3, ArchiveOutcome.PROTECTED, 60, 3_000),
            ),
            policy,
        )
        // 180 总量、软上限 100：两条成功记录（各 60）都要淘汰，失败记录不动
        assertEquals(listOf(1L, 2L), plan.deletedIds())
        assertEquals(1, plan.protectedCount)
        assertFalse(plan.pendingUser)
        assertEquals("over_soft_cap", plan.reason)
    }

    @Test
    fun `cancelled records are evicted after successes`() {
        val plan = ArchiveRetention.plan(
            listOf(
                entry(1, ArchiveOutcome.CANCELLED, 80, 500),
                entry(2, ArchiveOutcome.SUCCESS, 80, 900),
            ),
            policy,
        )
        assertEquals(listOf(2L), plan.deletedIds())
    }

    @Test
    fun `hard cap means ask the user, not silent delete`() {
        val plan = ArchiveRetention.plan(
            listOf(
                entry(1, ArchiveOutcome.PROTECTED, 150, 1_000),
                entry(2, ArchiveOutcome.PROTECTED, 150, 2_000),
                entry(3, ArchiveOutcome.SUCCESS, 10, 3_000),
            ),
            policy,
        )
        assertTrue(plan.pendingUser)
        // 计划里只列出"可以问用户的那条成功记录"，受保护的两条永远不入选
        assertEquals(listOf(3L), plan.deletedIds())
        assertTrue(plan.evictions.none { it.protected })
        assertEquals(310L, plan.totalBytes)
    }

    private fun ArchiveRetentionPlan.deletedIds(): List<Long> = evictions.map { it.recordId }
}
