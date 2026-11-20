package io.a10vm.guestruntime;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Read bounded persisted tails, including sessions that failed before attachment. */
final class RuntimeDiagnostics {
    private static final int TAIL_BYTES = 6144;
    // A SIGQUIT thread dump from system_server is much larger than a normal
    // framework log tail. Preserve enough of it for the diagnostics action.
    private static final int SERVER_TAIL_BYTES = 64 * 1024;
    private static final int TRACE_TAIL_BYTES = 16384;
    private static final int MAX_TRACE_FILES = 3;

    static String collect(File directory) {
        StringBuilder text = new StringBuilder();
        Set<String> included = new HashSet<>();
        for (String name : new String[] {"session-state.log", "startup-events.log", "framework-client.log", "framework-input.log",
                "framework-server.log", "framework-compositor.log", "framework-allocator.log",
                "framework-composer.log", "framework-broker.log", "framework-host-devices.log",
                "framework-display-state.log", "art.log", "seed.log", "a9exec.log"}) {
            append(text, new File(directory, name), "framework-server.log".equals(name)
                    ? SERVER_TAIL_BYTES : TAIL_BYTES);
            included.add(name);
        }
        // FrameworkSession creates role-specific logs. Include every one so
        // diagnostics does not silently omit healthd, installd, or later HALs.
        File[] frameworkLogs = directory.listFiles((parent, name) ->
                name.startsWith("framework-") && name.endsWith(".log"));
        if (frameworkLogs != null) {
            Arrays.sort(frameworkLogs, Comparator.comparing(File::getName));
            for (File file : frameworkLogs) {
                if (included.add(file.getName())) append(text, file, "framework-server.log".equals(file.getName())
                        ? SERVER_TAIL_BYTES : TAIL_BYTES);
            }
        }
        File[] applications = directory.listFiles((parent, name) ->
                name.startsWith("application-") && name.endsWith(".log"));
        if (applications != null) {
            File newest = null, previous = null;
            long newestTime = Long.MIN_VALUE, previousTime = Long.MIN_VALUE;
            for (File file : applications) {
                long modified = file.lastModified();
                if (modified >= newestTime) {
                    previous = newest; previousTime = newestTime;
                    newest = file; newestTime = modified;
                } else if (modified >= previousTime) { previous = file; previousTime = modified; }
            }
            if (newest != null) append(text, newest);
            if (previous != null) append(text, previous);
        }
        appendNewestTraces(text, new File(directory, "rootfs/data/anr"), "ANR");
        appendNewestTraces(text, new File(directory, "rootfs/data/tombstones"), "TOMBSTONE");
        return text.length() == 0 ? "No runtime logs have been created.\n" : text.toString();
    }

    private static void appendNewestTraces(StringBuilder text, File directory, String kind) {
        File[] traces = directory.listFiles(File::isFile);
        if (traces == null || traces.length == 0) return;
        Arrays.sort(traces, Comparator.comparingLong(File::lastModified).reversed());
        int count = Math.min(MAX_TRACE_FILES, traces.length);
        for (int i = 0; i < count; ++i) {
            text.append("\n--- ").append(kind).append(" | ")
                    .append(traces[i].getAbsolutePath()).append(" ---\n");
            appendTail(text, traces[i], TRACE_TAIL_BYTES);
        }
    }

    private static void append(StringBuilder text, File file) {
        append(text, file, TAIL_BYTES);
    }

    private static void append(StringBuilder text, File file, int maxBytes) {
        if (!file.isFile()) return;
        text.append("\n--- ").append(file.getName()).append(" | ")
                .append(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date(file.lastModified())))
                .append(" ---\n");
        appendTail(text, file, maxBytes);
    }

    private static void appendTail(StringBuilder text, File file, int maxBytes) {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            int size = (int) Math.min(length, maxBytes);
            byte[] bytes = new byte[size];
            input.seek(length - size);
            input.readFully(bytes);
            int start = 0;
            while (start < size && (bytes[start] & 0xc0) == 0x80) start++;
            if (length > size) text.append("[recent tail]\n");
            text.append(new String(bytes, start, size - start, StandardCharsets.UTF_8)).append('\n');
        } catch (IOException error) { text.append("Read error: ").append(error.getMessage()).append('\n'); }
    }
}
