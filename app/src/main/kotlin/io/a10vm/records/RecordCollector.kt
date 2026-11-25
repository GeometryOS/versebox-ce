package io.a10vm.records

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.util.Log
import me.kanadex.geometryos.audit.ArchiveRequest
import me.kanadex.geometryos.audit.ArchiveResult
import me.kanadex.geometryos.audit.model.ArchiveOutcome
import me.kanadex.geometryos.audit.policy.ScanRoot
import me.kanadex.geometryos.audit.android.AuditBootstrap
import me.kanadex.geometryos.audit.android.AuditIndex
import me.kanadex.geometryos.audit.model.SessionId
import io.a10vm.runtime.RomSpec
import io.a10vm.runtime.VmDevice
import io.a10vm.runtime.VmRegistry
import io.a10vm.ui.settings.AppPreferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 把一次操作「归档成一条记录」的采集器。
 *
 * 职责边界（docs/log-audit/04 §3）：
 * 1. 开始时写一条 RUNNING 索引（[begin]）；
 * 2. 结束时只组装 [me.kanadex.geometryos.audit.ArchiveRequest] 交给 `AuditEngine.archive`，
 *    白名单、2 MB 取尾、12 MB 预算、manifest.json、原子 rename 全部在 `:log-audit` 里；
 * 3. 全程只读运行时的日志，绝不篡改 vm 目录下任何日志文件。
 *
 * 旧实现把 `wanted()` / `MAX_FILE_BYTES` / `MAX_TOTAL_BYTES` / `archiveLogs()` 写在这里，
 * 于是"超过 2 MB 就整份跳过、超预算静默丢弃"成了产品行为（01 §4 P0-2）。
 * 这些现在已经删掉：阈值只允许存在于 `AuditPolicy`。
 */
object RecordCollector {
    private const val TAG = "A10VM_RECORDS"
    private const val MAX_RUNTIME_OPERATION_LOG_BYTES = 512 * 1024
    private val operationSequences = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger>()
    private val pendingMetaArchives = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<Job>>()

    suspend fun awaitOperationArchives(vmId: String) {
        val queue = pendingMetaArchives[vmId] ?: return
        while (true) {
            val job = queue.poll() ?: break
            job.join()
        }
        if (queue.isEmpty()) pendingMetaArchives.remove(vmId, queue)
    }

    private fun runtimeOperationFile(context: Context, device: VmDevice): File =
        File(VmRegistry.get(context).runtimeOf(device), "operation.log")

