package io.a10vm.guestruntime;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

/** Small snapshots for the boot UI, with the same events retained for diagnostics. */
public final class StartupLog {
    private static final int LIMIT = 96;
    /** 每个阶段事件同时进 logcat：`adb logcat -s A10VM_STARTUP`。 */
    private static final String TAG = "A10VM_STARTUP";
    private final File file;
    private final String label;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private String session = "";
    private long started, sequence;
    /** 给 UI 用的"这一轮还在进行中"；`finish()` 只清它。 */
    private boolean active;
    /**
     * 是否已经 `begin()` 过。**写盘只看它，不看 `active`**。
     *
     * <p>2026-11-11 真机踩到：`NativeGraphicsSession.start()` 在 `finally` 里调 `finish()`，
     * 而"桌面确认/收开机动画"这些事发生在 start() 返回**之后**的后台线程里，
     * 于是 `record()` 被 `if (!active) return;` 直接丢掉 —— `SPAWN client`、
     * `READY client: desktop_services_ready=1`、`STOP bootanim: desktop-ready` 一行都没落盘，
     * 看日志的人只能看到 §BT§READY Android framework accepting input§BT§ 之后一片空白，
     * 于是"动画没停"和"桌面没起来"在日志里长得一模一样。
     */
    private boolean writable;

    StartupLog(File directory) { this(directory, "Android 10"); }

    StartupLog(File directory, String label) {
        file = new File(directory, "startup-events.log");
        this.label = label;
    }

    public synchronized void begin() {
        session = UUID.randomUUID().toString();
        started = System.nanoTime();
        sequence = 0;
        lines.clear();
        active = true;
        writable = true;
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), new byte[0]);
        } catch (IOException ignored) { /* UI events remain available if persistence fails. */ }
        record("BOOT", label + " startup");
    }

    public synchronized void record(String stage, String message) {
        if (!writable) return;
        String compact = message.replace('\n', ' ').replace('\r', ' ');
        if (compact.length() > 240) compact = compact.substring(0, 240);
        String line = String.format(Locale.ROOT, "[%06.2fs] %-7s %s",
                (System.nanoTime() - started) / 1_000_000_000.0, stage, compact);
        if (lines.size() == LIMIT) lines.removeFirst();
        lines.addLast(line);
        sequence++;
        Log.i(TAG, label + " " + line);
        try {
            Files.write(file.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { /* Logging must not interrupt a boot. */ }
    }

    public synchronized void finish() { active = false; }

    public synchronized Snapshot snapshot() {
        return new Snapshot(session, sequence, session.isEmpty() ? 0 : (System.nanoTime() - started) / 1_000_000,
                new ArrayList<>(lines), active);
    }

    public static final class Snapshot {
        public final String session;
        public final long sequence, elapsedMillis;
        public final ArrayList<String> lines;
        public final boolean active;
        private Snapshot(String session, long sequence, long elapsedMillis, ArrayList<String> lines, boolean active) {
            this.session = session;
            this.sequence = sequence;
            this.elapsedMillis = elapsedMillis;
            this.lines = lines;
            this.active = active;
        }
    }
}
