// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** romKey 的解析与版本比较（`docs/rom-package/02` §5.2 的契约）。 */
class RomKeysTest {

    @Test
    fun keyIsIdAndVersion() {
        assertEquals("android15@1.0.0", RomKeys.key("android15", "1.0.0"))
        assertEquals("android15", RomKeys.key("android15", RomKeys.LEGACY_VERSION))
        assertEquals("android15", RomKeys.key("android15", ""))
    }

    @Test
    fun parseKey() {
        assertEquals("android15", RomKeys.idOf("android15@1.0.0"))
        assertEquals("1.0.0", RomKeys.versionOf("android15@1.0.0"))
        assertEquals(RomKeys.LEGACY_VERSION, RomKeys.versionOf("android10"))
    }

    @Test
    fun compareVersionsNumeric() {
        assertTrue(RomKeys.compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(RomKeys.compareVersions("1.1.0", "1.0.99") > 0)
        assertTrue(RomKeys.compareVersions("1.0", "1.0.1") < 0)
        assertEquals(0, RomKeys.compareVersions("1.0.0", "1.0"))
        assertTrue(RomKeys.compareVersions("2026.11.1", "1.0.0") > 0)
    }

    @Test
    fun validity() {
        assertTrue(RomKeys.isValidId("android15"))
        assertTrue(RomKeys.isValidId("hihon-ui.1"))
        assertFalse(RomKeys.isValidId("Android15"))
        assertFalse(RomKeys.isValidId("-android"))
        assertTrue(RomKeys.isValidVersion("1.0.0"))
        assertTrue(RomKeys.isValidVersion("2026.11.1"))
        assertFalse(RomKeys.isValidVersion("1.0.0-rc1"))
    }
}
