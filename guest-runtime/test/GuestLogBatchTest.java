// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.guestruntime;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class GuestLogBatchTest {
    public static void main(String[] args) {
        List<String> lines = Arrays.asList("one", "二", "three", "four");
        List<String> batches = GuestLogBatch.combine(lines, 8);
        if (batches.size() != 3) throw new AssertionError("Unexpected batch count: " + batches.size());
        StringBuilder joined = new StringBuilder();
        for (String batch : batches) {
            if (batch.getBytes(StandardCharsets.UTF_8).length > 8)
                throw new AssertionError("Batch exceeded byte limit");
            joined.append(batch);
        }
        if (!"one\n二\nthree\nfour\n".equals(joined.toString()))
            throw new AssertionError("Batching changed line contents or order");
        System.out.println("guest_log_batch_complete=1");
    }
}