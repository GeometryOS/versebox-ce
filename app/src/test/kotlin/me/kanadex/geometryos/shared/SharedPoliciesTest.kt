// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.shared

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/*
 * The pure policies use fixed inputs so the tests run without a device, filesystem or SQLite.
 * Workflow fixtures exercise those dependencies separately.
 */
class SharedPoliciesTest {
    private val now = Instant.parse("2026-11-28T08:00:00Z").toEpochMilli()
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test fun groupsAtEveryBoundaryAndSortsWithinGroups() {
        val ages = listOf(0L, 1L, 7L, 30L, 183L, 365L)
        val times = ages.map { now - it * day }
        val sorted = BackupDateGrouping.groupAndSort(listOf(null, now - 1) + times.reversed(), now) { it }
        assertEquals(BackupDateGrouping.Group.entries, sorted.map { it.first })
        assertEquals(listOf(now, now - 1), sorted.first().second)
        assertEquals(listOf(null), sorted.last().second)
        assertEquals(BackupDateGrouping.Group.UNKNOWN, BackupDateGrouping.group(now + 1, now))
        assertEquals(BackupDateGrouping.Group.UNKNOWN, BackupDateGrouping.group(0L, now))
        assertEquals("2026-11-28 16:00:00", BackupDateGrouping.format(now, ZoneId.of("Asia/Shanghai")))
    }

    @Test fun allocatesSmallestFreeSuffixAcrossDenseAndSparseNames() {
        val base = "Android15 虚拟机"
        val occupied = listOf(base) + (1..199).map { "$base($it)" }
        val corrected = checkNotNull(VmInstanceNamePolicy.correct(base, occupied, 24))
        assertEquals("$base(200)", corrected.value)
        assertTrue(checkNotNull(corrected.event).contains(corrected.value))
        assertEquals("$base(3)", VmInstanceNamePolicy.correct(base, occupied - "$base(3)", 24)?.value)
        assertEquals("$base(200)", VmInstanceNamePolicy.correct("$base(1)", occupied, 24)?.value)
    }

    @Test fun checksNormalizedKeysAndTruncatedCandidates() {
        assertEquals("É VM(2)", VmInstanceNamePolicy.correct(" É   VM ", listOf("e\u0301 vm", "é vm(1)"), 24)?.value)
        val base = "😀".repeat(24)
        assertEquals("😀".repeat(21) + "(2)",
            VmInstanceNamePolicy.correct(base, listOf(base, "😀".repeat(21) + "(1)"), 24)?.value)
        assertNull(VmInstanceNamePolicy.correct("  ", emptyList(), 24))
        assertEquals("Android 15 虚拟机", VmInstanceNamePolicy.defaultName("Android 15", 24))
    }
}
