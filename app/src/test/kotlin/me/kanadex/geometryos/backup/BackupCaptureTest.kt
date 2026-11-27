// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup

import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Stopped instance links can outlive the APK installation that created them
class BackupCaptureTest {
    @Test fun acceptsDeclaredLibrariesFromThePreviousAppInstall() {
        val oldInstall = "/data/app/~~old-container/me.kanadex.versebox-old-install/lib/arm64/liba15vm-source-linker.so"
        assertTrue(isDeclaredNativeLink(oldInstall, "liba15vm-source-linker.so",
            "me.kanadex.versebox", listOf(Paths.get("/data/app/current/lib/arm64"))))
    }

    @Test fun rejectsUndeclaredPathsFilesAndTraversal() {
        val currentRoot = Paths.get("/data/app/current/lib/arm64")
        assertTrue(isDeclaredNativeLink("/data/app/current/lib/arm64/liba15vm-source-linker.so",
            "liba15vm-source-linker.so", "me.kanadex.versebox", listOf(currentRoot)))
        assertFalse(isDeclaredNativeLink("/data/app/~~old/me.kanadex.other-old/lib/arm64/liba15vm-source-linker.so",
            "liba15vm-source-linker.so", "me.kanadex.versebox", emptyList()))
        assertFalse(isDeclaredNativeLink("/data/app/~~old/me.kanadex.versebox-old/lib/arm64/liba10vm-source-linker.so",
            "liba15vm-source-linker.so", "me.kanadex.versebox", emptyList()))
        assertFalse(isDeclaredNativeLink("/data/app/~~old/me.kanadex.versebox-../lib/arm64/liba15vm-source-linker.so",
            "liba15vm-source-linker.so", "me.kanadex.versebox", emptyList()))
        assertFalse(isDeclaredNativeLink("/etc/passwd", "liba15vm-source-linker.so",
            "me.kanadex.versebox", emptyList()))
        assertFalse(isDeclaredNativeLink("relative/liba15vm-source-linker.so", "liba15vm-source-linker.so",
            "me.kanadex.versebox", emptyList()))
    }
}
