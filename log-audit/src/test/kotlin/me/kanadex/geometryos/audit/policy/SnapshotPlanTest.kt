package me.kanadex.geometryos.audit.policy

import me.kanadex.geometryos.audit.model.AuditPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotPlanTest {
    private val policy = AuditPolicy(
        recordBytesBudget = 3L * 1024 * 1024,
        fileTailBytes = 2L * 1024 * 1024,
    )

    @Test
    fun `oversized file keeps its tail instead of being skipped`() {
        val result = SnapshotPlan.plan(
            listOf(
                SnapshotSource("/vm/framework-server.log", "vm/framework-server.log", 4_876_543L, 2),
            ),
            policy,
        )
        val copy = result.copies.single()
        assertEquals("tail@2MB", copy.truncated)
        assertEquals(2L * 1024 * 1024, copy.storedBytes)
        assertEquals(4_876_543L, copy.bytes)
        assertFalse(result.complete)
    }

    @Test
    fun `budget overflow lands in missing with a reason instead of vanishing`() {
        val result = SnapshotPlan.plan(
            listOf(
                SnapshotSource("/vm/startup-events.log", "startup-events.log", 1_000, 0),
                SnapshotSource("/vm/framework-server.log", "vm/framework-server.log", 2L * 1024 * 1024, 2),
                SnapshotSource("/native-window/gpu-host.log", "native-window/gpu-host.log", 2L * 1024 * 1024, 3),
            ),
            policy,
        )
        assertEquals(setOf("startup-events.log", "vm/framework-server.log"), result.copies.map { it.stored }.toSet())
        assertEquals("over_record_budget", result.missing.single().reason)
        assertEquals("native-window/gpu-host.log", result.missing.single().path)
    }

    @Test
    fun `small files are copied whole`() {
        val result = SnapshotPlan.plan(
            listOf(SnapshotSource("/vm/startup-events.log", "startup-events.log", 3_302, 0)),
            policy,
        )
        assertNull(result.copies.single().truncated)
        assertTrue(result.complete)
    }

    @Test
    fun `expected file that never appeared is reported as not_created`() {
        val result = SnapshotPlan.plan(emptyList(), policy, expected = listOf("startup-events.log"))
        assertEquals("not_created", result.missing.single().reason)
        assertFalse(result.complete)
    }

    @Test
    fun `operation log is archived ahead of framework logs`() {
        val result = SnapshotPlan.plan(
            listOf(
                SnapshotSource("/runtime/operation.log", "runtime/operation.log", 128, 2),
                SnapshotSource("/vm/framework-server.log", "vm/framework-server.log", 128, 3),
            ),
            policy,
        )
        assertEquals(listOf("runtime/operation.log", "vm/framework-server.log"), result.copies.map { it.stored })
        assertTrue(SnapshotPlan.wanted("runtime/operation.log"))
        assertFalse(SnapshotPlan.wanted("rootfs/system/framework/framework.jar"))
        assertFalse(SnapshotPlan.wanted("vm/application-8891-1.log"))
    }
}
