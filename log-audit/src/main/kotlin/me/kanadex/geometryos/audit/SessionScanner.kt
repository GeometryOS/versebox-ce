package me.kanadex.geometryos.audit

import me.kanadex.geometryos.audit.model.AuditEvent
import me.kanadex.geometryos.audit.model.AuditEventKind
import me.kanadex.geometryos.audit.model.SessionId
import me.kanadex.geometryos.audit.model.SessionFile
import me.kanadex.geometryos.audit.model.SessionRecord
import me.kanadex.geometryos.audit.model.SessionSnapshot
import me.kanadex.geometryos.audit.port.AuditClock
import me.kanadex.geometryos.audit.port.AuditEventSink
import me.kanadex.geometryos.audit.port.AuditFileSystem
import java.time.Instant
import java.time.ZoneOffset

/**
 * 运行期日志的会话台账与分组（docs/log-audit/02 §2.1、§2.3、04 §6）。
 *
 * 台账落在 `vm/sessions/<sessionId>/`：
 *
 * ```text
 * vm/sessions/<sessionId>/.active   会话进行中的锁标记（消失 = 已结束）
 * vm/sessions/<sessionId>/meta      started=<ms> / ended=<ms>
 * vm/sessions/<sessionId>/…       该会话的运行期日志（运行时将来直接写这里时的位置）
 * ```
 *
 * 为什么还要给旧文件分组：升级前 `application-<pid>-<seq>.log` 直接落在 `vm/` 下，
 * 名字里没有会话标识。这里用**台账里的精确时间窗**归属（[startedAt, endedAt]），
 * 不按天、不按文件数：一个文件只会落在它那次启动的窗口里，所以不会出现"把一次会话切一半"。
 * 落在任何窗口之前的文件归 `legacy-<yyyyMMdd>` 天桶，按"完整性未知"优先清理。
 *
 * 归属只覆盖 `application-*.log`：`framework-<role>.log` 是**每次启动截断重写**的，
 * `gpu-host.log` 是**追加写**的，它们始终属于最新一次会话，删旧会话时碰它们等于删掉正在用的日志。
 */
