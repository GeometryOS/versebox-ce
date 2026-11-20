package me.kanadex.geometryos.audit

import me.kanadex.geometryos.audit.model.ArchiveEntry
import me.kanadex.geometryos.audit.model.ArchiveOutcome
import me.kanadex.geometryos.audit.model.AuditPolicy
import me.kanadex.geometryos.audit.model.SessionId
import me.kanadex.geometryos.audit.testing.FakeFileSystem
import me.kanadex.geometryos.audit.testing.RecordingSink
import me.kanadex.geometryos.audit.testing.TestClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAuditEngineTest {
    private val fs = FakeFileSystem()
    private val sink = RecordingSink()
    private val clock = TestClock(1_762_300_000_000L)
    private var policy = AuditPolicy(
        keepSessions = 1,
        sessionBytesCap = 1_000_000_000L,
        windowLogTailBytes = 1_000L,
    )
    private val engine = DefaultAuditEngine(fs, clock, sink) { policy }

    private val layout = DeviceLayout(
        vmId = "vm-1",
        runtimeDir = "/dev/vm",
        sessionsDir = "/dev/vm/sessions",
        windowLogPath = "/dev/native-window/gpu-host.log",
    )

    @Test
    fun `session begin writes the ledger and truncates the append-only window log`() = runBlocking {
        fs.putFile("/dev/native-window/gpu-host.log", 5_000, 1)
        val session = SessionId.generate(clock.now(), "abcd1234")

        engine.onSessionBegin(layout, session)

        assertTrue(fs.exists("/dev/vm/sessions/${session.value}/.active"))
        assertTrue(fs.text("/dev/vm/sessions/${session.value}/meta")!!.contains("started="))
        assertEquals(1_000L, fs.size("/dev/native-window/gpu-host.log"))
        assertEquals(listOf("window_log_truncated"), sink.kinds())
    }

    @Test
    fun `session end closes the ledger so the group becomes evictable`() = runBlocking {
        val session = SessionId.generate(clock.now(), "beef0001")
        engine.onSessionBegin(layout, session)
        fs.putFile("/dev/vm/sessions/${session.value}/application-1-1.log", 100, clock.now())
        engine.onSessionEnd(layout, session)
        assertFalse(fs.exists("/dev/vm/sessions/${session.value}/.active"))
        assertTrue(fs.text("/dev/vm/sessions/${session.value}/meta")!!.contains("ended="))
    }

    @Test
    fun `maintain evicts legacy logs whole and reports the容量 view`() = runBlocking {
        fs.putFile("/dev/vm/application-1-1.log", 10, 500)
        fs.putFile("/dev/vm/application-1-2.log", 10, 600)
        val report = engine.maintain(AuditTrigger.SERVICE_START, listOf(layout), emptyList())
        assertTrue(report.freedSessionBytes >= 20L)
        assertTrue(fs.paths().none { it.contains("/dev/vm/application-") })
        assertTrue(sink.kinds().contains("session_evict"))
        assertEquals(report.sessionBytes, engine.state.value.sessionBytes)
    }

    @Test
    fun `archive goes through the engine and returns a manifest`() = runBlocking {
        fs.putText("/dev/vm/startup-events.log", "[000.10s] BOOT   Android 15 startup", 1)
        val result = engine.archive(
            ArchiveRequest(
                vmId = "vm-1",
                recordId = 9,
                sessionId = SessionId("m4x1-abcdef12"),
                kind = "LAUNCH",
                status = "SUCCESS",
                outcome = ArchiveOutcome.SUCCESS,
                targetDir = "/records/vm-1/9",
                roots = listOf(me.kanadex.geometryos.audit.policy.ScanRoot("/dev/vm", "vm/")),
            ),
        )
        assertTrue(result is ArchiveResult.Success)
        assertTrue(fs.exists("/records/vm-1/9/manifest.json"))
    }

    @Test
    fun `hard cap archives are not deleted until the user confirms`() = runBlocking {
        policy = policy.copy(archiveSoftCap = 100, archiveHardCap = 100)
        fs.putFile("/records/vm-1/1/framework-server.log", 300, 1)
        val archives = listOf(
            ArchiveEntry(1, "vm-1", ArchiveOutcome.PROTECTED, 300, 1_000, "/records/vm-1/1"),
        )
        val report = engine.maintain(AuditTrigger.SERVICE_START, emptyList(), archives)
        assertTrue(report.pendingCleanup)
        assertTrue(report.deletedRecords.isEmpty())
        assertTrue(fs.exists("/records/vm-1/1/framework-server.log"))

        val confirmed = engine.cleanupSuccessArchives(confirm = true, archives = listOf(
            ArchiveEntry(1, "vm-1", ArchiveOutcome.SUCCESS, 300, 1_000, "/records/vm-1/1"),
        ))
        assertEquals(listOf(1L), confirmed.deletedRecords)
        assertFalse(fs.exists("/records/vm-1/1/framework-server.log"))
    }
}