    private fun writeOperationEvent(
        file: File,
        recordId: Long,
        sequence: Int,
        stage: String,
        event: String,
        message: String,
        failure: Throwable? = null,
    ) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() >= MAX_RUNTIME_OPERATION_LOG_BYTES) return
            val json = JSONObject()
                .put("schema", "versebox-operation-log/1")
                .put("operationId", recordId.toString())
                .put("recordId", recordId)
                .put("sequence", sequence)
                .put("timestamp", System.currentTimeMillis())
                .put("level", if (failure == null) "INFO" else "ERROR")
                .put("stage", stage)
                .put("event", event)
                .put("message", message.take(MAX_EVENT_TEXT))
            failure?.let { json.put("stackTrace", it.stackTraceToString().take(MAX_STACK_TEXT)) }
            file.appendText(json.toString() + "\n", Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "操作日志写入失败 record=$recordId stage=$stage", it) }
    }

    fun appendOperation(
        context: Context,
        recordId: Long?,
        device: VmDevice,
        stage: String,
        event: String,
        message: String,
        failure: Throwable? = null,
    ) {
        val id = recordId?.takeIf { it > 0L } ?: return
        val sequence = operationSequences[id]?.getAndIncrement() ?: return
        writeOperationEvent(runtimeOperationFile(context, device), id, sequence, stage, event, message, failure)
    }

    /** 记录总开关。键名唯一来源在 AppPreferences.KEY_COLLECT_RECORDS。 */
    private fun enabled(context: Context): Boolean = context
        .getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        .getBoolean(AppPreferences.KEY_COLLECT_RECORDS, true)

    fun begin(context: Context, device: VmDevice, rom: RomSpec?, kind: RecordKind): Long? = runCatching {
        if (!enabled(context)) return@runCatching null
        val store = RecordStore.get(context)
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val values = ContentValues().apply {
            put("vm_id", device.id)
            put("vm_name", device.name)
            put("rom_id", device.romId)
            put("rom_name", rom?.name)
            put("android_version", rom?.androidVersion)
            put("api_level", rom?.apiLevel ?: 0)
            put("kind", kind.name)
            put("status", RecordStatus.RUNNING.name)
            put("started_at", System.currentTimeMillis())
            put("app_version", "${info.versionName} (${info.longVersionCode})")
            put("host_release", "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            put("host_model", "${Build.MANUFACTURER} ${Build.MODEL}")
        }
        val id = store.insert(values)
        if (id > 0L) {
            operationSequences[id] = java.util.concurrent.atomic.AtomicInteger(1)
            runCatching {
                val operationFile = runtimeOperationFile(context, device)
                operationFile.parentFile?.mkdirs()
                operationFile.writeText("", Charsets.UTF_8)
                writeOperationEvent(operationFile, id, 0, kind.name, "accepted", "操作已登记")
            }.onFailure { Log.w(TAG, "操作日志初始化失败 record=$id", it) }
        }
        Log.i(TAG, "RECORD_BEGIN id=$id vm=${device.id} kind=$kind")
        id.takeIf { it > 0 }
    }.onFailure { Log.w(TAG, "RECORD_BEGIN 失败：${it.message}") }.getOrNull()

    /**
     * 写一条「没有客体日志」的记录（创建虚拟机、ROM 导入 / 删除）。
     *
     * 为什么一次写完而不是 begin + finish：这些动作在返回时成败已经确定，没有中间态；
     * 而且它们**不归档**（没有 vm/ 日志可收），只写索引行。归档只服务「能落出日志的运行过程」，
     * 见 docs/log-audit/04 §3。
     *
     * 状态由调用方给：**不许用界面状态反推**（见 2026-11-06 的两条检讨）。
     */
    fun recordMeta(
        context: Context,
        vmId: String,
        vmName: String,
        romId: String,
        romName: String?,
        apiLevel: Int,
        kind: RecordKind,
        status: RecordStatus,
        detail: String,
        startedAt: Long = System.currentTimeMillis(),
        operationEvents: List<String> = emptyList(),
        failure: Throwable? = null,
    ) {
        if (!enabled(context)) return
        runCatching {
            val store = RecordStore.get(context)
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val now = System.currentTimeMillis()
            val headline = detail.lineSequence().firstOrNull { it.isNotBlank() }?.take(160)
            val values = ContentValues().apply {
                put("vm_id", vmId)
                put("vm_name", vmName)
                put("rom_id", romId)
                put("rom_name", romName)
                put("api_level", apiLevel)
                put("kind", kind.name)
                put("status", status.name)
                put("failure", if (status == RecordStatus.FAILED) headline ?: "未给出原因" else null)
                put("summary", if (status == RecordStatus.FAILED) null else headline)
                put("started_at", startedAt)
                put("finished_at", now)
                put("duration_ms", (now - startedAt).coerceAtLeast(0))
                put("app_version", info.versionName + " (" + info.longVersionCode + ")")
                put("host_release", "Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT)
                put("host_model", Build.MANUFACTURER + " " + Build.MODEL)
                put("event_count", operationEvents.size + 3)
            }
            val id = store.insert(values)
            if (id > 0L) {
                val job = AuditBootstrap.scope.launch(Dispatchers.IO) {
                    archiveMetaOperation(context.applicationContext, id, vmId, kind, status, startedAt, detail, operationEvents, failure)
                }
                pendingMetaArchives.getOrPut(vmId) { java.util.concurrent.ConcurrentLinkedQueue() }.add(job)
            }
            Log.i(TAG, "RECORD_META id=$id vm=$vmId kind=$kind status=$status")
        }.onFailure { Log.w(TAG, "RECORD_META 失败：${it.message}") }
    }

    private suspend fun archiveMetaOperation(
        context: Context,
        id: Long,
        vmId: String,
        kind: RecordKind,
        status: RecordStatus,
        startedAt: Long,
        detail: String,
        events: List<String>,
        failure: Throwable?,
    ) {
        val recordsDir = File(context.filesDir, "records")
        val source = File(recordsDir, ".operation-$id")
        val target = File(recordsDir, archivePart(vmId) + "/" + id)
        try {
            source.deleteRecursively()
            check(source.mkdirs()) { "无法创建操作日志目录" }
            val device = VmRegistry.get(context).find(vmId)
            val runtimeLog = device?.let { runtimeOperationFile(context, it) }
            runtimeLog?.takeIf { it.isFile }?.copyTo(File(source, "operation.log"), overwrite = true)
            val log = File(source, "operation.log")
            if (!log.isFile) log.createNewFile()
            val runtimeLines = log.readLines(Charsets.UTF_8)
            val runtimeEvents = runtimeLines.size
            if (runtimeLines.isEmpty()) {
                writeOperationEvent(log, id, 0, kind.name, "accepted", "操作已登记")
            }
            log.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                var sequence = if (runtimeLines.isEmpty()) 1 else runtimeEvents
                fun event(name: String, message: String, stack: String? = null) {
                    val json = JSONObject().put("schema", "versebox-operation-log/1")
                        .put("operationId", id.toString()).put("recordId", id)
                        .put("sequence", sequence++).put("timestamp", System.currentTimeMillis())
                        .put("level", if (name == "exception") "ERROR" else "INFO")
                        .put("stage", kind.name).put("event", name).put("message", message.take(MAX_EVENT_TEXT))
                    if (stack != null) json.put("stackTrace", stack.take(MAX_STACK_TEXT))
                    writer.appendLine(json.toString())
                }
                if (runtimeEvents == 0) event("accepted", "操作已登记")
                events.forEach { event("step", it) }
                event(if (failure == null) "completed" else "failed", detail)
                failure?.let { event("exception", it.message ?: it.javaClass.name, it.stackTraceToString()) }
            }
            val result = AuditBootstrap.engine(context).archive(
                ArchiveRequest(
                    vmId = vmId,
                    recordId = id,
                    sessionId = SessionId.Legacy,
                    kind = kind.name,
                    status = status.name,
                    outcome = AuditIndex.outcome(status, kind),
                    targetDir = target.absolutePath,
                    roots = listOf(ScanRoot(source.absolutePath, "")) + listOfNotNull(
                        VmRegistry.get(context).find(vmId)?.let { vm ->
                            File(AuditIndex.layout(context, vm).runtimeDir, "operation.log")
                        }?.takeIf { it.isFile }?.let { ScanRoot(it.parentFile!!.absolutePath, "runtime/") },
                    ),
                    expected = listOf("operation.log"),
                ),
            )
            when (result) {
                is ArchiveResult.Success -> {
                    RecordStore.get(context).update(id, ContentValues().apply {
                        put("log_dir", relativeToFiles(context, target.absolutePath))
                        put("event_count", runtimeEvents + events.size + 2 + if (failure == null) 0 else 1)
                    })
                    AuditBootstrap.emit(context, "operation_archive_written", mapOf("record" to id, "kind" to kind.name))
                }
                is ArchiveResult.Failed -> {
                    AuditBootstrap.emit(context, "operation_archive_failed", mapOf("record" to id, "reason" to result.message))
                    Log.w(TAG, "操作日志归档失败 id=$id：${result.message}")
                }
            }
        } catch (problem: Throwable) {
            AuditBootstrap.emit(context, "operation_archive_failed", mapOf("record" to id, "reason" to (problem.message ?: problem.javaClass.simpleName)))
            Log.w(TAG, "操作日志归档失败 id=$id", problem)
        } finally {
            source.deleteRecursively()
        }
    }

    private fun archivePart(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "unknown" }

    private const val MAX_EVENT_TEXT = 8 * 1024
    private const val MAX_STACK_TEXT = 128 * 1024
    /**
     * 收尾：归档 + 摘要 + 索引。
     *
     * @param session 本次运行所属的会话；没有会话的动作（改名等）用 [SessionId.Legacy]。
     */
    suspend fun finish(
        context: Context,
        recordId: Long?,
        device: VmDevice,
        kind: RecordKind,
        status: RecordStatus,
        detail: String,
        session: SessionId = SessionId.Legacy,
    ) {
        if (recordId == null || recordId <= 0) return
        runCatching {
            val store = RecordStore.get(context)
            val record = store.find(recordId) ?: return@runCatching
            appendOperation(context, recordId, device, "operation", status.name.lowercase(), detail)
            val request = AuditIndex.archiveRequest(context, device, recordId, session, kind, status)
            val result = AuditBootstrap.engine(context).archive(request)
            val archiveFailure = (result as? ArchiveResult.Failed)?.message
            val digest = readDigest(File(request.targetDir))
            val failure = if (status == RecordStatus.FAILED) {
                digest.failure ?: detail.lineSequence().firstOrNull()?.take(160) ?: "未给出原因"
            } else {
                null
            }
            val summary = if (status == RecordStatus.FAILED) {
                null
            } else {
                val head = detail.lineSequence().firstOrNull()?.take(160).orEmpty()
                val base = when {
                    head.isNotBlank() -> head
                    digest.complete -> "${kind.label}完成"
                    digest.lastEvent != null -> "${kind.label}完成，但未走完启动：${digest.lastEvent}"
                    else -> "${kind.label}完成"
                }
                // 归档失败不改写操作状态：那会把"启动成功"说成"启动失败"，
                // 让记录列表说谎、也盖掉真正的失败原因。把结论写进这一行里。
                if (archiveFailure != null) "$base（归档失败：$archiveFailure）" else base
            }
            val now = System.currentTimeMillis()
            store.update(recordId, ContentValues().apply {
                put("status", status.name)
                put("failure", failure)
                put("summary", summary)
                put("finished_at", now)
                put("duration_ms", (now - record.startedAt).coerceAtLeast(0))
                put("log_dir", relativeToFiles(context, request.targetDir))
                put("event_count", digest.count)
            })
            Log.i(
                TAG,
                "RECORD_END id=$recordId vm=${device.id} kind=$kind status=$status " +
                    "files=${digest.count} archive=${if (archiveFailure == null) "ok" else archiveFailure}",
            )
            operationSequences.remove(recordId)
        }.onFailure { Log.w(TAG, "RECORD_END 失败：${it.message}") }
    }

    /** 记录被删除时顺带清掉它的归档目录（索引与文件不同步会越长越胖）。 */
    fun deleteArchives(context: Context, records: List<VmRecord>) {
        for (record in records) {
            val dir = record.logDir ?: continue
            runCatching { File(context.filesDir, dir).deleteRecursively() }
            AuditBootstrap.emit(
                context,
                "archive_user_delete",
                mapOf("record" to record.id, "vm" to record.vmId),
            )
        }
    }

    private fun relativeToFiles(context: Context, absolute: String): String? =
        runCatching { File(absolute).relativeTo(context.filesDir).path }.getOrNull()

    private data class Digest(val failure: String?, val lastEvent: String?, val complete: Boolean, val count: Int)

    private fun readDigest(archive: File): Digest {
        val file = archive.walkTopDown().firstOrNull { it.isFile && it.name == "startup-events.log" }
            ?: return Digest(null, null, false, 0)
        val lines = LogDigest.parse(runCatching { file.readText() }.getOrDefault(""))
        return Digest(
            failure = LogDigest.failure(lines),
            lastEvent = LogDigest.lastEvent(lines),
            complete = LogDigest.bootComplete(lines),
            count = lines.size,
        )
    }
}
