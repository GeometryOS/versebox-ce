// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 自述的平铺解析与 L1 校验（`docs/rom-package/02` §2）。 */
class RomPropertiesYamlTest {

    private val sha = "0".repeat(63) + "1"

    private fun text(
        schema: String = "versebox-rom/2",
        id: String = "android15",
        version: String = "1.0.0",
        api: String = "35",
        bytes: String = "1688764416",
        payloadRoot: String = "payload/",
        extra: String = "",
    ): String = listOf(
        "schema: " + schema,
        "id: " + id,
        "romVersion: " + version,
        "name: Android 15",
        "androidVersion: \"15\"",
        "apiLevel: " + api,
        "description: 测试用自述",
        "inputs: versebox_inputs.json",
        "payloadRoot: " + payloadRoot,
        "payloadBytes: " + bytes,
        "payloadSha256: " + sha,
        "libMode: rom",
        "packedBy: pack-rom.py@test",
        extra,
    ).filter { it.isNotBlank() }.joinToString("\n")

    @Test
    fun parsesValidProperties() {
        val properties = RomPropertiesYaml.read(text())
        assertEquals("android15@1.0.0", properties.key)
        assertEquals(35, properties.apiLevel)
        assertEquals("rom", properties.libMode)
        assertFalse(properties.placeholder)
        assertEquals(RomLayout.INPUTS, properties.inputs)
    }

    @Test
    fun rejectsUnknownSchema() {
        try {
            RomPropertiesYaml.read(text(schema = "versebox-rom/1"))
            fail("应当拒绝旧 schema")
        } catch (expected: RomException) {
            assertTrue(expected.message!!.contains("schema"))
        }
    }

    @Test
    fun rejectsBadPayloadRoot() {
        try {
            RomPropertiesYaml.read(text(payloadRoot = "payload-a15/"))
            fail("payloadRoot 只允许 payload/")
        } catch (expected: RomException) {
            assertTrue(expected.message!!.contains("payloadRoot"))
        }
    }

    @Test
    fun rejectsZeroPayloadWithoutPlaceholder() {
        try {
            RomPropertiesYaml.read(text(bytes = "0"))
            fail("非占位包的 payloadBytes 必须大于 0")
        } catch (expected: RomException) {
            assertTrue(expected.message!!.contains("payloadBytes"))
        }
    }

    @Test
    fun acceptsPlaceholderWithZeroPayload() {
        val properties = RomPropertiesYaml.read(text(bytes = "0", extra = "placeholder: true"))
        assertTrue(properties.placeholder)
    }

    @Test
    fun rejectsApiOutOfRange() {
        try {
            RomPropertiesYaml.read(text(api = "36"))
            fail("apiLevel 必须在 26..35")
        } catch (expected: RomException) {
            assertTrue(expected.message!!.contains("apiLevel"))
        }
    }
}
