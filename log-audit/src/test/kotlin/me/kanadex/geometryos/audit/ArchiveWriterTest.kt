package me.kanadex.geometryos.audit

import me.kanadex.geometryos.audit.json.ManifestJson
import me.kanadex.geometryos.audit.model.ArchiveOutcome
import me.kanadex.geometryos.audit.model.AuditPolicy
import me.kanadex.geometryos.audit.model.SessionId
import me.kanadex.geometryos.audit.policy.ScanRoot
import me.kanadex.geometryos.audit.testing.FakeFileSystem
import me.kanadex.geometryos.audit.testing.RecordingSink
import me.kanadex.geometryos.audit.testing.TestClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveWriterTest {
    private val fs = FakeFileSystem()
    private val sink = RecordingSink()
    private val clock = TestClock(1_762_300_000_000L)
    private val writer = ArchiveWriter(fs, clock, sink)
    private val policy = AuditPolicy()

    private fun request(recordId: Long = 73) = ArchiveRequest(
        vmId = "1793740665036",
        recordId = recordId,
        sessionId = SessionId("m4x1-abcdef12"),
        kind = "CRASH",
        status = "FAILED",
        outcome = ArchiveOutcome.PROTECTED,
        targetDir = "/records/1793740665036/$recordId",
        roots = listOf(
            ScanRoot("/dev/root", ""),
            ScanRoot("/dev/root/vm", "vm/"),
            ScanRoot("/dev/root/native-window", "native-window/"),
        ),
    )

    private fun seed() {
        fs.putText("/dev/root/startup-events.log", "[000.10s] BOOT   Android 15 startup", 10)
        fs.putText("/dev/root/session-state.log", "state=ERROR", 11)
        fs.putFile("/dev/root/vm/framework-server.log", 4L * 1024 * 1024, 12)
        fs.putFile("/dev/root/vm/application-8891-1.log", 1024, 13)
        fs.putFile("/dev/root/native-window/gpu-host.log", 4_600, 14)
    }

    @Test
    fun `writes manifest plus tail of the oversized framework log, atomically`() {
        seed()
        val result = writer.write(request(), policy)
        assertTrue(result is ArchiveResult.Success)
        val manifest = (result as ArchiveResult.Success).manifest

        assertNotNull(fs.text("/records/1793740665036/73/manifest.json"))
        assertEquals(2L * 1024 * 1024, fs.size("/records/1793740665036/73/vm/framework-server.log"))
        // 白名单之外的东西不许进归档
        assertNull(fs.text("/records/1793740665036/73/vm/application-8891-1.log"))
        // 临时目录必须已经消失
        assertFalse(fs.isDirectory("/records/1793740665036/.tmp-73"))

        val framework = manifest.files.single { it.path == "vm/framework-server.log" }
        assertEquals("tail@2MB", framework.truncated)
        assertEquals(4L * 1024 * 1024, framework.bytes)
        assertFalse(manifest.complete)

        // 清单能原样读回，且"完整性"结论一致
        val decoded = ManifestJson.decode(fs.text("/records/1793740665036/73/manifest.json")!!)
        assertNotNull(decoded)
        assertEquals(manifest, decoded)
    }

    @Test
    fun `rename failure leaves no half snapshot`() {
        seed()
        fs.failRename = true
        val result = writer.write(request(recordId = 74), policy)
        assertTrue(result is ArchiveResult.Failed)
        assertFalse(fs.isDirectory("/records/1793740665036/.tmp-74"))
        assertFalse(fs.isDirectory("/records/1793740665036/74"))
    }

    @Test
    fun `missing startup log is reported instead of silently absent`() {
        fs.putFile("/dev/root/vm/framework-server.log", 10, 12)
        val result = writer.write(request(recordId = 75), policy) as ArchiveResult.Success
        assertEquals("not_created", result.manifest.missing.single { it.path == "startup-events.log" }.reason)
        assertFalse(result.manifest.complete)
    }
}
