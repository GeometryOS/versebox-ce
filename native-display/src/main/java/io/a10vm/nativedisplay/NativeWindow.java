package io.a10vm.nativedisplay;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Surface;
import java.io.File;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongFunction;

/** Service-owned GPU endpoint; a Surface attachment does not own the guest. */
public final class NativeWindow implements AutoCloseable {
    private long handle;
    private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock();
    /** 本次运行真正生效的几何（由 native 侧回读，可能已被合法性检查改回默认值）。 */
    private final int width, height, density;

    public NativeWindow(File directory) {
        this(directory, 0, 0, 0);
    }

    /**
     * @param width  客体像素宽；0 = 用编译期默认值（旧调用点行为不变）
     * @param height 客体像素高；0 = 用编译期默认值
     * @param density 客体 DPI；0 = 用编译期默认值
     */
    public NativeWindow(File directory, int width, int height, int density) {
        handle = WindowBridge.create(directory.getAbsolutePath(), width, height, density);
        int[] mode = handle == 0 ? new int[0] : WindowBridge.mode(handle);
        if (mode.length == 3) {
            this.width = mode[0];
            this.height = mode[1];
            this.density = mode[2];
        } else {
            this.width = WindowBridge.WIDTH;
            this.height = WindowBridge.HEIGHT;
            this.density = WindowBridge.DENSITY;
        }
    }

    /** 客体像素宽（渲染器建帧缓冲用的那个值）。 */
    public int width() { return width; }
    /** 客体像素高。 */
    public int height() { return height; }
    /** 客体 DPI（与之对应的客体属性是 ro.sf.lcd_density）。 */
    public int density() { return density; }

    private <T> T access(LongFunction<T> operation, T absent) {
        lifetime.readLock().lock();
        try { return handle == 0 ? absent : operation.apply(handle); }
        finally { lifetime.readLock().unlock(); }
    }
    public long attach(Surface surface) { return access(value -> WindowBridge.attach(value, surface), 0L); }
    public void detach(long generation) {
        access(value -> { WindowBridge.detach(value, generation); return null; }, null);
    }
    public void touch(MotionEvent event, int width, int height) {
        // Input is best-effort: a saturated or torn-down channel must not fault the UI thread.
        try {
            access(value -> WindowBridge.submit(value, event, width, height, this.width, this.height), 0);
        } catch (IllegalStateException | UnsatisfiedLinkError ignored) {
        }
    }
    public void cancelTouch() {
        access(value -> WindowBridge.touch(value, MotionEvent.ACTION_CANCEL, 0,
                SystemClock.uptimeMillis() * 1000000, new int[0], new int[0], new int[0], new float[0]), 0);
    }
    public long[] metrics() { return access(WindowBridge::metrics, new long[17]); }
    public String error() { return access(WindowBridge::error, ""); }
    public String description() { return access(WindowBridge::description, ""); }
    public boolean requestExit() { return access(WindowBridge::requestExit, false); }
    @Override public void close() {
        long previous;
        lifetime.writeLock().lock();
        try { previous = handle; handle = 0; }
        finally { lifetime.writeLock().unlock(); }
        if (previous == 0) return;
        WindowBridge.destroy(previous);
    }
}
