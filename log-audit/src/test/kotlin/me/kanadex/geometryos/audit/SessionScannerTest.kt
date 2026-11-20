package me.kanadex.geometryos.audit

import me.kanadex.geometryos.audit.model.SessionId
import me.kanadex.geometryos.audit.testing.FakeFileSystem
import me.kanadex.geometryos.audit.testing.RecordingSink
import me.kanadex.geometryos.audit.testing.TestClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionScannerTest {
    private val fs = FakeFileSystem()
    private val clock = TestClock(10_000L)
    private val sink = RecordingSink()
    private val scanner = SessionScanner(fs, clock, sink)

    private val layout = DeviceLayout(
        vmId = "1793740665036",
        runtimeDir = "/dev/root/vm",
        sessionsDir = "/dev/root/vm/sessions",
        windowLogPath = "/dev/root/native-window/gpu-host.log",
    )

    @Test
    fun `loose application logs are attributed by the ledger window, not by day`() {
        fs.putText("/dev/root/vm/sessions/s1/meta", "started=1000\nended=2000\n")
        fs.putFile("/dev/root/vm/sessions/s1/application-1-1.log", 100, 1_500)
        fs.putFile("/dev/root/vm/application-9-1.log", 10, 1_400)
        fs.putFile("/dev/root/vm/application-9-2.log", 10, 500)

        val snapshots = scanner.scan(layout)
        val session = snapshots.single { it.id == SessionId("s1") }
        assertEquals(2, session.files.size)
        assertTrue(session.isClosed)
        assertEquals(110L, session.bytes)

        val legacy = snapshots.single { it.legacy }
        assertEquals(1, legacy.files.size)
        assertTrue(legacy.id.isLegacy)
        assertEquals(10L, legacy.bytes)
    }

    @Test
    fun `framework and window logs are never grouped into session eviction`() {
        fs.putText("/dev/root/vm/sessions/s1/meta", "started=1000\nended=2000\n")
        fs.putFile("/dev/root/vm/framework-server.log", 5_000, 1_500)
        fs.putFile("/dev/root/native-window/gpu-host.log", 5_000, 1_500)

        val snapshots = scanner.scan(layout)
        assertTrue(snapshots.all { it.files.isEmpty() })
    }

    @Test
    fun `stale active marker is closed with the last write time`() {
        fs.putText("/dev/root/vm/sessions/s2/meta", "started=1000\n")
        fs.putText("/dev/root/vm/sessions/s2/.active", "")
        fs.putFile("/dev/root/vm/sessions/s2/application-1-1.log", 10, 4_000)

        assertEquals(1, scanner.closeStale(layout, live = setOf(SessionId("other"))))
        assertFalse(fs.exists("/dev/root/vm/sessions/s2/.active"))
        assertTrue(fs.text("/dev/root/vm/sessions/s2/meta")!!.contains("ended=4000"))
        assertEquals(listOf("session_stale_closed"), sink.kinds())
    }

    @Test
    fun `live sessions are left alone by closeStale`() {
        fs.putText("/dev/root/vm/sessions/s3/meta", "started=1000\n")
        fs.putText("/dev/root/vm/sessions/s3/.active", "")
        scanner.closeStale(layout, live = setOf(SessionId("s3")))
        assertTrue(fs.exists("/dev/root/vm/sessions/s3/.active"))
    }
}
