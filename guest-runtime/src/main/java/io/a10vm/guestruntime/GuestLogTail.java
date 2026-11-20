package io.a10vm.guestruntime;

import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.kanadex.geometryos.guestruntime.GuestLogBatch;

/**
 * Mirrors the guest process logs to the host logcat.
 *
 * <p>Every guest process already writes its stderr (including
 * {@code __android_log_*} captured by liba10vm/liba9vm-guest-log) into
 * {@code <stateRoot>/vm/framework-*.log} and {@code application-*.log}.  Those
 * files are the only place a guest app's Java log lines appear, but they are
 * invisible to {@code adb logcat}: when a guest app freezes, the tester has to
 * pull the whole instance directory and the last line before the freeze is
 * still a file offset, not a timestamped logcat record.
 *
 * <p>This tailer appends every new line to the host logcat under
 * {@code A10VM_GUEST}.  It starts from the current end of files that already
 * exist when the session starts (older sessions are not replayed), and from
 * offset 0 for files created afterwards, so a freshly launched instance shows
 * its whole boot in real time.
 *
 * <p>This is a diagnostic mirror, not a second log sink: it never consumes or
 * rewrites the guest files, it only reads the bytes appended since the last
 * poll.  A single line is capped so a runaway binder trace cannot flood the
 * host log buffer.
 */
public final class GuestLogTail implements AutoCloseable {
    private static final String TAG = "A10VM_GUEST";
    private static final int MAX_CHUNK = 256 * 1024;
    private static final int MAX_LINE = 2048;
    private static final int MAX_BATCH_BYTES = 3000;
    private static final long POLL_MILLIS = 200;
    /**
     * 目录扫描比读增量慢一档。
     *
     * <p>会话开始时就存在的日志属于之前的会话，不会再增长；每 200 ms 为它们各做一次
     * stat 是纯浪费，而且开销随 vm 目录里的日志数量线性增长（一台跑久的实例有几百个
     * application-*.log，就是每秒上千次 stat）。所以新文件与"还在长的旧文件"按 2 s
     * 发现一次，已经发现的活动文件仍按 200 ms 读增量，实时性不变。
     */
    private static final long DISCOVER_MILLIS = 2_000;

    private final File directory;
    private final boolean batchLines;
    /** 本次会话正在跟踪并读取增量的文件（新出现的进程日志）。 */
    private final Map<String, File> active = new HashMap<>();
    /** 会话开始前就存在的日志：只在发现周期里看一眼有没有变大。 */
    private final Map<String, Long> sealed = new HashMap<>();
    private final Map<String, Long> offsets = new HashMap<>();
    private volatile boolean running;
    private Thread thread;

    public GuestLogTail(File directory, boolean batchLines) {
        this.batchLines = batchLines;
        // FrameworkSession's directory is already the instance "vm" folder
        // (VmDevices.runtimeDir = "<root>/<id>/vm"), and that is where launch()
        // writes framework-*.log and AppLaunch writes application-*.log.
        // Older callers pass the instance root instead, so accept both shapes.
        File nested = new File(directory, "vm");
        this.directory = nested.isDirectory() ? nested : directory;
    }

    public void start() {
        if (thread != null) return;
        // Files that already exist belong to a previous session.  Start at
        // their current end; files created by this session are read from 0.
        File[] existing = directory.listFiles();
        if (existing != null) {
            for (File file : existing) {
                String name = file.getName();
                if (!name.endsWith(".log")) continue;
                sealed.put(name, file.length());
            }
        }
        running = true;
        thread = new Thread(this::loop, "guest-log-tail");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        long nextDiscover = 0L;
        while (running) {
            try {
                if (SystemClock.uptimeMillis() >= nextDiscover) {
                    nextDiscover = SystemClock.uptimeMillis() + DISCOVER_MILLIS;
                    discover();
                }
                for (File file : new ArrayList<>(active.values())) forward(file);
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                return;
            } catch (Throwable ignored) {
                // A diagnostic mirror must never take the guest session down.
            }
        }
    }

    /**
     * 发现阶段：找出本次会话新增的日志，以及"开始前就存在但还在变大"的那几个。
     *
     * <p>只在这里枚举目录与做 stat，因此开销与"发现频率 × 目录里的日志数"成正比，
     * 而不是与每一拍都成正比。
     */
    private void discover() {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".log")) continue;
            if (active.containsKey(name)) continue;
            long length = file.length();
            Long before = sealed.get(name);
            if (before == null) {
                // 本次会话新出现的文件：从头读。
                active.put(name, file);
                offsets.put(name, 0L);
            } else if (length > before) {
                // 旧文件又开始增长：说明那个进程还活着，转入活动集合。
                active.put(name, file);
                offsets.put(name, before);
                sealed.remove(name);
            }
        }
    }

    private void forward(File file) {
        // 先看名字再看类型：非日志文件不必为它做一次 stat（vm 目录里还有目录与 sock）。
        String name = file.getName();
        if (!name.endsWith(".log")) return;
        if (!file.isFile()) return;
        long offset = offsets.getOrDefault(name, 0L);
        long length = file.length();
        if (length < offset) offset = 0;
        if (length == offset) return;
        if (offset == 0 && length > MAX_CHUNK) offset = length - MAX_CHUNK;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(offset);
            int size = (int) Math.min(length - offset, MAX_CHUNK);
            byte[] bytes = new byte[size];
            input.readFully(bytes);
            int end = -1;
            for (int i = size - 1; i >= 0; --i) if (bytes[i] == '\n') { end = i; break; }
            if (end < 0) return;
            offsets.put(name, offset + end + 1);
            int start = 0;
            // 客体日志本身不带时间，而"抽搐"只能靠时间对齐来定位（哪一行在跳帧之前）。
            // 这里在每一行前面加一次读取时刻的单调毫秒；误差上界就是这一遍轮询的间隔。
            long readAt = SystemClock.uptimeMillis();
            List<String> lines = new ArrayList<>();
            for (int i = 0; i <= end; ++i) {
                if (bytes[i] != '\n') continue;
                int lineLength = Math.min(i - start, MAX_LINE);
                if (lineLength > 0) lines.add("t=" + readAt + " " + name + ": " +
                        new String(bytes, start, lineLength, StandardCharsets.UTF_8));
                start = i + 1;
            }
            if (batchLines) {
                for (String batch : GuestLogBatch.combine(lines, MAX_BATCH_BYTES)) Log.i(TAG, batch);
            } else {
                for (String line : lines) Log.i(TAG, line);
            }
        } catch (Throwable ignored) {
            // File rotated or truncated between length() and read().
        }
    }

    @Override public void close() {
        running = false;
        Thread worker = thread;
        thread = null;
        if (worker != null) {
            worker.interrupt();
            try { worker.join(500); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
