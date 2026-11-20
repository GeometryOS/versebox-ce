package io.a10vm.guestruntime;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public final class GuestRuntime {
    private final RuntimeFiles files;
    private final StartupLog startupLog;
    private final String label;
    private final File directory, stateRoot, rootfs, libraries, config, properties, log;

    public GuestRuntime(Context context, File directory) throws java.io.IOException {
        this(context, directory, "guest-runtime-inputs.json", 29, "Android 10", context.getFilesDir());
    }

    public GuestRuntime(Context context, File directory, String manifestName, int expectedSdk,
                        String label, File stateRoot) throws java.io.IOException {
        this(context, directory, manifestName, expectedSdk, label, stateRoot, null);
    }

    /**
     * @param romRoot 已解包的 ROM 目录（`files/roms/<romKey>/`）。**null 表示走原来的
     *                assets 路径**（旧实例与旧格式 ROM 逐字不变）；非 null 时清单、payload
     *                与（libMode=rom/both 时）客体 .so 都从这个目录读，见
     *                `docs/rom-package/03` §3.1。
     */
    public GuestRuntime(Context context, File directory, String manifestName, int expectedSdk,
                        String label, File stateRoot, File romRoot) throws java.io.IOException {
        directory = directory.getCanonicalFile();
        this.directory = directory;
        this.stateRoot = stateRoot.getCanonicalFile();
        this.label = label;
        startupLog = new StartupLog(directory, label);
        files = new RuntimeFiles(context, directory, startupLog, manifestName, expectedSdk, label, romRoot);
        rootfs = new File(directory, "rootfs");
        libraries = new File(directory, "lib");
        config = new File(directory, "ld.config.txt");
        properties = new File(directory, "properties");
        log = new File(directory, "art.log");
    }

    public String label() { return label; }

    public void verify() throws Exception { files.verify(); }
    public StartupLog startupLog() { return startupLog; }
    public File nativeFile(String logical) throws Exception { return files.nativeFile(logical); }

    public void configureHostDevices(java.util.Set<String> unavailableHals) throws Exception {
        if (files.inputs().has("romBoot") || files.inputs().optBoolean("vintfAllManifests")) {
            java.util.Set<String> unavailable = new java.util.HashSet<>(unavailableHals);
            unavailable.add("android.hardware.gnss");
            unavailable.add("vendor.qti.gnss");
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(new File(rootfs, "vendor/etc/vintf").toPath())) {
                for (java.nio.file.Path manifest : (Iterable<java.nio.file.Path>) paths.filter(path -> path.toString().endsWith(".xml"))::iterator)
                    HostDeviceManifest.configure(manifest, unavailable);
            }
            return;
        }
        String manifest = files.inputs().optString("hostDevicesManifest",
                "vendor/etc/vintf/manifest/a10vm-host-devices.xml");
        HostDeviceManifest.configure(new File(rootfs, manifest).toPath(), unavailableHals);
    }

    public void prepare() throws Exception {
        verify();
        files.installData();
        JSONArray directories = files.inputs().getJSONArray("initialDirectories");
        for (int i = 0; i < directories.length(); ++i)
            Files.createDirectories(new File(rootfs, directories.getString(i)).toPath());
        // API 28's set_sched_policy() writes the thread id to
        // /dev/cpuctl/<group>/tasks and reports a scheduling failure when that
        // open fails.  A container has no cgroup mount, and the failure aborts
        // SystemServer in DisplayManagerService.setupSchedulerPolicies(), so
        // provide the node with the content shape an Android init would have.
        for (String group : Arrays.asList("top-app", "foreground", "background")) {
            File tasks = new File(rootfs, "dev/cpuctl/" + group + "/tasks");
            Files.createDirectories(tasks.getParentFile().toPath());
            if (!tasks.isFile()) Files.write(tasks.toPath(), new byte[0]);
        }
        Map<String, String> aliases = new java.util.LinkedHashMap<>();
        if (files.inputs().optBoolean("apexAliases", true)) {
            aliases.put("apex/com.android.runtime", "/system/apex/com.android.runtime.debug");
            for (String name : Arrays.asList("com.android.conscrypt", "com.android.media", "com.android.media.swcodec", "com.android.resolv", "com.android.tzdata"))
                aliases.put("apex/" + name, "/system/apex/" + name);
        }
        aliases.put("product", "/system/product");
        aliases.put("etc", "/system/etc");
        aliases.put("data/data", "/data/user/0");
        JSONObject romAliases = files.inputs().optJSONObject("rootfsAliases");
        if (romAliases != null)
            for (java.util.Iterator<String> names = romAliases.keys(); names.hasNext();) {
                String name = names.next();
                aliases.put(name, romAliases.getString(name));
            }
        for (Map.Entry<String, String> alias : aliases.entrySet())
            files.link(new File(rootfs, alias.getKey()), alias.getValue());
        File sockets = new File(directory, "sock");
        Files.createDirectories(sockets.toPath());
        files.link(new File(rootfs, "dev/socket"), sockets.getPath());
        JSONArray inputs = files.inputs().getJSONArray("files");
        for (int i = 0; i < inputs.length(); ++i) {
            JSONObject item = inputs.getJSONObject(i);
            String logical = item.getString("logical");
            String library = item.optString("libraryName", logical.endsWith(".so") ? logical : "");
            if (!library.isEmpty()) {
                files.link(new File(libraries, library), nativeFile(logical).getPath());
                files.link(new File(rootfs, "system/lib64/" + library), nativeFile(logical).getPath());
            }
            if (item.has("guestPath"))
                files.link(new File(rootfs, item.getString("guestPath").substring(1)), nativeFile(logical).getPath());
        }
        // Android 15's libhardware only accepts a HAL module whose realpath() stays
        // inside the HAL directory: hw_module_exists() realpath()s the candidate and
        // requires the result to begin with realpath(dir) + '/'
        // (disassembly: libhardware.so hw_get_module_by_class+0x42c -> 0x4440 ->
        // 0x4550). Every native input above is materialised as a symlink into the
        // APK's nativeLibraryDir, so realpath() answers
        // /data/app/.../lib/arm64/liba15vm-guest-*.so and the module is declared
        // missing; composer then prints "failed to get hwcomposer or gralloc module"
        // without ever calling dlopen (real-device 2026-10-27 20:11, instance
        // 1793101892870). A9's libhardware.so has no realpath reference at all,
        // which is why the same symlink layout works there. Entries listed in
        // realFiles are installed as regular files inside the instance rootfs,
        // exactly like a device image, and replace the symlink created above.
        JSONArray realFiles = files.inputs().optJSONArray("realFiles");
        for (int i = 0; realFiles != null && i < realFiles.length(); ++i) {
            JSONObject item = realFiles.getJSONObject(i);
            File target = new File(rootfs, item.getString("guestPath").substring(1));
            File source = nativeFile(item.getString("logical"));
            Files.createDirectories(target.getParentFile().toPath());
            Files.deleteIfExists(target.toPath());
            Files.copy(source.toPath(), target.toPath());
        }
        JSONArray nativeLinks = files.inputs().optJSONArray("nativeLinks");
        for (int i = 0; nativeLinks != null && i < nativeLinks.length(); ++i) {
            JSONObject item = nativeLinks.getJSONObject(i);
            files.link(new File(directory, item.getString("path")), nativeFile(item.getString("logical")).getPath());
        }
        String nativePath = files.nativeDirectory.getCanonicalPath();
        // 客体 .so 的物理根：新格式 ROM 是 <rom>/payload/lib，旧格式 payload 是
        // files/romlibs/<prefix>；A9/A10 的老清单为空，此时列表里只有 APK 的 lib 目录，
        // 与改动前逐字一致（docs/rom-package/04 §3.4）。
        java.util.List<String> libRootPaths = new ArrayList<>();
        for (File root : files.libRoots()) libRootPaths.add(root.getCanonicalPath());
        // linker 的 permitted.paths 决定"哪些宿主路径允许被 mmap"。LD_PRELOAD、执行体
        // （linker/dalvikvm64/app_process64）都直接指向 payload/lib，不把这几条加进去
        // linker 会拒绝加载（不是找不到，是 "not accessible for the namespace"）。
        java.util.LinkedHashSet<String> permitted = new java.util.LinkedHashSet<>();
        permitted.add(nativePath);
        permitted.add(directory.getCanonicalPath());
        permitted.addAll(libRootPaths);
        String allowed = String.join(":", permitted);
        java.util.List<String> namespaces = new ArrayList<>(Arrays.asList("runtime", "conscrypt", "media", "swcodec"));
        if (files.inputs().has("romBoot")) namespaces.addAll(Arrays.asList("vndk", "sphal"));
        // Android 15's libnativeloader resolves the platform public-library
        // namespace as "system" and falls back to the exported "default" namespace.
        // The guest must therefore export the namespace that owns the platform
        // libraries, while still creating one namespace per APEX, named after its
        // path (/apex/com.android.art -> com_android_art). Without the APEX entries
        // ART aborts with "namespace com_android_art does not exist or exported".
        java.util.List<String> apexNamespaces = new ArrayList<>();
        if (files.inputs().optBoolean("apexLayout")) {
            File apexRoot = new File(rootfs, "apex");
            File[] entries = apexRoot.listFiles();
            if (entries != null) {
                Arrays.sort(entries, java.util.Comparator.comparing(File::getName));
                for (File entry : entries) {
                    String name = entry.getName();
                    if (!entry.isDirectory() || !name.startsWith("com.android.")) continue;
                    apexNamespaces.add(name.replace('.', '_').replace('-', '_'));
                }
            }
            // Keep the default namespace as the exported platform-library owner.
            // libnativeloader falls back from the optional "system" export to
            // "default", which is where the guest materializes system libraries.
            namespaces.addAll(0, apexNamespaces);
        }
        // A real Android linker also searches the VNDK directories of the default
        // namespace.  Redroid 9 keeps libraries such as libpuresoftkeymasterdevice.so
        // only in /system/lib64/vndk-28, and without this path dlopen of
        // android.hardware.keymaster@3.0-impl.so fails with "library not found".
        java.util.List<String> defaultSearch = new ArrayList<>();
        defaultSearch.add(libraries.getPath());
        // payload/lib 排在实例 lib/ 之后：实例里的同名别名先命中（保持全部现有行为），
        // 只有当某个图书馆没被物化成别名时才由 ROM 自带的那份兜底。payload/lib 里的文件名
        // 一律是 liba15vm-*（不是 soname），所以它不可能抢掉 libc.so 之类的解析。
        defaultSearch.addAll(libRootPaths);
        // Android 15 keeps bionic and ART in APEX directories. The guest linker
        // resolves DT_NEEDED against these search paths, so without them
        // dlopen("libart.so") fails even though nativeLinks materialised the
        // file under rootfs/apex. A9/A10 manifests do not set apexLayout.
        if (files.inputs().optBoolean("apexLayout")) {
            for (String base : Arrays.asList(
                    "apex/com.android.runtime/lib64/bionic",
                    "apex/com.android.art/lib64",
                    "apex/com.android.conscrypt/lib64",
                    "apex/com.android.media/lib64",
                    "apex/com.android.media.swcodec/lib64",
                    "apex/com.android.resolv/lib64")) {
                File apexDirectory = new File(rootfs, base);
                if (apexDirectory.isDirectory()) defaultSearch.add(apexDirectory.getPath());
            }
        }
        for (String base : Arrays.asList("system/lib64", "vendor/lib64")) {
            File directory = new File(rootfs, base);
            if (directory.isDirectory()) defaultSearch.add(directory.getPath());
            File[] entries = directory.listFiles();
            if (entries == null) continue;
            for (File entry : entries)
                if (entry.isDirectory() && entry.getName().startsWith("vndk-")) defaultSearch.add(entry.getPath());
        }
        // HAL implementation directories: HIDL passthrough loads "lib@x.y-impl.so"
        // by soname, not by path, so the linker must search hw/ itself.  Without it
        // ComposerHal reports "failed to get mapper service" and SurfaceFlinger
        // aborts with "failed to create composer client".
        for (String base : Arrays.asList("system/lib64/hw", "vendor/lib64/hw")) {
            File directory = new File(rootfs, base);
            if (directory.isDirectory()) defaultSearch.add(directory.getPath());
        }
        // Same contract as Android 10: the private linker patch (see
        // container-probe/runtime/dynamic/platform.patch) forces the linker to read
        // A10VM_LD_CONFIG, so this file is the only config the guest ever sees.
        // Search paths are host paths; A10VM_HOST_ROOT / A10VM_STATE_ROOT make the path
        // layer hand them back unchanged instead of prefixing the guest rootfs.
        String text = "dir.guest = " + nativePath + "\ndir.guest = " + directory.getCanonicalPath()
                + "\ndir.guest = " + files.hostRoot().getCanonicalPath() // = APK lib（见 RuntimeFiles.hostRoot）
                + "\n[guest]\nadditional.namespaces = " + String.join(",", namespaces) + "\n"
                + "namespace.default.isolated = true\n"
                + "namespace.default.search.paths = " + String.join(":", defaultSearch) + "\n"
                + "namespace.default.permitted.paths = " + allowed + "\n";
        if (files.inputs().optBoolean("apexLayout")) {
            // Android 15's libnativeloader resolves the platform parent of every
            // classloader namespace through an exported namespace named "default"
            // (the only namespace-name literal inside the library; failure text is
            // "namespace {} does not exist or exported").  A namespace is exported
            // only when ld.config marks it visible, and a real A15 ld.config has
            // `namespace.default.visible = true`.  Without that line
            // System.loadLibrary() from guest Java still finds the .so and configures
            // clns-1, but clns-1's shared group has no libc.so, so the linker maps a
            // SECOND copy of the project-built libc into the isolated namespace and
            // aborts with
            //   TLS symbol "(null)" in dlopened ".../liba15vm-guest-libc.so"
            //   referenced from ".../liba15vm-guest-libc.so" using IE access model
            // (real-device log 2026-10-27 19:06, instance 1793098159468,
            // vm/framework-server.log).  Consequence: JNI_OnLoad never ran, the
            // gRegJNI table stayed empty and android.os.Build.<clinit> died on
            // SystemProperties.native_get.  Evidence chain:
            // _audit/coding-log/2026-10-27-CL-A15-命名空间未导出与JNI注册.md
            text += "namespace.default.visible = true\n";
            // One visible namespace per APEX, searching that APEX's own lib64 first.
            for (String name : apexNamespaces) {
                String apexName = name.replace('_', '.');
                File apexLib64 = new File(rootfs, "apex/" + apexName + "/lib64");
                String search = (apexLib64.isDirectory() ? apexLib64.getPath() : libraries.getPath())
                        + ":" + libraries.getPath();
                text += "namespace." + name + ".visible = true\nnamespace." + name + ".isolated = false\n"
                        + "namespace." + name + ".search.paths = " + search + "\n"
                        + "namespace." + name + ".permitted.paths = " + allowed + "\n"
                        + "namespace." + name + ".links = default\nnamespace." + name + ".link.default.allow_all_shared_libs = true\n";
            }
        }
        for (String name : namespaces) {
            if (files.inputs().optBoolean("apexLayout") &&
                    apexNamespaces.contains(name)) continue;
            text += "namespace." + name + ".visible = true\nnamespace." + name + ".isolated = true\n"
                    + "namespace." + name + ".search.paths = " + libraries.getPath() + "\n"
                    + "namespace." + name + ".permitted.paths = " + allowed + "\n"
                    + "namespace." + name + ".links = default\nnamespace." + name + ".link.default.allow_all_shared_libs = true\n";
        }
        Files.write(config.toPath(), text.getBytes(StandardCharsets.UTF_8));
        File seedLog = new File(directory, "seed.log");
        String seedInputs = "seed_inputs=" + files.dataHash(files.assetPrefix() + "guest.prop") + "\n";
        if (!seedLog.isFile() || !new String(Files.readAllBytes(seedLog.toPath()), StandardCharsets.UTF_8)
                .contains(seedInputs)) {
            files.clearDirectory(properties);
            ProcessBuilder seed = new ProcessBuilder(nativeFile("seed-properties").getPath(),
                    properties.getPath(), new File(directory, "guest.prop").getPath())
                    .redirectErrorStream(true).redirectOutput(seedLog);
            seed.environment().clear();
            seed.environment().put("PATH", "/system/bin");
            try { run(seed, "属性初始化", 5); }
            catch (Exception failure) {
                String detail = seedLog.isFile() ? new String(Files.readAllBytes(seedLog.toPath()), StandardCharsets.UTF_8) : "";
                throw new IllegalStateException("属性初始化失败：" + failure.getMessage() + "\n" + detail, failure);
            }
            Files.write(seedLog.toPath(), seedInputs.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
        }
    }

    /**
     * 把**本次运行**的显示密度写进本实例的 guest.prop。
     *
     * <p>为什么是 guest.prop：框架起来时 `property-service`（FrameworkSession.start 里那一步）
     * 就是用 `<实例目录>/guest.prop` 播种客体属性区的，`ro.sf.lcd_density` 在客体
     * SurfaceFlinger / DisplayManager 读它来决定 dp 与字体缩放。改这一个文件等于改
     * 这一次运行的密度，而且**只影响这台虚拟机**。
     *
     * <p>为什么必须在 {@link #prepare()} 之后调用：`installData()` 会按清单里的 sha256
     * 逐条比对，被改过的 guest.prop 会在下一次 prepare 时被原包内容覆盖回默认值。
     * 顺序固定为 `prepare() → applyDisplayDensity() → startDesktop()`，
     * 所以每次启动都写一遍是幂等的、也是必须的。
     *
     * <p>这个方法不区分 Android 9 / 10 / 15：三个运行时域用的是同一份 guest-runtime
     * 代码与同一条属性播种链（docs/display-settings/04 §3）。
     *
     * @param densityDpi 目标 DPI（Kotlin 侧已按 120..960 校验过，这里再挡一次）
     */
    public void applyDisplayDensity(int densityDpi) throws Exception {
        if (densityDpi < 120 || densityDpi > 960)
            throw new IllegalArgumentException("显示密度超出范围：" + densityDpi);
        File prop = new File(directory, "guest.prop");
        if (!prop.isFile()) throw new IllegalStateException("缺少 guest.prop：" + prop.getPath());
        List<String> lines = Files.readAllLines(prop.toPath(), StandardCharsets.UTF_8);
        boolean replaced = false;
        for (int i = 0; i < lines.size(); ++i) {
            String line = lines.get(i);
            int separator = line.indexOf('=');
            if (separator <= 0) continue;
            if (!line.substring(0, separator).trim().equals("ro.sf.lcd_density")) continue;
            lines.set(i, "ro.sf.lcd_density=" + densityDpi);
            replaced = true;
        }
        // 本镜像没有这一行时补一行（内置 ROM 与外部导入的 ROM 都可能有差异）。
        if (!replaced) lines.add("ro.sf.lcd_density=" + densityDpi);
        File staging = new File(directory, "guest.prop.display");
        Files.write(staging.toPath(), lines, StandardCharsets.UTF_8);
        // 原子替换：绝不能出现半个文件——它会被 property-service 直接读。
        Files.move(staging.toPath(), prop.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        startupLog.record("DISPLAY", "guest display " + densityDpi + "dpi (ro.sf.lcd_density)");
    }

    public FrameworkSession startFramework(String nonce) throws Exception {
        return startSession(nonce, false);
    }

    public FrameworkSession startDesktop(String nonce) throws Exception {
        return startSession(nonce, true);
    }

    public FrameworkSession startDesktop(String nonce, File graphicsSocket, File inputSocket) throws Exception {
        FrameworkSession session = new FrameworkSession(this, files, directory, graphicsSocket, inputSocket);
        try { session.start(nonce, true); return session; }
        catch (Exception failure) {
            try { session.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private FrameworkSession startSession(String nonce, boolean desktop) throws Exception {
        FrameworkSession session = new FrameworkSession(this, files, directory);
        try { session.start(nonce, desktop); return session; }
        catch (Exception failure) {
            try { session.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public Map<String, String> environment() throws Exception {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/system/bin");
        environment.put("TMPDIR", directory.getPath());
        environment.put("ANDROID_ROOT", "/system");
        environment.put("ANDROID_DATA", "/data");
        environment.put("ANDROID_STORAGE", "/storage");
        environment.put("EXTERNAL_STORAGE", "/storage/emulated/0");
        environment.put("ASEC_MOUNTPOINT", "/mnt/asec");
        environment.put("ANDROID_RUNTIME_ROOT", files.inputs().optString("runtimeRoot", "/apex/com.android.runtime"));
        environment.put("ANDROID_TZDATA_ROOT", files.inputs().optString("tzdataRoot", "/apex/com.android.tzdata"));
        // Android 15's libicu_jni and the ART APEX look their data up through
        // these two variables (init.environ.rc exports them on a real device).
        // Without ANDROID_I18N_ROOT the ICU initialisation aborts the guest with
        // "ANDROID_I18N_ROOT environment variable not set" before ART can start,
        // and without ANDROID_ART_ROOT ART cannot find its own APEX payload.
        if (files.inputs().optBoolean("apexLayout")) {
            environment.put("ANDROID_ART_ROOT", files.inputs().optString("artRoot", "/apex/com.android.art"));
            environment.put("ANDROID_I18N_ROOT", files.inputs().optString("i18nRoot", "/apex/com.android.i18n"));
        }
        environment.put("A10VM_ROOTFS", rootfs.getPath());
        environment.put("A10VM_HOST_ROOT", files.hostRoot().getCanonicalPath());
        environment.put("A10VM_STATE_ROOT", stateRoot.getPath());
        org.json.JSONObject runtimeProfile = files.inputs().optJSONObject("runtimeProfile");
        if (runtimeProfile != null)
            environment.put("A10VM_GUEST_APPLICATION_CLASS",
                    runtimeProfile.optString("appClass", "io.a10vm.guest.GuestApplication"));
        environment.put("A10VM_HOST_NETWORK", "1");
        environment.put("A10VM_PROPERTIES", properties.getPath());
        environment.put("A10VM_LD_CONFIG", config.getPath());
        environment.put("A10VM_EXEC_LINKER", nativeFile("ld-android.so").getPath());
        return environment;
    }

    public String checkJava(String nonce) throws Exception {
        String artBoot = "-Xbootclasspath:";
        org.json.JSONArray artPath = files.inputs().optJSONArray("artBootClassPath");
        if (artPath != null && artPath.length() > 0) {
            ArrayList<String> entries = new ArrayList<>();
            for (int i = 0; i < artPath.length(); ++i) entries.add(artPath.getString(i));
            artBoot += String.join(":", entries);
        } else {
            artBoot += "/apex/com.android.runtime/javalib/core-oj.jar:/apex/com.android.runtime/javalib/core-libart.jar";
        }
        ArrayList<String> command = new ArrayList<>(Arrays.asList(nativeFile("ld-android.so").getPath(),
                nativeFile("dalvikvm64").getPath(), "-Xnoimage-dex2oat", artBoot,
                "-Djava.library.path=" + libraries.getPath(), "-cp", new File(directory, "runtime-check.dex").getPath(),
                "io.a10vm.guest.RuntimeCheck", nonce));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory).redirectErrorStream(true).redirectOutput(log);
        builder.environment().clear();
        builder.environment().putAll(environment());
        StringBuilder preloads = new StringBuilder(files.nativeRole("guestLog").getPath());
        org.json.JSONArray extraPreloads = files.inputs().optJSONArray("extraPreloads");
        for (int i = 0; extraPreloads != null && i < extraPreloads.length(); ++i)
            preloads.append(':').append(nativeFile(extraPreloads.getString(i)).getPath());
        builder.environment().put("LD_PRELOAD", preloads.toString());
        try { run(builder, label + " ART", 15); }
        catch (Exception failure) {
            // A failed exec leaves art.log empty, so logs() alone says nothing.
            // Keep the throwable text: it carries the errno from ProcessBuilder.
            throw new IllegalStateException(label + " ART 启动失败：" + failure + "\n" + logs(), failure);
        }
        String output = logs();
        RuntimeIdentity.verifyArt(output, nonce, files, new File(rootfs, "data/local/tmp/runtime-check.txt"));
        return label + " ART 已通过线程和文件读写检查。";
    }

    private static void run(ProcessBuilder builder, String stage, int seconds) throws Exception {
        Process process = builder.start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new IllegalStateException(stage + " 超时");
            if (process.exitValue() != 0) throw new IllegalStateException(stage + " 退出 code=" + process.exitValue());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
    }

    public String logs() throws Exception {
        return log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : "";
    }

    public String diagnostics() { return RuntimeDiagnostics.collect(directory); }
}
