// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.guestruntime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Combines complete log lines without exceeding Android's single-entry byte budget.
public final class GuestLogBatch {
    private GuestLogBatch() {}

    public static List<String> combine(List<String> lines, int maxBytes) {
        List<String> batches = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;
        for (String line : lines) {
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (currentBytes > 0 && currentBytes + lineBytes > maxBytes) {
                batches.add(current.toString());
                current.setLength(0);
                currentBytes = 0;
            }
            current.append(line).append('\n');
            currentBytes += lineBytes;
            if (lineBytes > maxBytes) {
                batches.add(current.toString());
                current.setLength(0);
                currentBytes = 0;
            }
        }
        if (currentBytes > 0) batches.add(current.toString());
        return batches;
    }
}