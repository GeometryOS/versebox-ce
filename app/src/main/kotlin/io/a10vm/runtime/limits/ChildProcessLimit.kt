// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.runtime.limits

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 子进程限制检测 —— **与 demo 完全同构**，没有任何自创进程/服务/通道。
 *
 * 时序（demo 的 `RHelper` 原样搬过来）：
 * 1. 建握手文件 `<dataDir>/receivedBroad`（先删再创建）；
 * 2. 一个普通后台线程里连续 fork [FORK_COUNT] 个子进程，子进程 `for(;;) sleep(100)` 永不退出；
 * 3. 发一条显式广播到 `:probe` 进程的 [PhantomProbeReceiver] —— 这一步会拉起第二个应用进程，
 *    AMS 正是在应用进程数变化时才去清点 phantom process（实测：不做这步 22 秒内一条 trim 都没有）；
 * 4. 看门狗线程：sleep 3 秒后先发"乐观结论"（未受限），再持续轮询回收；
 * 5. 命中自己的 pid → 改判"受限"。收尾 SIGKILL 全部子进程并回收。
 *
 * 线程纪律（这就是 demo 不卡的原因）：点击只做 `Thread.start()`；
 * fork、等待、sleep 全部在那条线程里，页面一个字节的状态都不改。
 */
object ChildProcessLimitProbe {

    private const val TAG = "PhantomProbe"

    /** AOSP 默认上限，仅用于文案。 */
    const val SYSTEM_CAP = 32

    /** demo 里的 66：明显高于任何已知上限（ROM 可能改成 20/32/64）。 */
    const val FORK_COUNT = 66

    /** demo：`Thread.sleep(3000)` 之后才给乐观结论。 */
    const val SETTLE_MILLIS = 3_000L

    /** 之后继续盯多久。 */
    const val OBSERVE_MILLIS = 60_000L

    private const val POLL_MILLIS = 200L

    private val lock = Any()
    private var worker: Thread? = null
    private var pids: MutableList<Int>? = null
    private var cancelled = false

    val running: Boolean get() = synchronized(lock) { worker != null }

    /**
     * 开始检测。**立即返回**：只创建一个线程，没有任何 Binder 调用、没有 join、没有 sleep。
     * [onUpdate] 在后台线程被多次调用（先 Forking，再乐观 Relaxed，最后可能升级 Limited）。
     */
    fun start(context: Context?, onUpdate: (ProbeUpdate) -> Unit): Boolean {
        synchronized(lock) {
            if (worker != null) return false
            cancelled = false
            val state = mutableListOf<Int>()
            pids = state
            val app = context?.applicationContext
            val thread = Thread({ observe(app, state, onUpdate) }, "phanom-probe")
            worker = thread
            thread.start()
            return true
        }
    }

    fun cancel() {
        val thread: Thread?
        val state: MutableList<Int>?
        synchronized(lock) {
            cancelled = true
            thread = worker
            state = pids
        }
        state?.let { list ->
            val snapshot = synchronized(list) { list.toList() }
            snapshot.forEach { PhantomProcessNative.killPid(it) }
        }
        thread?.interrupt()
    }

