package me.kanadex.geometryos.audit

import me.kanadex.geometryos.audit.model.ArchiveEntry
import me.kanadex.geometryos.audit.model.AuditEvent
import me.kanadex.geometryos.audit.model.AuditEventKind
import me.kanadex.geometryos.audit.model.AuditPolicy
import me.kanadex.geometryos.audit.model.AuditState
import me.kanadex.geometryos.audit.model.SessionId
import me.kanadex.geometryos.audit.port.AuditClock
import me.kanadex.geometryos.audit.port.AuditEventSink
import me.kanadex.geometryos.audit.port.AuditFileSystem
import me.kanadex.geometryos.audit.policy.ArchiveRetention
import me.kanadex.geometryos.audit.policy.SessionRetention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 谁触发了这次维护（唯一触发点见 docs/log-audit/04 §5）。 */
enum class AuditTrigger {
    SERVICE_START,
    SESSION_BEGIN,
    SESSION_END,
    USER_REQUEST,
}

/** 一次维护的结果；`deletedRecords` 由 :app 用来删索引行（本模块不认识数据库）。 */
data class MaintenanceReport(
    val sessionBytes: Long = 0,
    val archiveBytes: Long = 0,
    val protectedCount: Int = 0,
    val pendingCleanup: Boolean = false,
    val freedSessionBytes: Long = 0,
    val freedArchiveBytes: Long = 0,
    val truncatedWindowLogs: Int = 0,
    val deletedRecords: List<Long> = emptyList(),
    val trigger: AuditTrigger = AuditTrigger.USER_REQUEST,
)

/**
 * 对外唯一入口（docs/log-audit/04 §3）。
 *
 * UI、Service、RecordCollector 都只能看到这 6 个方法 + 1 个状态流，
 * 拿不到任何"删文件"的入口：谁能删由 policy 决定，怎么删由 port 实现决定。
 */
interface AuditEngine {
    /** 容量视图唯一真相：记录页 / 设置页 / 审计页都读它。 */
    val state: StateFlow<AuditState>

    suspend fun onSessionBegin(layout: DeviceLayout, session: SessionId)

    suspend fun onSessionEnd(layout: DeviceLayout, session: SessionId)

    /**
     * 唯一自动清理入口。
     *
     * @param devices 需要维护的虚拟机布局
     * @param archives 归档索引（由 :app 从 records.db 读出；本模块不碰数据库）
     * @param live 服务里仍然活着的会话 id：它们永不淘汰
     */
    suspend fun maintain(
        trigger: AuditTrigger,
        devices: List<DeviceLayout>,
        archives: List<ArchiveEntry>,
        live: Set<SessionId> = emptySet(),
    ): MaintenanceReport

    suspend fun archive(request: ArchiveRequest): ArchiveResult

    /** 用户显式确认后的归档清理；`confirm = false` 只重新算一遍状态，不删。 */
    suspend fun cleanupSuccessArchives(confirm: Boolean, archives: List<ArchiveEntry>): ArchiveSweepReport
}

/**
 * 默认实现：所有决策走 policy，所有 IO 走 [AuditFileSystem]。
 *
 * 构造参数里只有 port 与"读一次策略"的 lambda，没有 Context、没有 Log，
 * 因此这一整个类都能在 JVM 单测里跑（见 log-audit/src/test）。
 */
