package io.a10vm.nativedisplay;

import android.view.MotionEvent;
import android.view.Surface;

public final class WindowBridge {
    static {
        // Per-ROM decoupling: the Android 15 runtime ships its own renderer named
        // liba15vm-desktop-window.so (pinned under guest-runtime-a15/prebuilt/arm64),
        // while the Android 9/10 runtimes keep liba10vm-desktop-window.so. Loading the
        // version-specific name first means an A15 build never packages or depends on an
        // A10-named artifact, and an A9/A10 build keeps working unchanged.
        try {
            System.loadLibrary("a15vm-desktop-window");
        } catch (UnsatisfiedLinkError a15) {
            System.loadLibrary("a10vm-desktop-window");
        }
    }
    private static native int[] displayMode();
    /**
     * **编译期默认几何**（DesktopDisplay.h 的三个常量）与刷新率。
     *
     * 只在"还没有会话 / 还不知道这台虚拟机设了什么分辨率"时使用：
     * 真正生效的分辨率来自每个渲染器自己的 [mode]（见 [NativeWindow#width]）。
     */
    private static final int[] MODE = displayMode();
    public static final int WIDTH = MODE[0], HEIGHT = MODE[1], REFRESH_HZ = MODE[2], DENSITY = MODE[3];
    private WindowBridge() {}

    /**
     * 创建渲染器。
     *
     * 宽/高/DPI 是**这台虚拟机自己的显示设置**解析出来的值（docs/display-settings/04）；
     * 传 0 表示沿用编译期默认值，行为与改造前逐字相同。
     */
    public static native long create(String directory, int width, int height, int density);

    /** 本次运行真正生效的显示几何：{width, height, density}。 */
    public static native int[] mode(long handle);

    public static native long attach(long handle, Surface surface);
    public static native void detach(long handle, long generation);
    public static native int tap(long handle, int x, int y);
    public static native int touch(long handle, int action, int index, long timeNanos,
                                   int[] ids, int[] xs, int[] ys, float[] pressures);
    public static native long[] metrics(long handle);
    public static native long[] intervals(long handle);
    public static native String error(long handle);
    public static native String description(long handle);
    public static native void resetTimings(long handle);
    public static native boolean requestExit(long handle);
    public static native void destroy(long handle);

    /**
     * 把宿主 view 上的触摸换算到客体像素坐标。
     *
     * [targetWidth]/[targetHeight] 必须是**这个会话**的几何（[NativeWindow] 的字段），
     * 不是编译期常量：分辨率改小以后仍按 1080x2160 换算，点击会整体偏移。
     */
    public static int submit(long handle, MotionEvent event, int viewWidth, int viewHeight,
                             int targetWidth, int targetHeight) {
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP &&
                action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_CANCEL &&
                action != MotionEvent.ACTION_POINTER_DOWN && action != MotionEvent.ACTION_POINTER_UP) return 0;
        int count = action == MotionEvent.ACTION_CANCEL ? 0 : event.getPointerCount();
        int[] ids = new int[count], xs = new int[count], ys = new int[count];
        float[] pressures = new float[count];
        for (int i = 0; i < count; ++i) ids[i] = event.getPointerId(i);
        // Only the current position is forwarded. Replaying batched history samples multiplied
        // every drag into N+1 synchronous round trips and is collapsed host-side instead.
        for (int i = 0; i < count; ++i) {
            xs[i] = scale(event.getX(i), viewWidth, targetWidth);
            ys[i] = scale(event.getY(i), viewHeight, targetHeight);
            pressures[i] = event.getPressure(i);
        }
        return touch(handle, action, count == 0 ? 0 : event.getActionIndex(),
                event.getEventTime() * 1000000, ids, xs, ys, pressures);
    }

    private static int scale(float value, int extent, int target) {
        return Math.max(0, Math.min(target - 1, (int)(value * target / Math.max(1, extent))));
    }
}
