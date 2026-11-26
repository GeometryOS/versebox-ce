// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.boot

import io.a10vm.ui.boot.BootFault
import io.a10vm.ui.boot.BootTexts
import io.a10vm.ui.boot.BootUiState
import io.a10vm.ui.boot.RomGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.kanadex.geometryos.rom.RomGate as PayloadGate
import me.kanadex.geometryos.rom.RomSource
import me.kanadex.geometryos.rom.RomVersionEntry
import me.kanadex.versebox.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootGateSequenceTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources
    private val sequence get() = BootGateSequence(resources)
    private fun entry(version: String, digest: String) = RomVersionEntry(
        key = "android10@" + version, id = "android10", version = version,
        displayName = "Android 10", source = RomSource.Imported, apiLevel = 29,
        available = true, payloadSha256 = digest.repeat(64),
    )
    private val current = entry("1.0", "a")
    private val newer = entry("1.1", "b")

    @Test
    fun updateSuspendsSelectionAndNavigationOnBothResponses() = runBlocking {
        for (confirmed in listOf(false, true)) {
            val updateShown = CompletableDeferred<Unit>()
            val missingShown = CompletableDeferred<Unit>()
            val answerUpdate = CompletableDeferred<Boolean>()
            val answerMissing = CompletableDeferred<Boolean>()
            var reads = 0
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                sequence.run({ current.ref }, { reads++; listOf(current.copy(payloadSha256 = "c".repeat(64)), newer) },
                    { error("Missing selection must preempt payload inspection") }) { gate ->
                    when (gate.event) {
                        BootEvent.ShowUpdate -> { updateShown.complete(Unit); answerUpdate.await() }
                        BootEvent.ShowMissingSelection -> { missingShown.complete(Unit); answerMissing.await() }
                        else -> error(gate.event.name)
                    }
                }
            }
            updateShown.await()
            assertEquals(1, reads)
            assertFalse(missingShown.isCompleted)
            answerUpdate.complete(confirmed)
            missingShown.await()
            assertFalse(result.isCompleted)
            assertEquals(2, reads)
            answerMissing.complete(true)
            assertEquals(BootGateSequence.Result.OpenVersions, result.await())
        }
    }

    @Test
    fun readyRepairAndMissingPayloadHaveExclusiveResults() = runBlocking {
        val expected = mapOf(
            PayloadGate.Ready to BootGateSequence.Result.Start,
            PayloadGate.NeedsExtract to BootGateSequence.Result.Repair(current.key, current.rowId),
            PayloadGate.Missing to BootGateSequence.Result.OpenRomList,
        )
        for ((payload, result) in expected) {
            val shown = mutableListOf<BootEvent>()
            assertEquals(result, sequence.run({ current.ref }, { listOf(current, newer) }, {
                BootGateSequence.Inspection(payload, current.key, current.rowId)
            }) { shown += it.event; false })
            assertEquals(if (payload == PayloadGate.Missing) listOf(BootEvent.ShowUpdate, BootEvent.ShowMissingPayload)
                else listOf(BootEvent.ShowUpdate), shown)
        }
    }

    @Test
    fun deletedDuringUpdateAcknowledgementDoesNotStart() = runBlocking {
        var entries = listOf(current, newer)
        val shown = mutableListOf<BootEvent>()
        val result = sequence.run({ current.ref }, { entries }, { error("Unexpected inspection") }) {
            shown += it.event
            entries = listOf(newer)
            false
        }
        assertEquals(BootGateSequence.Result.OpenVersions, result)
        assertEquals(listOf(BootEvent.ShowUpdate, BootEvent.ShowMissingSelection), shown)
    }

    @Test
    fun noInstanceAndCancellationStopBeforeFurtherChecks() = runBlocking {
        assertEquals(BootGateSequence.Result.NoInstance,
            sequence.run({ null }, { error("Unexpected scan") }, { error("Unexpected inspection") }, { error("Unexpected prompt") }))
        val wait = CompletableDeferred<Boolean>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            sequence.run({ current.ref }, { listOf(current, newer) }, { error("Unexpected inspection") }) { wait.await() }
            error("Cancelled sequence must not produce an effect")
        }
        job.cancelAndJoin()
    }

    @Test
    fun xmlArbitratesConcurrentDialogsAndRejectsUnsafeOrder() {
        val state = BootUiState(
            romGate = RomGate(BootEvent.ShowUpdate, "update", "body", "OK"),
            fault = BootFault.Other("failure"), alert = BootTexts.alert("runtime"), busy = true, message = "snapshot",
        )
        val visible = sequence.visible(state)
        assertNotNull(visible.romGate)
        assertNull(visible.fault)
        assertNull(visible.alert)
        assertFalse(visible.busy)
        assertNull(visible.message)
        assertNotNull(sequence.visible(state.copy(romGate = null)).fault)
        assertNotNull(sequence.visible(state.copy(romGate = null, fault = null)).alert)
        assertTrue(sequence.visible(state.copy(romGate = null, fault = null, alert = null)).busy)
        val names = resources.getStringArray(R.array.boot_event_order)
        assertThrows(IllegalArgumentException::class.java) { BootGateSequence(names.reversedArray()) }
        assertThrows(IllegalArgumentException::class.java) { BootGateSequence(names + names.first()) }
    }
}
