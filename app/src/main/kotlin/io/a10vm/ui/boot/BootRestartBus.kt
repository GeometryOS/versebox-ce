// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.boot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

// Each VM keeps its own pending start, including when its window has yet to be created.
object BootRestartBus {
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    val vmIds = pending.asStateFlow()

    fun request(vmId: String) {
        if (vmId.isNotEmpty()) pending.update { it + vmId }
    }

    fun consume(vmId: String): Boolean {
        val previous = pending.getAndUpdate { it - vmId }
        return vmId in previous
    }
}
