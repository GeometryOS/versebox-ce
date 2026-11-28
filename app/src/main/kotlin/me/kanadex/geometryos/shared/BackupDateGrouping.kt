// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.shared

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Backup age buckets and display dates share Unix epoch milliseconds as input.
 * The caller supplies the clock and display time zone.
 */
internal object BackupDateGrouping {
    enum class Group(val title: String) {
        TODAY("一天内"), WEEK("一周内"), MONTH("一个月内"), HALF_YEAR("半年内"), YEAR("一年内"), OLDER("更早"), UNKNOWN("未知")
    }

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    fun group(timestampMillis: Long?, nowMillis: Long): Group {
        if (timestampMillis == null || timestampMillis <= 0L) return Group.UNKNOWN
        val age = nowMillis - timestampMillis
        if (age < 0L) return Group.UNKNOWN
        return when {
            age < DAY -> Group.TODAY
            age < WEEK -> Group.WEEK
            age < MONTH -> Group.MONTH
            age < HALF_YEAR -> Group.HALF_YEAR
            age < YEAR -> Group.YEAR
            else -> Group.OLDER
        }
    }

    fun format(timestampMillis: Long?, zone: ZoneId): String = timestampMillis?.takeIf { it > 0L }
        ?.let { formatter.withZone(zone).format(Instant.ofEpochMilli(it)) }.orEmpty()

    private const val DAY = 24L * 60 * 60 * 1000
    private const val WEEK = 7L * DAY
    private const val MONTH = 30L * DAY
    private const val HALF_YEAR = 183L * DAY
    private const val YEAR = 365L * DAY

    fun <T> groupAndSort(rows: List<T>, nowMillis: Long, timestamp: (T) -> Long?): List<Pair<Group, List<T>>> {
        val dated = rows.map { it to timestamp(it) }
        val groups = dated.groupBy { group(it.second, nowMillis) }
        return Group.entries.mapNotNull { bucket ->
            groups[bucket]?.let { entries ->
                bucket to entries.sortedByDescending { it.second ?: Long.MIN_VALUE }.map { it.first }
            }
        }
    }
}
