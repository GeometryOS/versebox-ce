// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.rom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「现在能不能直接用这个 ROM」与「磁盘上这个解包目录属于哪一行」的判据
 * （`docs/rom-package/06-内置与本地ROM管理.md` §1.1 / §1.2）。
 *
 * 这组用例守两件被真机打脸过的事：
 *  1. 内置 ROM 与本地 ROM 是**平等的两份 ROM**：内置包的解包只是它的一个状态
 *     （Pending → Ready），不是"用内置包新建了一份本地 ROM"；
 *  2. 创建虚拟机与启动虚拟机问的是**同一个**结论：解包体缺了但原包还在 → 去 ROM 管理解包，
 *     不是"原地静默解 1.6 GB"（用户看到的是"点了保存没反应"）。
 */
class RomGateTest {

    private fun row(
        source: RomSource,
        state: RomState,
        payload: String = "aaaa1111",
        hasArchive: Boolean = true,
        placeholder: Boolean = false,
    ) = RomRow(
        key = RomKeys.key("android15", "1.1.0"),
        id = "android15",
        version = "1.1.0",
        displayName = "Android 15",
        description = "",
        androidVersion = "15",
        apiLevel = 35,
        source = source,
        state = state,
        payloadSha256 = payload,
        placeholder = placeholder,
        hasArchive = hasArchive,
    )

    private val missing = listOf("payload/rootfs/system/build.prop")

    @Test
    fun readyAndCompleteIsReady() {
        assertEquals(RomGate.Ready, RomGate.decide(row(RomSource.Imported, RomState.Ready), emptyList()))
        assertEquals(RomGate.Ready, RomGate.decide(row(RomSource.Builtin, RomState.Ready), emptyList()))
    }

    @Test
    fun notExtractedYetNeedsExtract() {
        // 内置包第一次使用：还没解包，但包在 APK 里（hasArchive）→ 去 ROM 管理解包。
        assertEquals(RomGate.NeedsExtract, RomGate.decide(row(RomSource.Builtin, RomState.Pending), missing))
    }

    @Test
    fun brokenPayloadButArchiveAliveNeedsExtract() {
        // 解包目录被系统清理：状态还是 Ready，但清单条目缺了，原包还在 → 自动修复。
        assertEquals(RomGate.NeedsExtract, RomGate.decide(row(RomSource.Builtin, RomState.Ready), missing))
        assertEquals(RomGate.NeedsExtract, RomGate.decide(row(RomSource.Imported, RomState.Missing), missing))
    }

    @Test
    fun noArchiveIsMissing() {
        // 连原包都没有：唯一该提示导入的情况。
        assertEquals(
            RomGate.Missing,
            RomGate.decide(row(RomSource.Imported, RomState.Missing, hasArchive = false), missing),
        )
        assertEquals(
            RomGate.Missing,
            RomGate.decide(row(RomSource.Builtin, RomState.Pending, hasArchive = false), missing),
        )
    }

    @Test
    fun placeholderIsMissing() {
        // 占位包只有自述与清单：解也解不出来，只能导入完整包。
        assertEquals(
            RomGate.Missing,
            RomGate.decide(row(RomSource.Builtin, RomState.Pending, placeholder = true), emptyList()),
        )
    }

    @Test
    fun legacyFollowsApkAndIsAlwaysReady() {
        val legacy = RomRow(
            key = "android9",
            id = "android9",
            version = RomKeys.LEGACY_VERSION,
            displayName = "Android 9",
            description = "",
            androidVersion = "9",
            apiLevel = 28,
            source = RomSource.Legacy,
            state = RomState.Ready,
        )
        // 旧格式没有派生层，不存在"缺文件"这种状态。
        assertEquals(RomGate.Ready, RomGate.decide(legacy, missing))
    }

    @Test
    fun absentRowIsMissing() {
        assertEquals(RomGate.Missing, RomGate.decide(null, emptyList()))
    }

    // ----------------------------------------------------- 解包目录的归属

    @Test
    fun builtinOwnsItsOwnUnpackedDirectory() {
        val builtin = listOf(RomRef("android15@1.1.0", RomSource.Builtin, "aaaa1111"))
        assertTrue(RomUsage.belongsToBuiltin("android15@1.1.0", "aaaa1111", builtin))
    }

    @Test
    fun anotherPayloadIsNotBuiltinOwned() {
        // 同 romKey、内容不同：那是另一个 ROM（本地导入的第二份），必须留成自己的行。
        val builtin = listOf(RomRef("android15@1.1.0", RomSource.Builtin, "aaaa1111"))
        assertFalse(RomUsage.belongsToBuiltin("android15@1.1.0", "bbbb2222", builtin))
        assertFalse(RomUsage.belongsToBuiltin("android10@1.0.0", "aaaa1111", builtin))
    }

    @Test
    fun builtinWithoutFingerprintStillOwns() {
        // 索引缺失 / 老包没有指纹：按 romKey 认（此时无法按内容区分，宁可归给内置那一行，
        // 也不许多出一行"可以删除的本地 ROM"）。
        val builtin = listOf(RomRef("android15@1.1.0", RomSource.Builtin, ""))
        assertTrue(RomUsage.belongsToBuiltin("android15@1.1.0", "aaaa1111", builtin))
    }

    @Test
    fun noBuiltinKeepsImportedRow() {
        // APK 里没有这个包：磁盘目录就是本地导入的（或手工拷入），照旧补一行本地 ROM。
        assertFalse(RomUsage.belongsToBuiltin("android15@1.1.0", "aaaa1111", emptyList()))
    }
}
