// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 物理布局的唯一判据（[RomLayout]）。
 *
 * 守的是 P5 / R4：**同一个 romKey 的两份不同内容不许共用一个解包目录**。
 * 目录名一旦只按 romKey，两份内容就会互相覆盖——UI 上"两个版本可切"立刻变成假切换
 * （`docs/rom-release/01-入库配置/04-ROM身份与分组红线.md` R4）。
 */
class RomLayoutTest {

    private val layout = RomLayout(File("/tmp/roms"))

    @Test
    fun dirNameIsContentAddressed() {
        val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertEquals("android15@1.0.0@01234567", layout.dirName("android15@1.0.0", sha))
        assertEquals("archives 下的包名同规则", "android15@1.0.0@01234567.zip",
            layout.archiveFor("android15@1.0.0", sha).name)
    }

    @Test
    fun differentPayloadsNeverShareADirectory() {
        val a = layout.dirName("android15@1.0.0", "aaaaaaaaaaaaaaaa")
        val b = layout.dirName("android15@1.0.0", "bbbbbbbbbbbbbbbb")
        assertTrue(a != b)
        assertTrue(layout.dirFor("android15@1.0.0", "aaaa").path != layout.dirFor("android15@1.0.0", "bbbb").path)
    }

    @Test
    fun missingPayloadFallsBackToLegacyName() {
        // 旧格式（还没读到自述）：退回旧布局名，只是读取路径，不会成为写入目标。
        assertEquals("android9", layout.dirName("android9", ""))
        assertEquals("android9", layout.dirName("android9", "   "))
    }

    @Test
    fun dirNameValidationAllowsTagAndRejectsSeparators() {
        assertTrue(layout.isValidDirName("android15@1.0.0@01234567"))
        assertTrue(layout.isValidKey("android15@1.0.0"))
        assertFalse(layout.isValidDirName("android15@1.0.0/../etc"))
        assertFalse(layout.isValidDirName(""))
    }

    @Test
    fun isExtractedChecksTheGivenDirectoryNotTheKey() {
        val root = File(System.getProperty("java.io.tmpdir"), "rom-layout-test-" + System.nanoTime())
        val dir = File(root, "android15@1.0.0@01234567")
        dir.mkdirs()
        File(dir, RomLayout.PROPERTIES).writeText("x")
        File(dir, RomLayout.INPUTS).writeText("{}")
        File(dir, RomLayout.PAYLOAD).mkdirs()
        val local = RomLayout(root)
        assertTrue(local.isExtracted(dir))
        // 同一个 key 的另一个指纹目录没有被解包，不许因为"键对上了"就算就绪。
        assertFalse(local.isExtracted(local.dirFor("android15@1.0.0", "ffffffff")))
        root.deleteRecursively()
    }
}
