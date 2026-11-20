// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.audit.android

import android.content.Context
import me.kanadex.geometryos.audit.AuditEngine
import me.kanadex.geometryos.audit.DefaultAuditEngine
import me.kanadex.geometryos.audit.model.AuditEvent
import me.kanadex.geometryos.audit.model.AuditPolicy
import me.kanadex.geometryos.audit.port.AuditClock
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * 进程内审计引擎的单例装配（docs/log-audit/04 §2 的最后一项）。
 *
 * 为什么是单例 + 独立单线程：
 * 1. 触发点唯一（服务启动 / 会话结束 / 用户显式操作），但调用方可能在主线程、服务线程或
 *    worker 线程上，必须有一条自己的串行通道，避免两次 maintain 交叉删同一批文件；
 * 2. 这条通道不能跟着 Service 生命周期走：Service 被杀时清理还要能继续收尾；
 * 3. 所有 IO 都在这一条线程上，主线程永远等不到一次 6 GB 的 `du`。
 */
object AuditBootstrap {
    private const val TAG = "A10VM_AUDIT"
    private const val AUDIT_DIR = "audit"
    private const val AUDIT_LOG = "audit.log"

    private class Components(
        val fs: AndroidAuditFileSystem,
        val clock: AuditClock,
        val store: AuditPolicyStore,
        val sink: AuditEventFile,
        val engine: AuditEngine,
    )

    @Volatile private var components: Components? = null

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            // 首次维护可能要删掉几个 GB 的旧日志：降到 background 优先级，
            // 免得和客体开机抢 IO（这条线程永远不在主线程上）。
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "a10vm-audit")
    }.asCoroutineDispatcher()

    /** 审计自己的工作线程；`maintain` 内部还会再 `withContext(IO)`，两者不冲突。 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    fun engine(context: Context): AuditEngine = components(context).engine

    fun policy(context: Context): AuditPolicy = components(context).store.read()

    fun policyStore(context: Context): AuditPolicyStore = components(context).store

    fun fileSystem(context: Context): AndroidAuditFileSystem = components(context).fs

    /** 审计日志原文（最近 [maxBytes] 字节），给审计页/导出用。 */
    fun auditLogTail(context: Context, maxBytes: Long): String =
        components(context).sink.tail(maxBytes)

    /** 审计事件出口：清理之外的动作（.trash 删不掉、用户手动删记录）也要留痕。 */
    fun emit(context: Context, kind: String, fields: Map<String, Any>) {
        runCatching {
            components(context).sink.emit(AuditEvent(System.currentTimeMillis(), kind, fields))
        }
    }

    private fun components(context: Context): Components = components ?: synchronized(this) {
        components ?: create(context.applicationContext).also { components = it }
    }

    private fun create(context: Context): Components {
        val fs = AndroidAuditFileSystem()
        val clock = object : AuditClock {
            override fun now(): Long = System.currentTimeMillis()
        }
        val store = AuditPolicyStore(context)
        // 不在这里 mkdirs：AppState 是在组合期构造的，构造函数里做磁盘操作会卡首帧。
        // 目录由 AuditEventFile 第一次 appendText 时创建。
        val logPath = File(File(context.filesDir, AUDIT_DIR), AUDIT_LOG).absolutePath
        val sink = AuditEventFile(fs, clock, logPath) { store.read().auditLogBytesCap }
        val engine = DefaultAuditEngine(fs, clock, sink) { store.read() }
        return Components(fs, clock, store, sink, engine)
    }
}
