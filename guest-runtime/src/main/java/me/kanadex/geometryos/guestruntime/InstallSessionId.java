// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.guestruntime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * This parser reads the install session from PackageManager's success line.
 * Guest command logs may contain unrelated bracketed process IDs first.
 */
public final class InstallSessionId {
    private static final Pattern CREATED = Pattern.compile(
            "(?m)^Success: created install session \\[(\\d+)]\\s*$");

    private InstallSessionId() {}

    public static String require(String commandOutput) {
        Matcher match = CREATED.matcher(commandOutput);
        if (!match.find()) throw new IllegalStateException("Missing package session: " + commandOutput);
        return match.group(1);
    }
}