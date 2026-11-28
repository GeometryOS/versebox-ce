// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.shared

import java.text.Normalizer
import java.util.Locale

/*
 * Resolves display names against the same normalized keys used by the registry.
 * The caller supplies a snapshot and serializes allocation with the write.
 */
data class CorrectedVmName(val value: String, val key: String, val event: String? = null)

object VmInstanceNamePolicy {
    private val whitespace = Regex("[\\s\\u00a0\\u3000]+")
    private val numbered = Regex("^(.+?)\\s*\\(([1-9][0-9]*)\\)$")

    private fun clean(value: String) = value.filterNot { it.code <= 0x1f || it.code == 0x7f }
        .trim().replace(whitespace, " ").trim()

    private fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    fun normalize(value: String, maxLength: Int): Pair<String, String>? {
        val display = clean(value)
        if (display.isEmpty() || display.codePointCount(0, display.length) > maxLength) return null
        return display to key(display)
    }

    fun defaultName(romName: String, maxLength: Int): String {
        val label = "虚拟机"
        val cleaned = clean(romName)
        if (cleaned.endsWith(" $label")) return truncate(cleaned, maxLength)
        val prefix = truncate(cleaned, maxLength - label.length - 1)
        return if (prefix.isEmpty()) label else "$prefix $label"
    }

    fun correct(requested: String, occupiedNames: Collection<String>, maxLength: Int): CorrectedVmName? {
        val (display, normalized) = normalize(requested, maxLength) ?: return null
        val occupied = occupiedNames.mapTo(HashSet()) { key(clean(it)) }
        if (normalized !in occupied) return CorrectedVmName(display, normalized)
        val base = numbered.matchEntire(display)?.groupValues?.get(1) ?: display
        val label = " 虚拟机".takeIf { base.endsWith(it) }.orEmpty()
        val prefix = base.removeSuffix(label)
        // Each suffix gives a distinct key even when the prefix is shortened.
        for (number in 1L..occupied.size.toLong() + 1) {
            val tail = "($number)"
            val prefixLimit = maxLength - tail.length - label.length
            if (prefixLimit <= 0) return null
            val candidate = truncate(prefix, prefixLimit) + label + tail
            val candidateKey = key(candidate)
            if (candidateKey !in occupied) {
                return CorrectedVmName(candidate, candidateKey, "虚拟机名称重复，已从「$display」调整为「$candidate」")
            }
        }
        return null
    }

    private fun truncate(value: String, limit: Int): String = value.substring(
        0, value.offsetByCodePoints(0, minOf(limit, value.codePointCount(0, value.length))),
    ).trimEnd()
}