class SessionScanner(
    private val fs: AuditFileSystem,
    private val clock: AuditClock,
    private val sink: AuditEventSink,
) {
    /** 台账里"进行中"的锁标记文件名。 */
    private val activeMarker = ".active"
    private val metaName = "meta"

    /** 读会话台账；没有目录时返回空表。 */
    fun records(layout: DeviceLayout): List<SessionRecord> {
        if (!fs.isDirectory(layout.sessionsDir)) return emptyList()
        return fs.list(layout.sessionsDir)
            .filter { it.directory }
            .mapNotNull { entry ->
                val id = SessionId.of(entry.path.substringAfterLast('/'))
                val meta = parseMeta(entry.path)
                if (meta.startedAt <= 0L) return@mapNotNull null
                SessionRecord(id, meta.startedAt, meta.endedAt, fs.exists(join(entry.path, activeMarker)))
            }
            .sortedBy { it.startedAt }
    }

    /**
     * 把"标记为进行中、但服务里已经没有这个会话"的台账收尾。
     *
     * 应用/服务被杀时 `onSessionEnd` 不会执行，台账就会永远停在进行中，
     * 那些会话既不能淘汰也不能判定完整性 —— 这是所有"日志只增不减"事故的共同起点。
     */
    fun closeStale(layout: DeviceLayout, live: Set<SessionId>): Int {
        if (!fs.isDirectory(layout.sessionsDir)) return 0
        var closed = 0
        for (entry in fs.list(layout.sessionsDir).filter { it.directory }) {
            val id = SessionId.of(entry.path.substringAfterLast('/'))
            if (id in live) continue
            if (!fs.exists(join(entry.path, activeMarker))) continue
            val files = sessionFiles(entry.path)
            val lastWrite = files.maxOfOrNull { it.modifiedAt }
            val meta = parseMeta(entry.path)
            val fallback = lastWrite ?: clock.now()
            val startedAt = if (meta.startedAt > 0L) meta.startedAt else fallback
            val endedAt = (lastWrite ?: clock.now()).coerceAtLeast(startedAt)
            fs.writeText(
                join(entry.path, metaName),
                "started=$startedAt\nended=$endedAt\n",
            )
            fs.delete(join(entry.path, activeMarker))
            closed++
            sink.emit(
                AuditEvent(
                    at = clock.now(),
                    kind = AuditEventKind.SESSION_STALE_CLOSED,
                    fields = mapOf(
                        "vm" to layout.vmId,
                        "session" to id.value,
                        "ended" to endedAt,
                        "files" to files.size.toLong(),
                    ),
                ),
            )
        }
        return closed
    }

    /** 当前所有会话组（含 legacy 天桶）的快照。 */
    fun scan(layout: DeviceLayout): List<SessionSnapshot> {
        val records = records(layout)
        val result = ArrayList<SessionSnapshot>()
        val attributed = HashMap<SessionId, MutableList<SessionFile>>()
        for (record in records) {
            val dir = join(layout.sessionsDir, record.id.value)
            val files = sessionFiles(dir)
            val snapshot = SessionSnapshot(
                id = record.id,
                active = record.active,
                legacy = false,
                startedAt = record.startedAt,
                endedAt = record.endedAt,
                groupDir = dir,
                files = files,
            )
            result += snapshot
        }
        // 旧布局：application-*.log 落在 runtimeDir 下，按台账时间窗归属。
        val legacyBuckets = LinkedHashMap<String, MutableList<SessionFile>>()
        val windows = records.sortedBy { it.startedAt }
        for (entry in looseLogs(layout.runtimeDir)) {
            val owner = windows.lastOrNull { record ->
                entry.modifiedAt >= record.startedAt &&
                    entry.modifiedAt <= (record.endedAt ?: Long.MAX_VALUE)
            }
            if (owner != null) {
                attributed.getOrPut(owner.id) { ArrayList() } += entry
            } else {
                val key = dayKey(entry.modifiedAt)
                legacyBuckets.getOrPut(key) { ArrayList() } += entry
            }
        }
        for ((id, files) in attributed) {
            val index = result.indexOfFirst { it.id == id }
            if (index >= 0) {
                val snapshot = result[index]
                result[index] = snapshot.copy(files = snapshot.files + files)
            }
        }
        for ((key, files) in legacyBuckets) {
            result += SessionSnapshot(
                id = SessionId.legacyBucket(key),
                active = false,
                legacy = true,
                startedAt = files.minOf { it.modifiedAt },
                endedAt = files.maxOf { it.modifiedAt },
                groupDir = null,
                files = files,
            )
        }
        return result
    }

    /** 会话目录里的日志文件（排除台账自身的 meta / .active）。 */
    private fun sessionFiles(dir: String): List<SessionFile> =
        fs.walkFiles(dir)
            .filter { it.path.substringAfterLast('/') != metaName }
            .filter { it.path.substringAfterLast('/') != activeMarker }
            .map { SessionFile(it.path, it.bytes, it.modifiedAt) }

    /** runtimeDir 下不带会话标识的旧日志。 */
    private fun looseLogs(runtimeDir: String): List<SessionFile> =
        fs.list(runtimeDir)
            .filter { !it.directory }
            .filter { entry ->
                val name = entry.path.substringAfterLast('/')
                name.startsWith("application-") && name.endsWith(".log")
            }
            .map { SessionFile(it.path, it.bytes, it.modifiedAt) }

    private data class Meta(val startedAt: Long, val endedAt: Long?)

    private fun parseMeta(dir: String): Meta {
        val text = fs.readText(join(dir, metaName), 4096)
        var started = 0L
        var ended: Long? = null
        for (line in text.lineSequence()) {
            val key = line.substringBefore('=', "")
            val value = line.substringAfter('=', "").trim().toLongOrNull() ?: continue
            when (key) {
                "started" -> started = value
                "ended" -> ended = value
            }
        }
        return Meta(started, ended)
    }

    private fun dayKey(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun join(base: String, name: String): String =
        if (base.endsWith("/")) base + name else "$base/$name"
}

/** 会话台账的写入侧：与 [SessionScanner] 用同一份路径与格式。 */
class SessionLedger(
    private val fs: AuditFileSystem,
    private val clock: AuditClock,
) {
    fun begin(layout: DeviceLayout, session: SessionId): Unit {
        val dir = dir(layout, session)
        fs.makeDirectories(dir)
        fs.writeText("$dir/meta", "started=${clock.now()}\n")
        fs.writeText("$dir/.active", "")
    }

    fun end(layout: DeviceLayout, session: SessionId): Unit {
        val dir = dir(layout, session)
        if (!fs.isDirectory(dir)) return
        val text = fs.readText("$dir/meta", 4096)
        val started = startedAt(text) ?: clock.now()
        fs.writeText("$dir/meta", "started=$started\nended=${clock.now()}\n")
        fs.delete("$dir/.active")
    }

    private fun startedAt(text: String): Long? = text.lineSequence()
        .firstOrNull { it.startsWith("started=") }
        ?.substringAfter('=')
        ?.trim()
        ?.toLongOrNull()

    private fun dir(layout: DeviceLayout, session: SessionId): String =
        "${layout.sessionsDir}/${session.value}"
}
