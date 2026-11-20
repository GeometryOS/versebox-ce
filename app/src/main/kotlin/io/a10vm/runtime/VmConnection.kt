package io.a10vm.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException

/**
 * 管理页/全屏页与 [VmService] 之间的通道。
 *
 * 状态全部按 `instance`（虚拟机 id）区分；界面自己维护 id → 状态的映射，所以多台同时
 * 运行时不会互相覆盖。
 */
class VmConnection(
    private val context: Context,
    private val onState: (Bundle) -> Unit,
    private val onLogs: (String) -> Unit = {},
    private val onDisconnected: () -> Unit = {},
) {
    private var remote: Messenger? = null
    private var bound = false
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (bound) when (message.what) {
            VmService.STATE -> onState(message.data)
            VmService.LOGS -> onLogs(message.data.getString("text").orEmpty())
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = Messenger(service)
            send(VmService.REGISTER)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            onDisconnected()
        }
    }

    fun bind() {
        if (!bound) bound = context.bindService(Intent(context, VmService::class.java), connection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
    }

    fun unbind() {
        if (!bound) return
        send(VmService.UNREGISTER)
        bound = false
        context.unbindService(connection)
        remote = null
    }

    fun perform(action: String, uri: String? = null, instance: String) {
        context.startForegroundService(Intent(context, VmService::class.java).setAction(action).apply {
            putExtra(VmService.EXTRA_INSTANCE, instance)
            if (uri != null) putExtra("uri", uri)
        })
    }

    fun requestLogs(instance: String? = null) = send(VmService.LOGS, instance)

    fun refreshNetwork(instance: String? = null) = send(VmService.REFRESH_NETWORK, instance)

    private fun send(code: Int, instance: String? = null): Boolean {
        val service = remote ?: return false
        return try {
            service.send(Message.obtain(null, code).apply {
                replyTo = receiver
                if (instance != null) data = Bundle().apply { putString(VmService.EXTRA_INSTANCE, instance) }
            })
            true
        } catch (_: RemoteException) { onDisconnected(); false }
    }
}
