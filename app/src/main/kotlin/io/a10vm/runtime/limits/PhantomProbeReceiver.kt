// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.runtime.limits

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Runs in the :probe process (android:process=":probe"), a 1:1 port of the demos
 * DetectPhantomProcessReceive: delete the handshake marker, ask AMS for a background ANR,
 * then die. Its whole purpose is to create one extra app-process event so that AMS
 * re-counts phantom processes and trims the over-limit ones.
 */
class PhantomProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < 31) return
        val marker = java.io.File(context.applicationInfo.dataDir, "receivedBroad")
        if (marker.exists()) marker.delete()
        Log.i("PhantomProbe", "probe process up, marker deleted, pid=" + Process.myPid())
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.appNotResponding("CUSTOM ANR")
        }
        val pid = Process.myPid()
        Process.killProcess(pid)
        PhantomProcessNative.killPid(pid)
    }
}