    fun notNeeded(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    private fun observe(
        context: Context?,
        state: MutableList<Int>,
        emit: (ProbeUpdate) -> Unit,
    ) {
        var forked = 0
        try {
            emit(ProbeUpdate(ProbePhase.Forking, 0, ChildProcessLimit.Unknown("正在启动子进程…")))
            val gate = FrameGate(context?.mainLooper)
            var perBatch = 3
            var inBatch = 0
            var batchStart = System.nanoTime()
            for (index in 0 until FORK_COUNT) {
                if (isCancelled()) return
                val pid = PhantomProcessNative.forkNullLoop()
                if (pid <= 0) {
                    emit(
                        ProbeUpdate(
                            ProbePhase.Done,
                            forked,
                            ChildProcessLimit.Unknown("fork 失败（已创建 $forked 个子进程）"),
                        ),
                    )
                    return
                }
                synchronized(state) { state += pid }
                forked++
            }

            // demo：fork 完立刻发广播，把 :probe 进程拉起来（这是让系统来查的必要条件）。
            if (context != null) {
                runCatching {
                    context.sendBroadcast(Intent(context, PhantomProbeReceiver::class.java))
                    Log.i(TAG, "poked :probe process after forking $forked children")
                }
            }
            emit(ProbeUpdate(ProbePhase.Forking, forked, ChildProcessLimit.Unknown("$forked 个子进程已就绪，观察中…")))

            // demo：Thread.sleep(3000) 之后先给乐观结论。
            val settleDeadline = System.currentTimeMillis() + SETTLE_MILLIS
            while (!isCancelled() && System.currentTimeMillis() < settleDeadline) {
                reapedPid(state)?.let { dead ->
                    emit(
                        ProbeUpdate(
                            ProbePhase.Observing,
                            forked,
                            ChildProcessLimit.Limited("系统回收了子进程 $dead（上限约 $SYSTEM_CAP）"),
                        ),
                    )
                    return
                }
                Thread.sleep(100)
            }
            if (isCancelled()) return
            emit(
                ProbeUpdate(
                    ProbePhase.Observing,
                    forked,
                    ChildProcessLimit.Relaxed("$forked 个子进程存活满 ${SETTLE_MILLIS / 1000} 秒，未观察到系统回收"),
                ),
            )

            val deadline = System.currentTimeMillis() + OBSERVE_MILLIS
            while (!isCancelled() && System.currentTimeMillis() < deadline) {
                reapedPid(state)?.let { dead ->
                    emit(
                        ProbeUpdate(
                            ProbePhase.Observing,
                            forked,
                            ChildProcessLimit.Limited("系统回收了子进程 $dead（上限约 $SYSTEM_CAP）"),
                        ),
                    )
                    return
                }
                Thread.sleep(POLL_MILLIS)
            }
            emit(
                ProbeUpdate(
                    ProbePhase.Done,
                    forked,
                    ChildProcessLimit.Relaxed("$forked 个子进程存活满 ${OBSERVE_MILLIS / 1000} 秒，未观察到系统回收"),
                ),
            )
        } catch (interrupted: InterruptedException) {
            // cancel() 走到这里，进入清理。
        } catch (failure: Throwable) {
            emit(
                ProbeUpdate(
                    ProbePhase.Done,
                    forked,
                    ChildProcessLimit.Unknown("检测失败：" + (failure.message ?: failure.javaClass.simpleName)),
                ),
            )
        } finally {
            cleanup(state)
            synchronized(lock) {
                if (pids === state) pids = null
                worker = null
            }
        }
    }

    private fun reapedPid(state: MutableList<Int>): Int? {
        val snapshot = synchronized(state) { state.toList() }
        for (pid in snapshot) {
            if (PhantomProcessNative.waitPidNoHang(pid) == 0) continue
            synchronized(state) { state.remove(pid) }
            return pid
        }
        return null
    }

    private fun cleanup(state: MutableList<Int>) {
        val snapshot = synchronized(state) { state.toList() }
        snapshot.forEach { PhantomProcessNative.killPid(it) }
        snapshot.forEach { PhantomProcessNative.reapPid(it, 200) }
        synchronized(state) { state.clear() }
    }

    private fun isCancelled(): Boolean = synchronized(lock) { cancelled }

}

// ---------------------------------------------------------------------------
// Below: type + JNI declarations shared with the rest of the runtime layer.
// ---------------------------------------------------------------------------

/**
 * Frame gate: only let the next fork batch start after the main thread has drawn a frame.
 *
 * fork() copies the page tables of the whole address space and holds this process mmap_lock
 * for the write side. Every allocation on the UI thread needs the same lock (read side), so
 * 66 back-to-back forks swallow several frames at once - that is the "tap feels frozen" stall.
 *
 * Batching on a ~8ms budget and waiting one frame in between keeps the total cost identical
 * while no single stall exceeds half a frame. Zero stalling is only possible by forking in a
 * different process (mmap_lock is per-process).
 */
private class FrameGate(private val looper: Looper?) {

    fun awaitFrame() {
        if (looper == null) {
            Thread.sleep(16)
            return
        }
        val latch = CountDownLatch(1)
        Handler(looper).post {
            Choreographer.getInstance().postFrameCallback { latch.countDown() }
        }
        latch.await(64, TimeUnit.MILLISECONDS)
    }
}
sealed interface ChildProcessLimit {
    data object NotNeeded : ChildProcessLimit
    data class Relaxed(val detail: String) : ChildProcessLimit
    data class Limited(val detail: String) : ChildProcessLimit
    data class Unknown(val detail: String) : ChildProcessLimit

    val ok: Boolean get() = this is Relaxed || this is NotNeeded
}

/** Only Forking / Settling means "still detecting" (max ~3s). */
enum class ProbePhase { Idle, Forking, Settling, Observing, Done;
    val pending: Boolean get() = this == Forking || this == Settling
}

data class ProbeUpdate(val phase: ProbePhase, val forked: Int, val limit: ChildProcessLimit)

internal class PhantomProcessNative private constructor() {
    companion object {
        init { System.loadLibrary("phantom-probe") }

        @JvmStatic external fun forkNullLoop(): Int
        @JvmStatic external fun waitAnyNoHang(): Int
        @JvmStatic external fun waitPidNoHang(pid: Int): Int
        @JvmStatic external fun killPid(pid: Int)
        @JvmStatic external fun reapPid(pid: Int, timeoutMillis: Int): Int
    }
}