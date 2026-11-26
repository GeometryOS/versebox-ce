// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.boot

import android.content.res.Resources
import io.a10vm.ui.boot.BootFault
import io.a10vm.ui.boot.BootTexts
import io.a10vm.ui.boot.BootUiState
import io.a10vm.ui.boot.RomGate
import me.kanadex.geometryos.rom.RomGate as PayloadGate
import me.kanadex.geometryos.rom.RomRef
import me.kanadex.geometryos.rom.RomTexts
import me.kanadex.geometryos.rom.RomVersionEntry
import me.kanadex.geometryos.rom.RomVersionPolicy
import me.kanadex.versebox.R

/*
 * The app-level sequence runs applicable events in resource order. Each event may suspend, and a
 * terminal result ends the attempt. The same priority catalog selects the visible runtime dialog.
 */
internal class EventOrder<E : Enum<E>>(names: Array<String>, catalog: List<E>) {
    private val events = names.map { name -> catalog.single { it.name == name } }

    init {
        require(events.size == catalog.size && events.toSet() == catalog.toSet())
    }

    fun before(first: E, second: E) {
        require(events.indexOf(first) < events.indexOf(second)) { "Expected $first before $second" }
    }

    fun firstMatching(matches: (E) -> Boolean): E? = events.firstOrNull(matches)

    suspend fun <R : Any> run(execute: suspend (E) -> R?): R? {
        for (event in events) execute(event)?.let { return it }
        return null
    }
}

internal enum class BootEvent {
    CheckInstance, ShowNoInstance, CheckUpdate, ShowUpdate, CheckSelection, ShowMissingSelection, OpenVersions,
    CheckPayload, ShowSelectionChanged, ReopenVersions, ShowMissingPayload, OpenRomList, RepairPayload, StartGuest,
    ShowStartupFailure, ShowRuntimeAlert, ShowSnapshotProgress, ShowSnapshotFailure,
}

internal class BootGateSequence(names: Array<String>) {
    constructor(resources: Resources) : this(resources.getStringArray(R.array.boot_event_order))

    private val order = EventOrder(names, BootEvent.entries).apply {
        before(BootEvent.CheckInstance, BootEvent.ShowNoInstance)
        before(BootEvent.ShowNoInstance, BootEvent.CheckUpdate)
        before(BootEvent.CheckPayload, BootEvent.ShowSelectionChanged)
        before(BootEvent.ShowSelectionChanged, BootEvent.ReopenVersions)
        before(BootEvent.ReopenVersions, BootEvent.StartGuest)
        before(BootEvent.CheckUpdate, BootEvent.ShowUpdate)
        before(BootEvent.ShowUpdate, BootEvent.CheckSelection)
        before(BootEvent.CheckSelection, BootEvent.ShowMissingSelection)
        before(BootEvent.ShowMissingSelection, BootEvent.OpenVersions)
        before(BootEvent.OpenVersions, BootEvent.CheckPayload)
        before(BootEvent.CheckPayload, BootEvent.ShowMissingPayload)
        before(BootEvent.ShowMissingPayload, BootEvent.OpenRomList)
        before(BootEvent.CheckPayload, BootEvent.RepairPayload)
        before(BootEvent.OpenRomList, BootEvent.StartGuest)
        before(BootEvent.RepairPayload, BootEvent.StartGuest)
    }

    data class Inspection(val gate: PayloadGate, val key: String, val rowId: String)

    sealed interface Result {
        data object NoInstance : Result
        data object Start : Result
        data object OpenVersions : Result
        data object OpenRomList : Result
        data class Repair(val key: String, val rowId: String) : Result
    }