class DefaultAuditEngine(
    private val fs: AuditFileSystem,
    private val clock: AuditClock,
    private val sink: AuditEventSink,
    private val policyProvider: () -> AuditPolicy,
) : AuditEngine {
    private val scanner = SessionScanner(fs, clock, sink)
    private val ledger = SessionLedger(fs, clock)
    private val sessionJanitor = SessionJanitor(fs, clock, sink)
    private val archiveJanitor = ArchiveJanitor(fs, clock, sink)
    private val writer = ArchiveWriter(fs, clock, sink)

    private val _state = MutableStateFlow(AuditState())
    override val state: StateFlow<AuditState> = _state.asStateFlow()

    override suspend fun onSessionBegin(layout: DeviceLayout, session: SessionId) =
        withContext(Dispatchers.IO) {
            val policy = currentPolicy()
            ledger.begin(layout, session)
            truncateWindowLog(layout, policy)
            _state.value = _state.value.copy(policy = policy)
        }

    override suspend fun onSessionEnd(layout: DeviceLayout, session: SessionId) =
        withContext(Dispatchers.IO) {
            ledger.end(layout, session)
        }

    override suspend fun maintain(
        trigger: AuditTrigger,
        devices: List<DeviceLayout>,
        archives: List<ArchiveEntry>,
        live: Set<SessionId>,
    ): MaintenanceReport = withContext(Dispatchers.IO) {
        val policy = currentPolicy()
        var sessionBytes = 0L
        var freedSessions = 0L
        var truncated = 0
        for (layout in devices) {
            scanner.closeStale(layout, live)
            val snapshots = scanner.scan(layout)
            sessionBytes += snapshots.sumOf { it.bytes }
            val plan = SessionRetention.plan(snapshots, policy)
            if (plan.evictions.isNotEmpty()) {
                val report = sessionJanitor.sweep(plan)
                freedSessions += report.freedBytes
            }
            if (truncateWindowLog(layout, policy)) truncated++
        }
        val archivePlan = ArchiveRetention.plan(archives, policy)
        val archiveReport = archiveJanitor.sweep(archives, archivePlan, userConfirmed = false)
        val archiveBytes = archiveReport.retainedBytes
        _state.value = AuditState(
            sessionBytes = (sessionBytes - freedSessions).coerceAtLeast(0L),
            archiveBytes = archiveBytes,
            protectedCount = archiveReport.protectedCount,
            pendingCleanup = archiveReport.pendingUser,
            lastMaintainedAt = clock.now(),
            policy = policy,
        )
        MaintenanceReport(
            sessionBytes = _state.value.sessionBytes,
            archiveBytes = archiveBytes,
            protectedCount = archiveReport.protectedCount,
            pendingCleanup = archiveReport.pendingUser,
            freedSessionBytes = freedSessions,
            freedArchiveBytes = archiveReport.freedBytes,
            truncatedWindowLogs = truncated,
            deletedRecords = archiveReport.deletedRecords,
            trigger = trigger,
        )
    }

    override suspend fun archive(request: ArchiveRequest): ArchiveResult = withContext(Dispatchers.IO) {
        val policy = currentPolicy()
        val result = writer.write(request, policy)
        if (result is ArchiveResult.Failed) {
            sink.emit(
                AuditEvent(
                    at = clock.now(),
                    kind = AuditEventKind.ARCHIVE_FAILED,
                    fields = mapOf(
                        "vm" to request.vmId,
                        "record" to request.recordId,
                        "message" to result.message.take(160),
                    ),
                ),
            )
        }
        result
    }

    override suspend fun cleanupSuccessArchives(
        confirm: Boolean,
        archives: List<ArchiveEntry>,
    ): ArchiveSweepReport = withContext(Dispatchers.IO) {
        val policy = currentPolicy()
        val plan = ArchiveRetention.plan(archives, policy)
        val report = archiveJanitor.sweep(archives, plan, userConfirmed = confirm)
        _state.value = _state.value.copy(
            archiveBytes = report.retainedBytes,
            protectedCount = report.protectedCount,
            pendingCleanup = report.pendingUser,
            lastMaintainedAt = clock.now(),
            policy = policy,
        )
        report
    }

    /**
     * `native-window/gpu-host.log` 只追加写、跨会话累积（01 §1、05 阶段 3）。
     * 每次会话开始时截断到 [AuditPolicy.windowLogTailBytes]，返回是否真的截断过。
     */
    private fun truncateWindowLog(layout: DeviceLayout, policy: AuditPolicy): Boolean {
        val path = layout.windowLogPath
        if (path.isEmpty() || !fs.exists(path)) return false
        if (fs.size(path) <= policy.windowLogTailBytes) return false
        val kept = runCatching { fs.truncateToTail(path, policy.windowLogTailBytes) }.getOrElse { return false }
        sink.emit(
            AuditEvent(
                at = clock.now(),
                kind = AuditEventKind.WINDOW_LOG_TRUNCATED,
                fields = mapOf("vm" to layout.vmId, "kept" to kept),
            ),
        )
        return true
    }

    private fun currentPolicy(): AuditPolicy {
        val policy = runCatching { policyProvider() }.getOrElse { AuditPolicy.Default }
        return policy.normalized()
    }
}
