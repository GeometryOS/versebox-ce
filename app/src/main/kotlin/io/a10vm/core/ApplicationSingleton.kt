// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.core

import android.content.Context

/*
 * Process-wide singleton holder keyed by the Application instance.
 * Production has exactly one Application, so a cached value is never rebuilt there.
 * Robolectric gives every test method a fresh Application and temp directory; a cache
 * that outlives it keeps reading a deleted directory, which fails only when several
 * tests share one JVM.
 */
class ApplicationSingleton<T : Any>(private val create: (Context) -> T) {
    @Volatile private var value: T? = null
    @Volatile private var owner: Context? = null

    fun of(context: Context): T {
        val app = context.applicationContext
        val cached = value
        if (cached != null && owner === app) return cached
        return synchronized(this) {
            val current = value
            if (current != null && owner === app) current
            else create(app).also { value = it; owner = app }
        }
    }
}
