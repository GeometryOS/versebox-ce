// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.runtime.limits

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * 主进程侧的客户端 + 结论的持久化落点。
 *
 * 四条纪律：
 * 1. 主进程**永不 fork**（fork 在 :phantom 里，见 PhantomProbeHost.kt）；
 * 2. startService() 是对 system_server 的同步 Binder 调用，只从 client 队列发出；
 * 3. SQLite 只从 client 队列碰（复用 RecordStore 的数据层），UI 线程不做 IO；
 * 4. **中间态不覆盖结论**：fork 中 / fork 失败这种没有判定的回传只推进 phase，
 *    不写 [Snapshot.stored] —— 否则首屏刚恢复的「上次结论」会被一句「正在启动子进程…」冲掉，
 *    首页卡片就变成了每次启动都显示未检测/正在检测。
 *
 * 状态语义见 [StoredLimit]：**只有「系统真的回收了子进程」是可置信证据**；自动检测的
 * 「未观测到」是 3 秒超时误判 —— 既不清抑制位、也不落盘、也不当跃迁基准。
 * 「不再提示」只在真正恢复（NotNeeded / 手动 Relaxed）时清除。
 */
object ChildProcessLimitMonitor {

    data class Snapshot(
        val stored: StoredLimit = StoredLimit(),
        val phase: ProbePhase = ProbePhase.Idle,
        val forked: Int = 0,
        /** 本次回传是否要求弹对话框（结论受限且未被「不再提示」抑制）。 */
        val alert: Boolean = false,
    ) {
        val limit: ChildProcessLimit get() = stored.limit
        val origin: LimitOrigin get() = stored.origin
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()
    val checking: Boolean get() = _state.value.phase.pending

    private val client = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phantom-client").apply { isDaemon = true }
    }

    @Volatile
    private var appContext: Context? = null

    /** 上一份**可置信**结论（受限 / 真解除），用来判断这次算不算新一轮限制。 */
    @Volatile
    private var previous: StoredLimit? = null

    /** 本次进程是否已经拿到过判定：拿到了就不再让磁盘里的旧值覆盖它。 */
    @Volatile
    private var hasVerdict = false

    /**
     * 本次进程是否已经弹过提示。
     *
     * 「每次进 App 检测到限制就弹一次」= 每次进入只弹一次：进二级页会顺手再跑一轮检测，
     * 同一次进入里不该连弹两张。出现真正解除（[StoredLimit.trulyNormal]）时复位。
     */
    @Volatile
    private var alerted = false

    /**
     * 绑定上下文并把**上次的结论读出来**（后台线程）。首屏显示的就是它，此时还没有发起检测。
     */
    fun attach(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        // 首屏立刻可见：先用同步镜像显示上次结论，再让 SQLite 在后台把它精确化。
        ChildProcessLimitStore.peek(app)?.let { cached ->
            previous = cached
            _state.value = _state.value.copy(stored = cached)
        }
        client.execute {
            val stored = ChildProcessLimitStore.load(app) ?: return@execute
            if (hasVerdict) return@execute
            previous = stored
            _state.value = _state.value.copy(stored = stored)
        }
    }


    /**
     * 发起一次检测。与点击卡片走的是**同一条**异步入口：只投递一条 Intent，立即返回。
     * [origin] 只影响结论的解读（自动的「未观测到」不算解除）。
     */
    fun start(origin: LimitOrigin): Boolean {
        val context = appContext ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val next = StoredLimit(
                limit = ChildProcessLimit.NotNeeded,
                origin = origin,
                at = System.currentTimeMillis(),
                muted = false,
            )
            hasVerdict = true
            _state.value = Snapshot(next, ProbePhase.Done)
            commit(next)
            return false
        }
        if (_state.value.phase.pending) return false
        // 只推进阶段：结论要等回传，检测期间卡片继续显示上次落盘的状态。
        _state.value = _state.value.copy(phase = ProbePhase.Forking, forked = 0)
        val callback = receiver(origin)
        client.execute { PhantomProbeService.start(context, callback) }
        return true
    }

    fun cancel() {
        val context = appContext ?: return
        if (_state.value.phase.pending) {
            _state.value = _state.value.copy(phase = ProbePhase.Done)
        }
        client.execute { PhantomProbeService.cancel(context) }
    }

    /** 用户点了「不再提示」：抑制到下次真正恢复正常为止。 */
    fun mute() {
        // 落盘的是「上一份可置信结论 + 抑制位」：当前视图可能只是自动检测的「未观测到」。
        val base = previous ?: _state.value.stored
        _state.value = _state.value.copy(
            stored = _state.value.stored.copy(muted = true),
            alert = false,
        )
        commit(base.copy(muted = true))
    }

    /** 对话框已经弹过：把 alert 清成一次性事件，重进页面/切 tab 不会再弹。 */
    fun consumeAlert() {
        if (_state.value.alert) _state.value = _state.value.copy(alert = false)
    }

    /** 记录跃迁基准并落盘（只在拿到判定时调用）。 */
    private fun commit(stored: StoredLimit) {
        previous = stored
        val app = appContext ?: return
        client.execute { ChildProcessLimitStore.save(app, stored) }
    }

    private fun receiver(origin: LimitOrigin): ResultReceiver =
        object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultData == null) return
                val phase = ProbePhase.entries.getOrElse(
                    resultData.getInt(PhantomProbeService.KEY_PHASE),
                ) { ProbePhase.Done }
                val forked = resultData.getInt(PhantomProbeService.KEY_FORKED)
                val detail = resultData.getString(PhantomProbeService.KEY_DETAIL).orEmpty()
                val limit = when {
                    resultData.getBoolean(PhantomProbeService.KEY_NOT_NEEDED) -> ChildProcessLimit.NotNeeded
                    resultData.getBoolean(PhantomProbeService.KEY_LIMITED) -> ChildProcessLimit.Limited(detail)
                    resultData.getBoolean(PhantomProbeService.KEY_OK) -> ChildProcessLimit.Relaxed(detail)
                    else -> ChildProcessLimit.Unknown(detail)
                }
                val verdict = limit is ChildProcessLimit.Limited ||
                    limit is ChildProcessLimit.Relaxed ||
                    limit is ChildProcessLimit.NotNeeded
                if (!verdict) {
                    // 中间态（正在 fork / fork 失败）：不落盘、不参与跃迁判定，也不动卡片结论。
                    _state.value = _state.value.copy(phase = phase, forked = forked)
                    return
                }
                val previousMuted = _state.value.stored.muted
                val next = StoredLimit(
                    limit = limit,
                    origin = origin,
                    at = System.currentTimeMillis(),
                    // 只有「真正恢复正常」才清抑制位；自动检测的 Relaxed 不算。
                    muted = if (limit is ChildProcessLimit.NotNeeded ||
                        (limit is ChildProcessLimit.Relaxed && origin == LimitOrigin.MANUAL)
                    ) {
                        false
                    } else {
                        previousMuted
                    },
                )
                hasVerdict = true
                val alert = next.shouldAlert(alerted) || _state.value.alert
                if (alert) alerted = true
                if (next.trulyNormal) alerted = false
                // 卡片照实显示本次观测（包括自动检测的「未观测到」）；
                // 但只有可置信结论（真受限 / 真解除）才落盘 —— 自动的「未观测到」是超时误判，
                // 拿它覆盖库里的结论会让下次启动的首屏显示一个不可信的「未见限制」。
                if (next.confident) commit(next)
                _state.value = Snapshot(next, phase, forked, alert)
            }
        }
}
