package io.a10vm.guestruntime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per-runtime desktop switches; Android 10 inputs keep the historical defaults. */
final class DesktopProfile {
    private final JSONObject inputs;
    private final JSONObject settings;

    DesktopProfile(JSONObject inputs) {
        this.inputs = inputs;
        settings = inputs.optJSONObject("runtimeProfile");
    }

    private boolean flag(String name, boolean fallback) {
        return settings == null ? fallback : settings.optBoolean(name, fallback);
    }

    private String text(String name, String fallback) {
        return settings == null ? fallback : settings.optString(name, fallback);
    }

    String bootEntry() { return text("bootEntry", "io.a10vm.guest.DesktopBoot"); }
    String checkClass() { return text("checkClass", "io.a10vm.guest.DesktopCheck"); }
    String inputClass() { return text("inputClass", "io.a10vm.guest.DesktopInput"); }
    String readyMarker() { return text("readyMarker", "desktop_services_ready=1"); }

    List<String> checkArguments() {
        List<String> result = new ArrayList<>(Arrays.asList("app_process64", "-Xnoimage-dex2oat",
                "/system/bin", checkClass()));
        JSONArray extra = settings == null ? null : settings.optJSONArray("checkArguments");
        if (extra != null) for (int i = 0; i < extra.length(); ++i) result.add(extra.optString(i));
        return result;
    }

    /**
     * Extra ART arguments for the desktop `server` role only.
     *
     * <p>Android 15 is the first runtime where the server is started with
     * {@code dalvikvm64}, so it is also the first one where the runtime's own
     * instrumentation switches can be used without touching a shared argv. They are
     * profile-driven so A9/A10 (empty by default) are byte-identical, and so a
     * diagnostic round can be reverted by editing one line in the inputs.
     */
    List<String> serverArtArguments() {
        List<String> result = new ArrayList<>();
        JSONArray extra = settings == null ? null : settings.optJSONArray("serverArtArguments");
        if (extra != null) for (int i = 0; i < extra.length(); ++i) result.add(extra.optString(i));
        return result;
    }

    /**
     * HIDL service manager channel: the hwbinder socket, the hwbinder broker,
     * the `hwservicemanager` process, and (separately) the libhwbinder.so
     * preload entry.
     *
     * Every image in use has a HIDL service manager: A9/A10 in system/bin,
     * Android 15 in system_ext. The A15 composer@2.1 / allocator@2.0 HALs and
     * SurfaceFlinger all open /dev/hwbinder and terminate when it is missing
     * (real-device evidence: instance 1793072298659), so A15 keeps this on too.
     */
    boolean hwbinder() { return flag("hwbinder", true); }
    /**
     * The `libhwbinder.so` LD_PRELOAD entry, which is a different question from
     * {@link #hwbinder()}: Android 15 merged the hwbinder transport into
     * libhidlbase and ships no libhwbinder.so, so asking for it would abort with
     * "Missing runtime input: libhwbinder.so". A15 sets this to false while
     * keeping the hwbinder channel itself enabled. A9/A10 keep the default.
     */
    boolean hwbinderPreload() { return flag("hwbinderPreload", true); }
    boolean halModules() { return flag("halModules", true); }
    boolean registryHelpers() { return flag("registryHelpers", false); }
    String appClass() { return text("appClass", "io.a10vm.guest.GuestApplication"); }
    String keymasterLogical() {
        return text("keymasterLogical", "image/vendor/bin/hw/android.hardware.keymaster@3.0-service");
    }
    String keystoreLogical() { return text("keystoreLogical", "image/system/bin/keystore"); }
    boolean dexCache() { return flag("dexCache", true); }
    boolean graphics() { return flag("graphics", true); }
    /**
     * The graphics configstore HAL service.
     *
     * A9/A10 images ship vendor/bin/hw/android.hardware.configstore@1.1-service,
     * so the host launches it. Android 15 keeps only the client library
     * (android.hardware.configstore@1.1.so, which SurfaceFlinger links) and has
     * no service binary; Redroid 15 boots SF without one, i.e. SF uses its
     * defaults. A15 therefore sets this to false instead of aborting the desktop
     * chain on a program the image does not contain.
     */
    boolean configstore() { return flag("configstore", true); }
    boolean systemSuspend() { return flag("systemSuspend", true); }
    boolean idmap2d() { return flag("idmap2d", true); }
    boolean health() { return flag("health", true); }
    boolean keystore() { return flag("keystore", true); }
    boolean audio() { return flag("audio", true); }
    boolean media() { return flag("media", true); }
    boolean installer() { return flag("installer", true); }
    /**
     * Skip installd's dex2oat child.  API 28's Redroid image has only a 32-bit
     * dex2oat; the runtime is 64-bit, so every DexInv fails with 0x1f and
     * PackageManagerService retries all system packages on every boot.
     */
    boolean dexoptShim() { return flag("dexoptShim", false); }
    boolean camera() { return flag("camera", inputs.optBoolean("hostCameraBridge")); }
    String healthMarker() {
        return text("healthMarker",
                "Registration complete for android.hardware.health@2.0::IHealth/default.");
    }
    int artStartupSeconds() { return settings == null ? 30 : settings.optInt("artStartupSeconds", 30); }

    JSONArray services() {
        JSONArray extra = settings == null ? null : settings.optJSONArray("services");
        return extra == null ? new JSONArray() : extra;
    }
}
