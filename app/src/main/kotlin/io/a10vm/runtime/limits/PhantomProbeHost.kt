// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.runtime.limits

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log

/**
 * 探针进程（`android:process=":phantom"`）：fork / 等待 / 回收**全部**在这里发生。
 *
 * 为什么必须独立进程（内核语义，不是风格）：
 * `fork()` 要给整个地址空间复制页表，全程持有**本进程** `mmap_lock` 的写侧；
 * UI 线程每一帧的分配都要拿同一把锁的读侧。66 次 fork ≈ 284~302ms（本机实测），
 * 这段时间 UI 线程连内存都分配不了，而 Compose 每帧都要分配 →
 * 按压反馈、水波纹、转场会被整段吞掉，观感就是"按下去卡住 0.x 秒"。
 *
 * `mmap_lock` 是 **per-process** 的，所以把 fork 搬到这个进程之后，主进程那把锁一次都不会被碰。
 * 这是真正的零阻塞；分帧/降优先级/sleep 只是把阻塞摊开，治不了本。
 *
 * 结论通过 [ResultReceiver] 回传，主进程只做一次 `startService`。
 */
class PhantomProbeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ChildProcessLimitProbe.cancel()
            return START_NOT_STICKY
        }

        @Suppress("DEPRECATION")
        val callback = intent?.getParcelableExtra<ResultReceiver>(EXTRA_CALLBACK)

        // 起跑放在后台线程：允许"上一次还在观察期"时被重跑请求接管（先取消、等它收尾、再开一批）。
        Thread({
            if (ChildProcessLimitProbe.running) {
                ChildProcessLimitProbe.cancel()
                val until = System.currentTimeMillis() + 800
                while (ChildProcessLimitProbe.running && System.currentTimeMillis() < until) {
                    Thread.sleep(20)
                }
            }
            val started = ChildProcessLimitProbe.start(applicationContext) { update ->
                report(callback, update)
            }
            if (!started) {
                report(
                    callback,
                    ProbeUpdate(ProbePhase.Done, 0, ChildProcessLimit.Unknown("上一次检测还没收尾")),
                )
            }
        }, "phantom-probe-launch").start()

        return START_NOT_STICKY
    }

    private fun report(callback: ResultReceiver?, update: ProbeUpdate) {
        val bundle = Bundle().apply {
            putInt(KEY_PHASE, update.phase.ordinal)
            putInt(KEY_FORKED, update.forked)
            putString(KEY_DETAIL, update.limit.text())
            putBoolean(KEY_OK, update.limit.ok)
            putBoolean(KEY_NOT_NEEDED, update.limit is ChildProcessLimit.NotNeeded)
            putBoolean(KEY_LIMITED, update.limit is ChildProcessLimit.Limited)
        }
        runCatching { callback?.send(RESULT_UPDATE, bundle) }
    }

    companion object {
        private const val TAG = "PhantomProbeService"

        const val ACTION_START = "io.a10vm.intent.action.PHANTOM_PROBE_START"
        const val ACTION_CANCEL = "io.a10vm.intent.action.PHANTOM_PROBE_CANCEL"
        const val EXTRA_CALLBACK = "callback"

        const val KEY_PHASE = "phase"
        const val KEY_FORKED = "forked"
        const val KEY_DETAIL = "detail"
        const val KEY_OK = "ok"
        const val KEY_NOT_NEEDED = "not_needed"
        const val KEY_LIMITED = "limited"
        const val RESULT_UPDATE = 1

        private fun ChildProcessLimit.text(): String = when (this) {
            ChildProcessLimit.NotNeeded -> "Android 11 及以下没有 phantom process 上限"
            is ChildProcessLimit.Relaxed -> detail
            is ChildProcessLimit.Limited -> detail
            is ChildProcessLimit.Unknown -> detail
        }

        fun start(context: Context, callback: ResultReceiver) {
            val intent = Intent(context, PhantomProbeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CALLBACK, callback)
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "startService failed: " + it.message) }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, PhantomProbeService::class.java).apply { action = ACTION_CANCEL }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "cancel failed: " + it.message) }
        }
    }
}