    suspend fun run(
        binding: suspend () -> RomRef?,
        entries: suspend (RomRef) -> List<RomVersionEntry>,
        inspect: suspend (RomRef) -> Inspection?,
        ask: suspend (RomGate) -> Boolean,
    ): Result {
        var bindingSnapshot: RomRef? = null
        var newer: RomVersionEntry? = null
        var missing = false
        var wantsVersions = false
        var inspection: Inspection? = null
        return checkNotNull(order.run<Result> execute@{ event ->
            when (event) {
                BootEvent.CheckInstance -> bindingSnapshot = binding()
                BootEvent.ShowNoInstance -> if (bindingSnapshot == null) return@execute Result.NoInstance
                BootEvent.CheckUpdate -> {
                    val ref = checkNotNull(bindingSnapshot)
                    newer = RomVersionPolicy.newerVersion(entries(ref), ref)
                }
                BootEvent.ShowUpdate -> newer?.let {
                    wantsVersions = ask(RomGate(event, RomTexts.UPDATE_TITLE,
                        BootTexts.gateUpdateCandidate(it.nameWithVersion), RomTexts.UPDATE_CONFIRM, BootTexts.GATE_UPDATE_LATER))
                }
                BootEvent.CheckSelection -> {
                    val ref = checkNotNull(bindingSnapshot)
                    missing = !RomVersionPolicy.isSelectable(RomVersionPolicy.match(entries(ref), ref))
                }
                BootEvent.ShowMissingSelection -> if (missing) askMissing(event, checkNotNull(bindingSnapshot), ask)
                BootEvent.OpenVersions -> if (missing || wantsVersions) return@execute Result.OpenVersions
                BootEvent.CheckPayload -> inspection = inspect(checkNotNull(bindingSnapshot))
                BootEvent.ShowSelectionChanged -> if (inspection == null) askMissing(event, checkNotNull(bindingSnapshot), ask)
                BootEvent.ReopenVersions -> if (inspection == null) return@execute Result.OpenVersions
                BootEvent.ShowMissingPayload -> if (inspection?.gate == PayloadGate.Missing) {
                    ask(RomGate(event, BootTexts.GATE_PAYLOAD_TITLE, BootTexts.GATE_PAYLOAD_MISSING, BootTexts.ACTION_IMPORT_ROM))
                }
                BootEvent.OpenRomList -> if (inspection?.gate == PayloadGate.Missing) return@execute Result.OpenRomList
                BootEvent.RepairPayload -> inspection?.takeIf { it.gate == PayloadGate.NeedsExtract }?.let {
                    return@execute Result.Repair(it.key, it.rowId)
                }
                BootEvent.StartGuest -> {
                    check(inspection?.gate == PayloadGate.Ready)
                    return@execute Result.Start
                }
                BootEvent.ShowStartupFailure, BootEvent.ShowRuntimeAlert,
                BootEvent.ShowSnapshotProgress, BootEvent.ShowSnapshotFailure -> Unit
            }
            null
        })
    }

    private suspend fun askMissing(event: BootEvent, ref: RomRef, ask: suspend (RomGate) -> Boolean) {
        ask(RomGate(event, BootTexts.GATE_NEED_VERSION_TITLE,
            if (ref.isEmpty) BootTexts.GATE_NOT_SELECTED else BootTexts.gateMissing(ref.key), BootTexts.GATE_OPEN_VERSION))
    }

    fun visible(state: BootUiState): BootUiState {
        val faultEvent = when (state.fault) {
            null -> null
            BootFault.NoInstance -> BootEvent.ShowNoInstance
            is BootFault.Other -> BootEvent.ShowStartupFailure
        }
        val first = order.firstMatching { event ->
            if (event == state.romGate?.event) true else when (event) {
                BootEvent.ShowNoInstance, BootEvent.ShowStartupFailure -> event == faultEvent
                BootEvent.ShowRuntimeAlert -> state.alert != null
                BootEvent.ShowSnapshotProgress -> state.busy
                BootEvent.ShowSnapshotFailure -> state.message != null
                else -> false
            }
        }
        return state.copy(
            romGate = state.romGate.takeIf { first != null && first == it?.event },
            fault = state.fault.takeIf { first != null && first == faultEvent },
            alert = state.alert.takeIf { first == BootEvent.ShowRuntimeAlert },
            busy = first == BootEvent.ShowSnapshotProgress,
            message = state.message.takeIf { first == BootEvent.ShowSnapshotFailure },
        )
    }
}
