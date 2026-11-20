package io.a10vm.guestruntime;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import me.kanadex.geometryos.guestruntime.InstallSessionId;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public final class FrameworkSession implements AutoCloseable {
    private static final int ART_STARTUP_SECONDS = 30;
    private DesktopProfile desktop = null;
    private final GuestRuntime runtime;
    private final RuntimeFiles files;
    private final File directory;
    private final File externalGraphics, inputSocket;
    private final Map<String, Process> children = new LinkedHashMap<>();
    private final Map<String, File> logs = new LinkedHashMap<>();
    private final List<String> romServices = new ArrayList<>();
    private Map<String, String> desktopEnvironment;
    private Map<String, String> helperEnvironment;
    private boolean inputStarted;
    private boolean desktopSession;
    /** CLASSPATH handed to the guest framework roles (server/client/input) as -cp. */
    private String desktopClassPath;
    private boolean api28DesktopSession;
    private GuestLogTail logTail;

    FrameworkSession(GuestRuntime runtime, RuntimeFiles files, File directory) {
        this(runtime, files, directory, null, null);
    }

    FrameworkSession(GuestRuntime runtime, RuntimeFiles files, File directory, File graphicsSocket, File inputSocket) {
        this.runtime = runtime;
        this.files = files;
        this.directory = directory;
        this.externalGraphics = graphicsSocket;
        this.inputSocket = inputSocket;
    }

    void start(String nonce, boolean desktop) throws Exception {
        // A crashed previous session (SIGKILLed server) leaves its zygote children
        // reparented to init; clean this instance's leftovers before adding more.
        reapGuestProcesses("before-start");
        desktopSession = desktop;
        api28DesktopSession = desktop && files.frameworkSdk() <= 28;
        this.desktop = new DesktopProfile(files.inputs());
        // Guest Java/native logs only land in <stateRoot>/vm/*.log, where logcat
        // cannot see them.  Mirror new lines to "A10VM_GUEST" so a freeze can be
        // diagnosed in real time instead of pulling the instance afterwards.
        logTail = new GuestLogTail(directory, files.frameworkSdk() == 29);
        logTail.start();
        File bootProperties = new File(directory, "boot-properties");
        File propertySocket = new File(directory, "property.sock");
        files.clearDirectory(bootProperties);
        Files.deleteIfExists(propertySocket.toPath());
        Map<String, String> propertyEnvironment = this.desktop.registryHelpers()
                ? registryBase() : runtime.environment();
        propertyEnvironment.put("A10VM_PROPERTIES", bootProperties.getPath());
        Process properties = launch("properties", Arrays.asList("property-service", bootProperties.getPath(),
                new File(directory, "guest.prop").getPath(), propertySocket.getPath()), propertyEnvironment);
        await("properties", properties, "property_service_ready=1", 3);
        RuntimeIdentity.verifyProcess(processPid("properties", "property_service_pid"), files);
        File socket = new File(directory, "binder.sock");
        Files.deleteIfExists(socket.toPath());
        Map<String, String> registry;
        if (this.desktop.registryHelpers()) {
            registry = registryEnvironment(socket);
        } else {
            // The A9 runtime keeps every helper in the same API 28 namespace; an
            // empty isolated registry would hide its own libc and Binder bridge.
            registry = runtime.environment();
            registry.put("A10VM_BINDER_PATH", socket.getPath());
        }
        registry.put("A10VM_PROPERTIES", bootProperties.getPath());
        Process broker = launch("broker", Arrays.asList("binder-broker", socket.getPath()), registry);
        await("broker", broker, "binder_broker_ready=1", 3);
        RuntimeIdentity.verifyProcess(processPid("broker", "binder_broker_pid"), files);
        Map<String, String> framework = runtime.environment();
        framework.put("A10VM_PROPERTIES", bootProperties.getPath());
        framework.put("A10VM_PROPERTY_SOCKET", propertySocket.getPath());
        framework.put("A10VM_BINDER_PATH", socket.getPath());
        framework.put("A10VM_DEX_CACHE_ENABLED", this.desktop.dexCache() ? "1" : "0");
        StringBuilder preload = new StringBuilder(files.nativeRole("propertyClient").getPath())
                .append(':').append(files.nativeRole("guestDevices").getPath()).append(':');
        if (this.desktop.halModules())
            preload.append(files.nativeRole("halModules").getPath()).append(':');
        preload.append(files.nativeRole("binderDevices").getPath()).append(':')
                .append(files.nativeRole("guestLog").getPath()).append(':')
                .append(files.nativeFile("libandroid_runtime.so").getPath()).append(':')
                .append(files.nativeFile("libutils.so").getPath()).append(':')
                .append(files.nativeFile("libbinder.so").getPath()).append(':');
        // The A15 image ships no libhwbinder.so (HIDL's hwbinder transport lives
        // inside libhidlbase), so that entry is profile-controlled while the
        // hwbinder channel itself (broker + hwservicemanager) stays enabled.
        if (this.desktop.hwbinderPreload())
            preload.append(files.nativeFile("libhwbinder.so").getPath()).append(':');
        preload.append(files.nativeFile("libhidlbase.so").getPath());
        JSONArray extraPreloads = files.inputs().optJSONArray("extraPreloads");
        for (int i = 0; extraPreloads != null && i < extraPreloads.length(); ++i)
            preload.append(':').append(files.nativeFile(extraPreloads.getString(i)).getPath());
        if (files.frameworkSdk() >= 35) {
            // Android 15's libnativeloader creates an isolated classloader namespace for
            // every System.loadLibrary() caller and fills it from the parent namespace's
            // "shared group" (bionic/linker/linker.cpp:2534). That group is exactly the
            // DF_1_GLOBAL members of the namespace
            // (bionic/linker/linker_namespaces.cpp:127-136), and libc.so is a plain
            // DT_NEEDED — so the classloader namespace has no libc, the linker maps a
            // SECOND copy of the project libc inside it, and that copy cannot use the
            // initial-exec TLS model:
            //   TLS symbol "(null)" in dlopened ".../liba15vm-guest-libc.so"
            //   referenced from ".../liba15vm-guest-libc.so" using IE access model
            // (real-device 2026-10-27 19:36, instance 1793100952210). LD_PRELOAD
            // members get DF_1_GLOBAL force-set (bionic/linker/linker.cpp:1739-1743) and
            // are added to every initial namespace, so naming the already-loaded runtime
            // libraries by soname here promotes those copies instead of loading new ones.
            // Effect: guest JNI libraries can be dlopened from a classloader namespace,
            // which is what liba15vm-jni-register.so needs to call startReg().
            preload.append(":libc.so:libm.so:libdl.so");
        }
        framework.put("LD_PRELOAD", preload.toString());
        Map<String, String> helper = this.desktop.registryHelpers() ? new HashMap<>(registry) : new HashMap<>(framework);
        helper.put("A10VM_PROPERTIES", bootProperties.getPath());
        helper.put("A10VM_PROPERTY_SOCKET", propertySocket.getPath());
        helper.put("LD_PRELOAD", files.nativeRole("propertyClient").getPath() + ":" +
                files.nativeRole("guestDevices").getPath() + ":" +
                files.nativeRole("binderDevices").getPath() + ":" +
                files.nativeRole("guestLog").getPath());
        helperEnvironment = helper;
        framework.put("CLASSPATH", new File(directory, "framework-check.dex").getPath());
        JSONArray boot = files.inputs().getJSONArray("bootClassPath");
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < boot.length(); ++i) paths.add(boot.getString(i));
        framework.put("BOOTCLASSPATH", String.join(":", paths));
        // Android 15's GetDefaultBootImageLocation() derives the boot image from
        // the common prefix of BOOTCLASSPATH and DEX2OATBOOTCLASSPATH.  With the
        // latter unset it aborts inside ParsedOptions::DoParse:
        //   Check failed: !location.empty()
        //   BOOTCLASSPATH and DEX2OATBOOTCLASSPATH must not be empty
        // (real-device log 2026-10-27, instance 1793088882545,
        // vm/framework-server.log).  A9/A10's ART has no such check, so their
        // environment stays exactly as before.
        if (files.frameworkSdk() >= 35)
            framework.put("DEX2OATBOOTCLASSPATH", dex2oatBootClassPath(paths));
        Map<String, String> managerEnvironment = new HashMap<>(framework);
        managerEnvironment.put("LD_PRELOAD", files.nativeRole("managerPolicy").getPath() + ":" +
                framework.get("LD_PRELOAD"));
        Process manager = launch("manager", Arrays.asList("servicemanager"), managerEnvironment);
        await("manager", manager, "binder_context_ready=1", 3);
        RuntimeIdentity.verifyProcess(processPid("manager", "binder_context_pid"), files);
        if (this.desktop.hwbinder()) {
            File hardwareSocket = new File(directory, "hwbinder.sock");
            Files.deleteIfExists(hardwareSocket.toPath());
            Process hardwareBroker = launch("hwbroker", Arrays.asList("binder-broker", hardwareSocket.getPath()), registry);
            await("hwbroker", hardwareBroker, "binder_broker_ready=1", 3);
            RuntimeIdentity.verifyProcess(processPid("hwbroker", "binder_broker_pid"), files);
            framework.put("A10VM_HWBINDER_PATH", hardwareSocket.getPath());
            managerEnvironment.put("A10VM_HWBINDER_PATH", hardwareSocket.getPath());
            if (helperEnvironment != null) helperEnvironment.put("A10VM_HWBINDER_PATH", hardwareSocket.getPath());
            Process hardwareManager = launch("hwmanager", Arrays.asList("hwservicemanager"), managerEnvironment);
            await("hwmanager", hardwareManager, "binder_context_ready=1", 3);
            RuntimeIdentity.verifyProcess(processPid("hwmanager", "binder_context_pid"), files);
        } else {
            // Only a runtime whose profile disables the HIDL channel (none today)
            // takes this path; every image in use has a HIDL service manager.
            runtime.startupLog().record("SKIP", "hwservicemanager: disabled by runtimeProfile.hwbinder");
        }
        if (desktop) {
            startDesktop(framework);
            return;
        }
        Process server = launch("server", command("Server", nonce), framework);
        await("server", server, "framework_service_registered=" + nonce, 10);
        RuntimeIdentity.verifyProcess(processPid("server", "framework_pid"), files);
        Process client = launch("client", command("Client", nonce), framework);
        await("client", client, "framework_complete=1", 10);
        RuntimeIdentity.verifyProcess(processPid("client", "framework_pid"), files);
        if (!client.waitFor(3, TimeUnit.SECONDS) || client.exitValue() != 0)
            throw new IllegalStateException("Framework client exit: " + readLog("client"));
        RuntimeIdentity.verifyFramework(readLog("client"), nonce, files);
        children.remove("client");
        if (!isAlive()) throw new IllegalStateException("Framework service stopped during startup");
    }

    private void startDesktop(Map<String, String> environment) throws Exception {
        RomBootProfile rom = new RomBootProfile(files.inputs(), directory);
        desktop = new DesktopProfile(files.inputs());
        File graphicsSocket = externalGraphics == null ? new File(directory, "gpu.sock") : externalGraphics;
        if (externalGraphics == null) {
            Files.deleteIfExists(graphicsSocket.toPath());
            File gpuLog = new File(directory, "framework-gpu.log");
            ProcessBuilder gpuBuilder = new ProcessBuilder(files.nativeFile("desktop-gpu-host").getPath(),
                    graphicsSocket.getPath()).directory(directory).redirectErrorStream(true).redirectOutput(gpuLog);
            gpuBuilder.environment().clear();
            gpuBuilder.environment().put("PATH", "/system/bin");
            Process gpu = gpuBuilder.start();
            children.put("gpu", gpu);
            logs.put("gpu", gpuLog);
            await("gpu", gpu, "desktop_gpu_ready=1", 3);
        }
        environment.put("A10VM_GPU_SOCKET", graphicsSocket.getPath());
        environment.put("A10VM_GUEST_UID", "1000");
        environment.put("A10VM_GUEST_GID", "1000");
        environment.put("A10VM_APP_PROCESS", files.nativeFile("app_process64").getPath());
        if (files.frameworkSdk() >= 35) {
            // Android 15 app children are started by the project zygote endpoint
            // (A15Zygote/A15AppLaunch) with the same dalvikvm64 shape the server
            // already uses, because the image's app_process only resolves a main
            // class through the boot image. A9/A10 keep the app_process path and
            // never read this variable.
            environment.put("A10VM_DALVIKVM64", files.nativeFile("dalvikvm64").getPath());
        }
        environment.put("A10VM_LIBRARY_PATH", new File(directory, "lib").getPath());
        if (!rom.enabled() && desktop.keystore()) {
            File keys = new File(directory, "rootfs/data/misc/keystore");
            Files.createDirectories(keys.toPath());
            android.system.Os.chmod(keys.getPath(), 0700);
            Map<String, String> keyEnvironment = new HashMap<>(environment);
            keyEnvironment.put("A10VM_GUEST_UID", "9999");
            keyEnvironment.put("A10VM_GUEST_GID", "9999");
            Process keymaster = launch("keymaster", Arrays.asList(desktop.keymasterLogical()), keyEnvironment);
            await("keymaster", keymaster,
                    "Registration complete for android.hardware.keymaster@3.0::IKeymasterDevice/default.", 5);
            keyEnvironment.put("A10VM_GUEST_UID", "1017");
            keyEnvironment.put("A10VM_GUEST_GID", "1017");
            Process keystore = launch("keystore", Arrays.asList(desktop.keystoreLogical(), "/data/misc/keystore"),
                    keyEnvironment);
            await("keystore", keystore, "with interface name default and seclevel SOFTWARE", 5);
            environment.put("A10VM_NATIVE_KEYSTORE", "1");
        }
        if (desktop.systemSuspend()) launch("suspend", Arrays.asList("system-suspend"), environment);
        if (desktop.graphics()) {
            launch("composer", Arrays.asList("graphics-composer"), environment);
            launch("allocator", Arrays.asList("graphics-allocator"), environment);
            // A15 has no configstore service binary; SF tolerates its absence.
            if (desktop.configstore())
                launch("configstore", Arrays.asList("graphics-configstore"), environment);
        }
        // Keep the ROM healthd as the fallback until the API 28 host-devices
        // binary (HostHealthHal, "backup") is actually built.  The battery
        // only follows the host once that binary replaces this service; see
        // docs/android9-port/20 and 24.
        if (rom.enabled() || desktop.health()) launch("health", Arrays.asList("health"), environment);
        if (desktop.idmap2d()) launch("idmap", Arrays.asList("idmap2d"), environment);
        Map<String, String> deviceEnvironment = desktop.registryHelpers() && helperEnvironment != null
                ? helperEnvironment : environment;
        Process devices = null;
        try {
            devices = launch("host-devices", Arrays.asList("host-devices"), deviceEnvironment);
            await("host-devices", devices, "host_devices_ready=1", 5);
        } catch (Exception unavailable) {
            // Sensors/vibrator/health are peripherals: Redroid 9 ships no sensors HAL
            // and the project has not rebuilt the A9 host-devices bridge yet.  A
            // missing peripheral must not stop SurfaceFlinger or SystemServer, the
            // same rule already applied to the input channel.
            if (devices != null) {
                devices.destroyForcibly();
                children.remove("host-devices");
                logs.remove("host-devices");
            }
            runtime.startupLog().record("WARN", "host-devices unavailable: " + unavailable.getMessage());
        }
        if (desktop.camera()) {
            Map<String, String> cameraEnvironment = new HashMap<>(environment);
            cameraEnvironment.put("A10VM_GUEST_UID", "1047");
            cameraEnvironment.put("A10VM_GUEST_GID", "1005");
            Process provider = launch("camera-provider", Arrays.asList("camera-provider"), cameraEnvironment);
            await("camera-provider", provider, "host_camera_provider_ready=1", 8);
            launch("camera", Arrays.asList("cameraserver"), cameraEnvironment);
        }
        if (desktop.audio()) {
            Map<String, String> audioEnvironment = new HashMap<>(environment);
            audioEnvironment.put("A10VM_GUEST_UID", "1041");
            audioEnvironment.put("A10VM_GUEST_GID", "1005");
            launch("audio-hal", Arrays.asList("audio-hal"), audioEnvironment);
            launch("audio", Arrays.asList("audioserver"), audioEnvironment);
        }
        if (desktop.media()) {
            Map<String, String> mediaEnvironment = new HashMap<>(environment);
            mediaEnvironment.put("A10VM_GUEST_UID", "1013");
            mediaEnvironment.put("A10VM_GUEST_GID", "1005");
            for (String role : Arrays.asList("media-extractor", "media-metrics", "media-drm", "drm", "media-omx", "media-codec", "media"))
                launch(role, Arrays.asList(role), mediaEnvironment);
        }
        if (desktop.installer()) {
            Map<String, String> installerEnvironment = new HashMap<>(environment);
            installerEnvironment.put("A10VM_GUEST_UID", "0");
            installerEnvironment.put("A10VM_GUEST_GID", "0");
            if (desktop.dexoptShim()) {
                // This guest has no 64-bit dex2oat.  Let installd's dex2oat child
                // exit 0 so PackageManagerService stops re-running a compilation
                // that cannot succeed (~74 failed DexInv calls per cold boot).
                installerEnvironment.put("A10VM_SKIP_DEX2OAT", "1");
                installerEnvironment.put("LD_PRELOAD",
                        files.nativeFile("liba9vm-dexopt-shim.so").getPath() + ":" +
                        installerEnvironment.get("LD_PRELOAD"));
            }
            launch("installer", Arrays.asList("installd"), installerEnvironment);
        }
        // A service that cannot be started must not take the whole session with it: this
        // list runs *before* the server, so one bad entry used to mean "no boot animation,
        // session failed" (round 151: adding the image's logd here did exactly that).
        // Record the failure, keep going, and let the role's own log carry the reason.
        JSONArray services = rom.services();
        for (int i = 0; i < services.length(); ++i) {
            try {
                startService(services.getJSONObject(i), environment);
            } catch (Exception failure) {
                String role = services.getJSONObject(i).optString("role", "service-" + i);
                android.util.Log.w("A10VM_NATIVE", "service " + role + " not started: " + failure);
                runtime.startupLog().record("WARN", role + ": " + failure.getMessage());
            }
        }
        JSONArray extraServices = desktop.services();
        for (int i = 0; i < extraServices.length(); ++i) {
            try {
                startService(extraServices.getJSONObject(i), environment);
            } catch (Exception failure) {
                String role = extraServices.getJSONObject(i).optString("role", "extra-" + i);
                android.util.Log.w("A10VM_NATIVE", "service " + role + " not started: " + failure);
                runtime.startupLog().record("WARN", role + ": " + failure.getMessage());
            }
        }
        if (desktop.graphics()) launch("compositor", Arrays.asList("surfaceflinger"), environment);
        List<String> paths = new ArrayList<>();
        paths.add(new File(directory, "framework-check.dex").getPath());
        JSONArray classpath = files.inputs().getJSONArray("systemServerClassPath");
        for (int i = 0; i < classpath.length(); ++i) paths.add(classpath.getString(i));
        environment.put("SYSTEMSERVERCLASSPATH", String.join(":", paths.subList(1, paths.size())));
        desktopClassPath = String.join(":", paths);
        if (rom.enabled()) environment.put("CLASSPATH", String.join(":", paths));
        rom.configure(environment);
        desktopEnvironment = new HashMap<>(environment);
        Map<String, String> serverEnvironment = new HashMap<>(environment);
        if (!rom.enabled()) serverEnvironment.put("CLASSPATH", String.join(":", paths));
        if (files.frameworkSdk() >= 35) {
            // liba15vm-jni-register.so resolves android::AndroidRuntime::startReg
            // through this path when RTLD_DEFAULT cannot see the LD_PRELOADed
            // libandroid_runtime.so. A9/A10 never load that bridge.
            serverEnvironment.put("A15VM_ANDROID_RUNTIME",
                    files.nativeFile("libandroid_runtime.so").getPath());
        }
        String bootEntry = rom.enabled() ? rom.bootEntry() : desktop.bootEntry();
        // dalvikvm resolves com.android.server.SystemServer from -cp, so the class
        // path has to be framework-check.dex + SYSTEMSERVERCLASSPATH (services.jar,
        // service-art.jar, com.android.location.provider.jar) — the same set the
        // system_server process gets on a device. app_process ignores -cp and keeps
        // reading CLASSPATH, so A9/A10 are untouched.
        Process server = launch("server",
                files.frameworkSdk() >= 35
                        ? dalvikArguments("-Da10vm.bootSetupTimeoutMs=210000",
                                String.join(":", paths), bootEntry)
                        : appProcessArguments("-Da10vm.bootSetupTimeoutMs=210000", bootEntry),
                serverEnvironment);
        int startupSeconds = desktop.artStartupSeconds();
        try {
            int pid = awaitPid(server, "desktop_pid", startupSeconds);
            RuntimeIdentity.verifyProcess(pid, files);
            // UI-0: SurfaceFlinger has had the display up for several seconds by the
            // time system_server prints its pid, so the guest's own bootanimation can
            // create its surface now. It paints the first real guest pixels while the
            // framework is still booting; the launcher covers it when it exits.
            startBootAnimation(environment);
            boolean firstBootDesktop = !new File(new File(directory, "rootfs"),
                    "data/system/users/0/settings.db").isFile();
            // 统一机制（A9/A10/A15 同一套，第 82 轮维护者要求）：
            //   · 框架进程一起来就启动触摸通道，并且**不阻塞**在桌面确认上；
            //   · 桌面确认只是读数（checkDesktopInBackground 内部失败只记 WARN）。
            // 触摸自己会重试（NativeGraphicsSession 有独立的输入重试循环），
            // 所以这里早启动一次即可，失败不代表会话失败。
            if (inputSocket != null) startInput(environment);
            Thread desktopCheck = new Thread(
                    () -> checkDesktopInBackground(rom, environment, firstBootDesktop),
                    "guest-desktop-check");
            desktopCheck.setDaemon(true);
            desktopCheck.start();
        } catch (Exception failure) {
            StringBuilder detail = new StringBuilder();
            for (String role : Arrays.asList("client", "input", "gpu", "composer", "allocator", "installer", "compositor", "server")) {
                String text = readLog(role);
                detail.append('\n').append(role).append(":\n").append(text.substring(Math.max(0, text.length() - 2200)));
            }
            throw new IllegalStateException(runtime.label() + " 桌面启动阻塞：" + failure.getMessage() + "\n" + detail, failure);
        }
    }

    /**
     * 桌面确认（F2 读数）：只写日志，绝不把会话判死，也绝不阻塞画面与触摸。
     */
    private void checkDesktopInBackground(RomBootProfile rom, Map<String, String> environment,
                                          boolean firstBoot) {
        int attempts = firstBoot ? 3 : 1;
        for (int attempt = 1; attempt <= attempts; ++attempt) {
            try {
                List<String> checkCommand = rom.enabled() ? rom.checkArguments() : desktop.checkArguments();
                if (files.frameworkSdk() >= 35) {
                    // Android 15's app_process resolves the main class only through the boot
                    // class loader, so a CLASSPATH entry class (io.a10vm.guest.DesktopCheck)
                    // is never found and framework-client.log stays empty -- the same root
                    // cause the server already works around. Use dalvikvm64 with the same
                    // argv shape as the server, and let DesktopCheck install the JNI table
                    // through the project bridge (-Da10vm.jni.register=1). The extra
                    // arguments the profile may carry are appended after the class name.
                    List<String> dalvik = dalvikArguments("-Da10vm.jni.register=1",
                            desktopClassPath, desktop.checkClass(), false);
                    checkCommand = new ArrayList<>(dalvik);
                    JSONArray extraArguments = files.inputs().optJSONObject("runtimeProfile") == null
                            ? null : files.inputs().getJSONObject("runtimeProfile").optJSONArray("checkArguments");
                    for (int i = 0; extraArguments != null && i < extraArguments.length(); ++i)
                        checkCommand.add(extraArguments.getString(i));
                }
                Process check = launch("client", checkCommand, environment);
                try {
                    await("client", check, rom.enabled() ? rom.readyMarker() : desktop.readyMarker(), 205);
                    if (!check.waitFor(2, TimeUnit.SECONDS) || check.exitValue() != 0 || !isAlive())
                        throw new IllegalStateException("Desktop services did not remain running");
                    children.remove("client");
                    stopBootAnimation("desktop-ready");
                    break;
                } catch (Exception failure) {
                    if (check.isAlive()) check.destroyForcibly();
                    children.remove("client");
                    if (!isAlive()) throw failure;
                    // 检查进程自己打印过 ready 标记、只是没能在 2 秒内退干净时，桌面其实已经起来了：
                    // 这时也必须把开机动画收掉，否则动画会一直盖在桌面上（真机表现："卡在第二屏"）。
                    if (readLog("client").contains(files.inputs().has("romBoot")
                            ? rom.readyMarker() : desktop.readyMarker())) {
                        stopBootAnimation("desktop-ready");
                        break;
                    }
                    if (attempt == attempts) {
                        runtime.startupLog().record("WARN",
                                "桌面确认未通过，继续等待首帧：" + failure.getMessage());
                        break;
                    }
                    runtime.startupLog().record("WAIT",
                            "桌面尚未就绪（首次初始化中），准备第 " + (attempt + 1) + " 次检查");
                    Thread.sleep(15000);
                }
            } catch (Exception failure) {
                // 只是读数：绝不外抛，绝不把已经开机的会话判死。
                runtime.startupLog().record("WARN", "桌面确认未通过：" + failure.getMessage());
                return;
            }
        }
    }

    private void startService(JSONObject service, Map<String, String> environment) throws Exception {
        Map<String, String> serviceEnvironment = new HashMap<>(environment);
        serviceEnvironment.put("A10VM_GUEST_UID", service.getString("uid"));
        serviceEnvironment.put("A10VM_GUEST_GID", service.getString("gid"));
        serviceEnvironment.put("LD_PRELOAD", files.nativeFile(service.getString("preload")).getPath() + ":" +
                environment.get("LD_PRELOAD"));
        String role = service.getString("role");
        List<String> serviceCommand = new ArrayList<>();
        serviceCommand.add(service.getString("logical"));
        JSONArray arguments = service.optJSONArray("arguments");
        if (arguments != null) for (int j = 0; j < arguments.length(); ++j)
            serviceCommand.add(arguments.getString(j));
        Process serviceProcess = launch(role, serviceCommand, serviceEnvironment);
        watchServiceExit(role, serviceProcess);
        romServices.add(role);
    }

    /**
     * A service that dies silently is invisible: round 157 lost logd this way — the three
     * sockets were there, the process was gone, and its own log was 0 bytes, so nothing
     * recorded why. Record the exit code once the process ends. This only observes a dead
     * child, so it cannot change how the service runs.
     */
    private void watchServiceExit(String role, Process process) {
        Thread thread = new Thread(() -> {
            try {
                int code = process.waitFor();
                runtime.startupLog().record("EXIT", role + ": code=" + code);
            } catch (Throwable failure) {
                runtime.startupLog().record("WARN", role + " exit watch failed: " + failure);
            }
        }, "a15-service-watch-" + role);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 触摸通道。首次开机时 SystemServer 可能还在初始化，input 进程会失败；这里不把它
     * 当成会话致命错误，由 [ensureInput] 在系统就绪后补上。
     */
    private void startInput(Map<String, String> environment) {
        if (inputStarted || inputSocket == null) return;
        Process input = null;
        try {
            List<String> command = new ArrayList<>();
            if (files.frameworkSdk() >= 35) {
                // Android 15's app_process cannot even create a runtime without an explicit
                // boot image (real device 2026-10-29 round 82: the input child died with
                //   "Check failed: !location.empty() No mainline framework library found"
                //   at art::GetDefaultBootImageLocation -> ParsedOptions::DoParse
                // before reaching DesktopInput). Use the same dalvikvm64 argv as the server
                // and the client; DesktopInput installs the JNI table itself when the host
                // passes -Da10vm.jni.register=1.
                command.addAll(dalvikArguments("-Da10vm.jni.register=1",
                        desktopClassPath, desktop.inputClass(), false));
                command.add(inputSocket.getPath());
            } else {
                command.addAll(Arrays.asList("app_process64", "-Xnoimage-dex2oat",
                        "/system/bin", desktop.inputClass(), inputSocket.getPath()));
            }
            input = launch("input", command, environment);
            await("input", input, "desktop_input_ready=1", Math.min(15, desktop.artStartupSeconds()));
            RuntimeIdentity.verifyProcess(processPid("input", "desktop_input_pid"), files);
            inputStarted = true;
            runtime.startupLog().record("READY", "input: desktop_input_ready=1");
        } catch (Exception failure) {
            if (input != null && input.isAlive()) input.destroyForcibly();
            children.remove("input");
            logs.remove("input");
            runtime.startupLog().record("WARN", "输入通道尚未就绪：" + failure.getMessage());
        }
    }

    /**
     * UI-0: start the image's own boot animation.
     *
     * <p>A real device runs {@code /system/bin/bootanimation} from
     * {@code system/etc/init/bootanim.rc} while {@code system_server} boots, and
     * SurfaceFlinger stops it when the first window is ready. This runtime has no
     * init, ships {@code debug.sf.nobootanimation=1}, and redroid has no
     * {@code /system/media/bootanimation.zip}, so until now the first guest pixel
     * could only be a framework window — i.e. the screen stayed on the host's own
     * start-up log until SystemUI/Launcher appeared.
     *
     * <p>The binary is launched with the same environment as SurfaceFlinger (guest
     * loader, {@code A10VM_GPU_SOCKET}, private binder) and the artwork staged by
     * {@code scripts/make-bootanimation.py}. It is never a session-critical role:
     * a failure here is a WARN, not a boot failure.
     */
    private void startBootAnimation(Map<String, String> environment) {
        try {
            File zip = new File(new File(directory, "rootfs"), "system/media/bootanimation.zip");
            if (!zip.isFile()) {
                runtime.startupLog().record("WARN", "bootanim: no /system/media/bootanimation.zip");
                return;
            }
            Map<String, String> bootEnvironment = new HashMap<>(environment);
            launch("bootanim", Arrays.asList("bootanimation"), bootEnvironment);
            runtime.startupLog().record("READY", "bootanim: guest boot animation started");
            Thread guard = new Thread(() -> {
                try { Thread.sleep(150_000); } catch (InterruptedException interrupt) { return; }
                stopBootAnimation("timeout");
            }, "a15-bootanim-guard");
            guard.setDaemon(true);
            guard.start();
        } catch (Throwable failure) {
            runtime.startupLog().record("WARN", "bootanim unavailable: " + failure.getMessage());
        }
    }

    /** Stops the boot animation once the launcher is up (or on timeout / session close). */
    private void stopBootAnimation(String reason) {
        Process boot = children.get("bootanim");
        if (boot == null || !boot.isAlive()) {
            // 以前这里直接 return，于是"动画没停"和"动画早就停了"在日志里长得一样。
            // 会话结束时收尾也会走到这里，所以只记一行 INFO，不冒充失败。
            runtime.startupLog().record("STOP", "bootanim: " + reason + "（进程已退出）");
            return;
        }
        boot.destroy();
        try {
            if (!boot.waitFor(1500, TimeUnit.MILLISECONDS)) boot.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        runtime.startupLog().record("STOP", "bootanim: " + reason);
    }

    /** 首帧出现后或系统就绪后补启动触摸通道；幂等，失败可重试。 */
    public boolean ensureInput() {
        if (inputStarted) return true;
        if (desktopEnvironment == null || inputSocket == null) return false;
        startInput(desktopEnvironment);
        return inputStarted;
    }

    /**
     * `java.library.path` becomes the classloader namespace search path: ART
     * passes it to `android_create_namespace()`, which resolves every entry
     * through the guest path layer.  API 28 libnativeloader does not keep host
     * state paths, so the honest API 28 value is the guest framework directory
     * that the ROM itself uses.  Android 10 resolves the host path, so it keeps
     * the state directory.
     */
    private String javaLibraryPath() throws Exception {
        if (files.inputs().optInt("frameworkSdk", 29) <= 28) return "/vendor/lib64:/system/lib64";
        return new File(directory, "lib").getPath();
    }

    /**
     * DEX2OATBOOTCLASSPATH as ART's *boot image derivation* needs it.
     *
     * <p>When ART is started without {@code -Ximage}, {@code
     * art::GetDefaultBootImageLocation()} asks {@code
     * art::GetFirstMainlineFrameworkLibraryFilename()} for the first boot class path
     * entry that is <em>not</em> covered by DEX2OATBOOTCLASSPATH: it verifies that
     * BOOTCLASSPATH starts with DEX2OATBOOTCLASSPATH, drops that prefix, splits the
     * remainder on ':' and takes the first element. With the two variables equal (the
     * host exported the same list for both) the remainder is empty and ART aborts:
     *
     * <pre>
     *   Check failed: !location.empty() No mainline framework library found
     * </pre>
     *
     * <p>That is what killed the image's own artd. Android 15 runs every dexopt through
     * artd, so artd registered, aborted on its first request, and PackageManagerService
     * blocked forever waiting for the reply -- the {@code Blocked in handler on
     * PackageManager (PackageManager) for 150s} ANR, together with the user-visible
     * "import times out / app storage page never loads / cannot uninstall / icons only
     * after a reboot / cannot clear data" cluster (real device 2026-10-31, instance
     * 1793443256048: framework-artd.log ends with this check, server.log prints
     * "Waited one second for artd" ~600 times in ten minutes).
     *
     * <p>dalvikvm64 children always pass {@code -Ximage}, so they never run this
     * derivation; artd cannot be given {@code -X} options (its own argv parser rejects
     * them, measured 2026-10-31 on instance 1793440502763), so the environment has to
     * satisfy the helper. Keeping the leading ART APEX jars as the prefix leaves
     * {@code /system/framework/framework.jar:...} as the remainder, i.e. the mainline
     * jar the helper wants, and artd then derives the same image list the device
     * derives (/system/framework/arm64/boot.art + boot-framework.art).
     *
     * <p>The trailing ':' is deliberate: the helper does not skip the separator, and no
     * process opens DEX2OATBOOTCLASSPATH as a class path (dalvikvm and dex2oat read
     * BOOTCLASSPATH), so the empty last token is inert. Falls back to the previous
     * behaviour when the boot class path has no ART APEX prefix at all.
     */
    private static String dex2oatBootClassPath(List<String> boot) {
        int end = 0;
        for (String entry : boot) {
            if (!entry.startsWith("/apex/com.android.art/javalib/")) break;
            ++end;
        }
        if (end == 0 || end >= boot.size()) return String.join(":", boot);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < end; ++i) result.append(boot.get(i)).append(':');
        return result.toString();
    }

    private int awaitPid(Process server, String key, int seconds) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
        while (!readLog("server").contains(key + "=")) {
            if (!server.isAlive() || SystemClock.elapsedRealtime() >= deadline)
                throw new IllegalStateException("Missing desktop process identity");
            Thread.sleep(10);
        }
        return processPid("server", key);
    }

    /**
     * `app_process64` argv for the desktop `server` role.
     *
     * Android 15's ART derives the default boot image location from BOOTCLASSPATH
     * minus DEX2OATBOOTCLASSPATH.  With the two equal (which is what the host
     * exports) the remainder is empty, so the derivation aborts inside
     * ParsedOptions::DoParse:
     *   Check failed: !location.empty() No mainline framework library found
     * Passing -Ximage explicitly bypasses that derivation.  The value is a
     * `<dir>/<basename>` pair: ART expands it to `<dir>/<isa>/<basename>`, i.e.
     * /system/framework/boot.art -> /system/framework/arm64/boot.art.
     * -Xgc:CMC is required as well: the image's boot.oat header records
     * `concurrent-copying false`, while the arm64 default collector is CC (read
     * barrier on), so the image is rejected with
     *   ValidateOatFile found read barrier state mismatch (oat file: false, runtime: true)
     * A9/A10 (frameworkSdk 28/29) keep their exact old argv.
     * Evidence: _audit/coding-log/2026-10-27-CL-A15-boot-image定位与两秒实验法.md
     */
    private List<String> appProcessArguments(String extra, String... tail) throws Exception {
        List<String> arguments = new ArrayList<>();
        arguments.add("app_process64");
        arguments.add("-Xnoimage-dex2oat");
        if (files.frameworkSdk() >= 35) {
            arguments.add("-Ximage:/system/framework/boot.art");
            arguments.add("-Xgc:CMC");
        }
        arguments.add(extra);
        arguments.add("-Djava.library.path=" + javaLibraryPath());
        arguments.add("/system/bin");
        arguments.addAll(Arrays.asList(tail));
        return arguments;
    }

    /**
     * Android 15 starts the desktop server with dalvikvm64, not app_process64.
     *
     * A15's app_process/RuntimeInit only resolves the main class through the
     * boot loader, so an entry class that lives in CLASSPATH (inside the APK)
     * is never found: the process aborts with
     *   java.lang.NoClassDefFoundError: Class not found using the boot class loader
     * before A15Boot.main runs. Adding the entry jar to BOOTCLASSPATH, using
     * -Xbootclasspath, -classpath, or a generated boot image was measured and
     * does not change that line (see
     * _audit/coding-log/2026-10-27-CL-A15-app_process主类语义与镜像image不一致.md).
     *
     * dalvikvm64 resolves the main class from -cp, and A15Boot calls
     * System.loadLibrary("a15vm-jni-register") whose JNI_OnLoad calls the
     * exported android::AndroidRuntime::startReg(JNIEnv*) — the same JNI table
     * app_process would install. A9/A10 keep their app_process path.
     *
     * `-cp` must carry SYSTEMSERVERCLASSPATH as well, exactly like the
     * system_server process on a device: the boot class loader has the framework
     * jars, but com.android.server.SystemServer lives in services.jar. With only
     * framework-check.dex on the path A15Boot gets
     *   java.lang.ClassNotFoundException: com.android.server.SystemServer
     * (real-device 2026-10-27 19:47, instance 1793101335022) right after the JNI
     * table became ready.
     *
     * The system-server jars on the *boot* class path were tried on 2026-10-27
     * 20:50 and did not change the failure line at all (instance 1793105421070:
     * same NCDFE, same point). They are reverted here on purpose: adding them
     * changes which class loader defines SystemServer, which would confuse the
     * next diagnosis. If a later probe shows the class really is being looked up
     * with the boot class loader, re-add them and re-measure.
     *
     * `-verbose:class` is a temporary diagnostic (A15 only): ART's NCDFE for a
     * failed boot-loader lookup carries no class name, so the last successfully
     * loaded class before the failure is the only remaining pointer. Remove it
     * once the failing class is known.
     */
    private List<String> dalvikArguments(String extra, String classPath, String bootEntry) throws Exception {
        return dalvikArguments(extra, classPath, bootEntry, true);
    }

    /**
     * @param diagnostics when false, no ART instrumentation is added (no
     *     {@code -verbose:class}, no {@code runtimeProfile.serverArtArguments}). Used for
     *     short-lived helper processes such as the desktop check, where a flood light only
     *     delays the marker line they exist to print.
     */
    private List<String> dalvikArguments(String extra, String classPath, String bootEntry,
                                         boolean diagnostics) throws Exception {
        List<String> arguments = new ArrayList<>();
        arguments.add("dalvikvm64");
        arguments.add("-Ximage:/system/framework/boot.art");
        arguments.add("-Xgc:CMC");
        JSONArray boot = files.inputs().getJSONArray("bootClassPath");
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < boot.length(); ++i) paths.add(boot.getString(i));
        // 2026-10-27 25th round: the system-server jars were put on the boot class
        // path (keeping them on -cp) as a *targeted* re-test of the one instruction
        // the sentinel bisect had isolated:
        //   services.jar classes.dex 0x0179 `new-instance SystemServer$$ExternalSyntheticLambda0`
        // Result (instance 1793109132349, APK 9593CA16): boot class path really became
        // 19 jars and SystemServer really became a boot class
        // (`a15_systemserver_class_loader=java.lang.BootClassLoader@c379d4b`), yet
        // `a15_post_wtf_field` still equalled `a15_wtf_sentinel` and the NCDFE text
        // was byte-identical. So the failing lookup is NOT the lambda descriptor —
        // making it boot-visible changes nothing. Reverted to the device-shaped
        // 16-jar boot class path; do not re-run this configuration.
        arguments.add("-Xbootclasspath:" + String.join(":", paths));
        // Both -verbose:class and -Xmethod-trace are flood lights; keep exactly one on so
        // the log that carries the judgement line is not buried by the other.
        //
        // The previous version inverted this: `diagnostics == false` produced an empty
        // profile list, which then fell into the `isEmpty()` branch and ADDED
        // `-verbose:class` — the exact opposite of the documented behaviour. The desktop
        // check therefore ran with the class-load flood light on, which both slows its
        // startup and buries its first marker. Use a null sentinel to mean "no
        // instrumentation at all".
        List<String> artDiagnostics = diagnostics ? desktop.serverArtArguments() : null;
        if (artDiagnostics != null) {
            if (artDiagnostics.isEmpty()) {
                arguments.add("-verbose:class");
            } else {
                arguments.addAll(artDiagnostics);
            }
        }
        arguments.add(extra);
        arguments.add("-Djava.library.path=" + javaLibraryPath());
        arguments.add("-cp");
        arguments.add(classPath);
        arguments.add(bootEntry);
        return arguments;
    }

    private List<String> command(String role, String nonce) {
        return Arrays.asList("app_process64", "-Xnoimage-dex2oat",
                "/system/bin", "io.a10vm.guest.Framework" + role, nonce);
    }

    private Map<String, String> registryBase() throws Exception {
        File libraries = new File(directory, "registry-lib");
        JSONObject mapping = files.inputs().getJSONObject("registryLibraries");
        for (java.util.Iterator<String> names = mapping.keys(); names.hasNext();) {
            String name = names.next();
            files.link(new File(libraries, name), files.nativeFile(mapping.getString(name)).getPath());
        }
        File config = new File(directory, "registry.config.txt");
        String nativePath = files.nativeDirectory.getCanonicalPath();
        // 注册表辅助程序（property-service / binder-broker）同样从客体 .so 的物理根执行，
        // 并对那里的桥做 LD_PRELOAD，所以这份 ld.config 也要放行同一个物理根。
        StringBuilder registryPermitted = new StringBuilder(nativePath + ":" + directory.getPath());
        StringBuilder registryGuest = new StringBuilder(nativePath);
        for (File root : files.libRoots()) {
            registryPermitted.append(':').append(root.getCanonicalPath());
            registryGuest.append("\ndir.registry = ").append(root.getCanonicalPath());
        }
        String text = "dir.registry = " + registryGuest + "\n[registry]\n"
                + "namespace.default.isolated = true\nnamespace.default.search.paths = " + libraries.getPath() + "\n"
                + "namespace.default.permitted.paths = " + registryPermitted + "\n";
        Files.write(config.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Map<String, String> environment = runtime.environment();
        environment.put("A10VM_LD_CONFIG", config.getPath());
        return environment;
    }

    private Map<String, String> registryEnvironment(File socket) throws Exception {
        Map<String, String> environment = registryBase();
        environment.put("A10VM_BINDER_PATH", socket.getPath());
        return environment;
    }

    private Process launch(String role, List<String> arguments, Map<String, String> environment) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(files.nativeFile("ld-android.so").getPath());
        command.add(files.nativeFile(arguments.get(0)).getPath());
        command.addAll(arguments.subList(1, arguments.size()));
        File log = new File(directory, "framework-" + role + ".log");
        logs.put(role, log);
        ProcessBuilder builder = new ProcessBuilder(command).directory(new File(directory, "rootfs")).redirectErrorStream(true).redirectOutput(log);
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process child = builder.start();
        children.put(role, child);
        // Note: java.lang.Process on Android has no pid() (that lives on android.os.Process),
        // so the identity is carried by the process's own log + its exit code, which
        // await() now reports for the waited-for child before looking at dependencies.
        runtime.startupLog().record("SPAWN", role + " process started");
        return child;
    }

    private void await(String role, Process child, String marker, int seconds) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
        long nextNotice = SystemClock.elapsedRealtime() + 3000;
        while (!readLog(role).contains(marker + "\n")) {
            // Check the process we are waiting for FIRST. The old order checked the
            // dependencies first, so when the server died at the same moment the loop
            // reported "Desktop server exited" and the client's own exit code was never
            // recorded — which is exactly how the F track kept looking like
            // "0-byte log, no explanation" instead of "client exited with code N".
            if (!child.isAlive()) throw new IllegalStateException("Framework " + role
                    + " exited: code=" + child.exitValue() + " " + readLog(role));
            if (children.containsKey("compositor")) {
                for (String service : Arrays.asList("gpu", "composer", "allocator", "compositor", "host-devices", "server")) {
                    Process dependency = children.get(service);
                    if (dependency != null && !dependency.isAlive()) {
                        // The dependency (usually the server) failed. Give the process we
                        // are waiting for a short grace period BEFORE reporting it: a
                        // fast-failing server otherwise destroys the client's evidence
                        // window. Real numbers from 2026-10-28 (instance 1793161167883):
                        // client spawned at 8.43 s, server died at 9.35 s, and
                        // framework-client.log was still empty — 0.92 s is shorter than
                        // dalvikvm64's own startup, so the client had no chance to write
                        // its first line. This grace period is diagnostic only: it does
                        // not keep the session alive, it just lets the log be written.
                        long graceUntil = SystemClock.elapsedRealtime() + 3000;
                        while (child.isAlive() && SystemClock.elapsedRealtime() < graceUntil) {
                            Thread.sleep(50);
                        }
                        throw new IllegalStateException("Desktop " + service + " exited: "
                                + exitStatus(dependency) + " " + readLog(service));
                    }
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("Framework " + role + " timeout: " + readLog(role));
            if (SystemClock.elapsedRealtime() >= nextNotice) {
                runtime.startupLog().record("WAIT", role + ": waiting for " + marker);
                nextNotice = SystemClock.elapsedRealtime() + 3000;
            }
            Thread.sleep(10);
        }
        runtime.startupLog().record("READY", role + ": " + marker);
    }

    /**
     * Exit code of a child that has already terminated. On Linux a process killed by
     * signal N reports 128+N, so this one field separates the three C2 hypotheses
     * (OOM kill / host-side crash / the process returning on its own) without
     * another session. Diagnostic only: it neither signals nor keeps anything alive.
     */
    private static String exitStatus(Process child) {
        try {
            return "code=" + child.exitValue();
        } catch (Throwable failure) {
            return "code=unknown(" + failure.getClass().getSimpleName() + ")";
        }
    }

    public boolean isAlive() {
        // 只以"框架必需的角色"为准。第 82 轮：原来这里遍历**所有**子进程，任何一个
        // 一次性角色（input/client）或早已干完活的镜像守护进程退出，都会让宿主在
        // 客体已经开机的情况下把整台虚拟机判成"系统进程退出"，用户就被踢回启动画面。
        // 外围角色退出由 launch() 自己记一行 WARN，不构成会话死亡。
        List<String> required = new ArrayList<>(Arrays.asList("properties", "broker", "manager"));
        if (desktop == null || desktop.hwbinder()) required.addAll(Arrays.asList("hwbroker", "hwmanager"));
        required.add("server");
        return required.stream()
                .map(children::get).allMatch(child -> child != null && child.isAlive());
    }

    /**
     * Capture the live system_server stacks before returning the saved logs.
     * This is intentionally attached to the user's diagnostics action so a
     * freeze can be captured without adb or a guest shell.
     */
    public String diagnostics() {
        String capture = "system_server thread dump: not requested";
        // Keep the new signal behavior scoped to the A9 desktop; shared A10
        // diagnostics continue to be a passive log read.
        if (!api28DesktopSession)
            return RuntimeDiagnostics.collect(directory);
        Process server = children.get("server");
        if (server != null && server.isAlive()) {
            File serverLog = logs.get("server");
            long previousLength = serverLog != null && serverLog.isFile() ? serverLog.length() : -1;
            try {
                int pid = processPid("server", desktopSession ? "desktop_pid" : "framework_pid");
                Os.kill(pid, OsConstants.SIGQUIT);
                long deadline = SystemClock.elapsedRealtime() + 2500;
                while (server.isAlive() && SystemClock.elapsedRealtime() < deadline
                        && serverLog != null && serverLog.length() <= previousLength) {
                    Thread.sleep(50);
                }
                long newLength = serverLog != null && serverLog.isFile() ? serverLog.length() : previousLength;
                capture = newLength > previousLength
                        ? "system_server thread dump: SIGQUIT sent to pid=" + pid + ", log appended="
                                + (newLength - previousLength) + " bytes"
                        : "system_server thread dump: SIGQUIT sent to pid=" + pid
                                + ", no framework-server.log growth observed within 2.5s";
            } catch (Throwable failure) {
                capture = "system_server thread dump: capture failed: " + failure;
            }
        } else {
            capture = "system_server thread dump: server process is not running";
        }
        return "--- live thread capture ---\n" + capture + "\n"
                + RuntimeDiagnostics.collect(directory);
    }

    public void setDisplayInteractive(boolean interactive) throws Exception {
        if (!isAlive() || desktopEnvironment == null) return;
        // 每次显示/唤醒虚拟机时都修一次 /data/app 的写位。安装路径里的
        // makeDataApksReadOnly() 只在 commit 成功后执行，而 commit 可能因为 binder 连接
        // 断开没能返回（实例 1793435241273，errno=111），APK 却已经落盘——下一次启动
        // 点开就还是 "Writable dex file"。这里与会话生命周期绑定，覆盖这种情况。
        makeDataApksReadOnly();
        new GuestDisplayPower(directory, files.nativeFile("ld-android.so"), files.nativeFile("app_process64"),
                desktopEnvironment).setInteractive(interactive);
    }

    public String installApk(File apk) throws Exception {
        return installApks(java.util.Collections.singletonList(apk));
    }

    /**
     * 通过客体自己的 `package` 服务安装 APK。
     *
     * 预置的 Android 10 framework dex 里没有 `GuestInstall`（A9 的 dex 有），所以这里不再
     * 依赖那个入口类，而是用两边 dex 都带的 `GuestCommand` 走同一套 PackageInstaller
     * 会话：create → write（每个 split 一次）→ commit，失败则 abandon。命令序列与
     * `GuestInstall` 完全一致，只是把“一个进程里做完”拆成宿主可控的几步，安装结果仍由
     * 客体 `PackageManagerService` 决定。
     */
    public String installApks(List<File> apks) throws Exception {
        if (apks.isEmpty()) throw new IllegalArgumentException("Expected APK files");
        if (!isAlive() || desktopEnvironment == null) throw new IllegalStateException("请先启动 " + runtime.label());
        long total = 0;
        for (File apk : apks) {
            if (!apk.isFile() || apk.length() == 0) throw new IllegalArgumentException("Missing APK: " + apk);
            total = Math.addExact(total, apk.length());
        }
        Map<String, String> environment = packageEnvironment();
        // Android 15's commit path runs the platform's dexopt for every installed APK
        // inside this container. The measured failure on 2026-10-31 16:08-16:10
        // (instance 1793434047234) was the 150 s commit limit: the child had the
        // session and was still working when the host gave up. A9/A10 finish in
        // seconds, so the limit is a container-speed guard, not a correctness check.
        // 0 = wait until the guest process exits (A15). A timeout here used to reach the
        // finally-block below and install-abandon a session that was still writing the APK
        // to /data/app, which is exactly how "装完却要重启虚拟机才有图标" was produced.
        int commitSeconds = files.frameworkSdk() >= 35 ? 0 : 150;
        int writeSeconds = files.frameworkSdk() >= 35 ? 600 : 130;
        String created = runPackageCommand(environment, "install-create", 90,
                new String[] {"package", "install-create", "-r", "-t", "--user", "0", "-S", Long.toString(total)});
        String session = InstallSessionId.require(created);
        boolean committed = false;
        try {
            for (int i = 0; i < apks.size(); i++) {
                File apk = apks.get(i);
                Map<String, String> write = new HashMap<>(environment);
                // GuestCommand 从 A10VM_COMMAND_INPUT 读取 install-write 的 stdin。
                write.put("A10VM_COMMAND_INPUT", apk.getCanonicalPath());
                runPackageCommand(write, "install-write", writeSeconds,
                        new String[] {"package", "install-write", "-S", Long.toString(apk.length()),
                                session, i == 0 ? "base.apk" : "split-" + i + ".apk", "-"});
            }
            runPackageCommand(environment, "install-commit", commitSeconds,
                    new String[] {"package", "install-commit", session});
            committed = true;
            makeDataApksReadOnly();
            refreshLauncher(environment);
            return "guest_install_complete=1\n" + created;
        } finally {
            if (!committed) {
                try {
                    runPackageCommand(environment, "install-abandon", 30,
                            new String[] {"package", "install-abandon", session});
                } catch (Exception cleanup) {
                    android.util.Log.w("A10VM", "Abandon install session: " + cleanup.getMessage());
                }
            }
        }
    }

    private boolean waitForever(Process child) {
        try { child.waitFor(); return true; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return !child.isAlive();
        }
    }

    /**
     * 让桌面重新扫描已安装应用。
     *
     * <p>安装完成后 server log 里 `PACKAGE_ADDED` = 0 行（实例 1793434047234），
     * Launcher3 的 LauncherApps 回调没有把它刷新出来，于是"包里已经装在 /data/app，
     * 桌面却没有图标，要重启虚拟机才出现"。这里在安装成功后用 system uid 通过
     * activity 服务重启 home：与"重启虚拟机"走的是同一条重扫路径，但只影响桌面进程。
     * 失败只记录，不影响安装结果。
     */
    private void refreshLauncher(Map<String, String> environment) {
        try {
            runPackageCommand(environment, "launcher-refresh", 60,
                    new String[] {"activity", "force-stop", "com.android.launcher3"});
            android.util.Log.i("A10VM_NATIVE", "install: launcher refresh requested");
        } catch (Exception failed) {
            android.util.Log.w("A10VM_NATIVE", "install: launcher refresh failed: " + failed.getMessage());
        }
    }

    /**
     * 去掉新装 APK 的写位，否则 Android 15 直接拒绝加载：
     *
     * <pre>
     *   java.lang.SecurityException: Writable dex file
     *     '/data/app/~~1BwGoS-…/tv.danmaku.bili-…/base.apk' is not allowed.
     *   at dalvik.system.DexFile.openDexFileNative
     *   … at android.app.ActivityThread.handleBindApplication
     *   … at io.a10vm.guest.A15GuestApplication.main
     * </pre>
     *
     * 真机证据：客人 dropbox 的 `data_app_crash@1793434612075.txt`（tv.danmaku.bili，
     * 实例 1793434047234，2026-10-31 16:16）与 `data_app_crash@1793434741463.txt`
     * （com.liuzh.deviceinfo，16:19）。真机上 /data/app 是 0644 root:root，应用进程
     * 不是属主，所以“属主可写”不存在；这个运行时里实例文件属于宿主 App 的 uid，
     * 而客人进程正是以这个 uid 运行，于是 owner-write 就足以触发该检查。
     * 镜像里的 APK 在 scripts/prepare-a15.py 里做的是同一件事（mode &amp;= ~0o222），
     * 新装进 /data/app 的 APK 只能在这里做。
     */
    private void makeDataApksReadOnly() {
        File dataApp = new File(directory, "rootfs/data/app");
        if (!dataApp.isDirectory()) return;
        int cleared = 0;
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                     java.nio.file.Files.walk(dataApp.toPath())) {
            for (java.nio.file.Path path : walk.toArray(java.nio.file.Path[]::new)) {
                try {
                    if (!java.nio.file.Files.isRegularFile(path)) continue;
                    java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
                            java.nio.file.Files.getPosixFilePermissions(path);
                    if (permissions.remove(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
                            | permissions.remove(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                            | permissions.remove(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) {
                        java.nio.file.Files.setPosixFilePermissions(path, permissions);
                        ++cleared;
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception failed) {
            android.util.Log.w("A10VM_NATIVE", "install: data/app walk failed: " + failed);
            return;
        }
        android.util.Log.i("A10VM_NATIVE", "install: cleared write bits on " + cleared + " file(s) under /data/app");
    }

    /**
     * 安装用的进程环境：必须显式给出 CLASSPATH，`GuestCommand` 才找得到；旧的安装路径
     * 直接复用 desktopEnvironment，而 A9/A10 的桌面环境里都没有 CLASSPATH，所以即使
     * dex 里有入口类也会以 "Could not find class" 失败。
     */
    private Map<String, String> packageEnvironment() throws Exception {
        Map<String, String> environment = new HashMap<>(desktopEnvironment);
        List<String> paths = new ArrayList<>();
        paths.add(new File(directory, "framework-check.dex").getPath());
        JSONArray classpath = files.inputs().getJSONArray("systemServerClassPath");
        for (int i = 0; i < classpath.length(); ++i) paths.add(classpath.getString(i));
        environment.put("CLASSPATH", String.join(":", paths));
        environment.put("A10VM_GUEST_UID", "1000");
        environment.put("A10VM_GUEST_GID", "1000");
        // GuestCommand 从 A10VM_COMMAND_INPUT 打开 shell 命令的 stdin。install-create /
        // install-commit 不需要输入，但仍要有一个客体读得到的空文件：容器里没有真实
        // 的 /dev/null 节点，直接落到默认路径会让进程在打开 stdin 时失败。
        File empty = new File(directory, "package-input.empty");
        if (!empty.isFile()) Files.write(empty.toPath(), new byte[0]);
        environment.put("A10VM_COMMAND_INPUT", empty.getPath());
        return environment;
    }

    /**
     * Guest argv for a one-shot package command (install-create / install-write /
     * install-commit / install-abandon).
     *
     * <p>Android 15 cannot run this through {@code app_process64}: that binary derives
     * its boot image from BOOTCLASSPATH before RuntimeInit, and aborts there —
     *
     * <pre>
     *   GUEST_LOG 7 : Check failed: !location.empty() No mainline framework library found
     *   GUEST_LOG 7 : Runtime aborting...
     *   native: ... art::GetDefaultBootImageLocation+256
     *   native: ... art::ParsedOptions::DoParse+1516
     *   native: ... liba15vm-app_process64.so (main+1200)
     * </pre>
     *
     * <p>Real device 2026-10-31 15:49, instance 1793432154923,
     * {@code vm/framework-install-create.log}: importing 哔哩哔哩 into Android 15 failed
     * with that abort (0/1), while the same APK imported fine into Android 10 at
     * 15:50 (instance 1793432840088, {@code Success: created install session [...]} +
     * {@code guest_command_result=0}), because there {@code app_process64} is the right
     * entry.
     *
     * <p>Android 15 therefore uses the same launcher as {@code A15AppLaunch}: dalvikvm64
     * with an explicit {@code -Ximage}/{@code -Xgc}/{@code -Xbootclasspath} and the
     * project app wrapper, which then runs {@code io.a10vm.guest.GuestCommand}. A9/A10
     * keep the measured argv byte-for-byte.
     */
    private List<String> packageArguments(String[] arguments) throws Exception {
        List<String> command = new ArrayList<>();
        if (files.frameworkSdk() < 35) {
            command.addAll(Arrays.asList("app_process64", "-Xnoimage-dex2oat",
                    "-Djava.library.path=" + javaLibraryPath(), "/system/bin",
                    "io.a10vm.guest.GuestCommand"));
        } else {
            JSONObject profile = files.inputs().optJSONObject("runtimeProfile");
            String wrapper = profile == null ? "io.a10vm.guest.A15GuestApplication"
                    : profile.optString("appClass", "io.a10vm.guest.A15GuestApplication");
            command.addAll(dalvikArguments("-Xnoimage-dex2oat",
                    new File(directory, "framework-check.dex").getPath(), wrapper, false));
            command.add(Integer.toString(files.frameworkSdk()));
            command.add("io.a10vm.guest.GuestCommand");
        }
        command.addAll(Arrays.asList(arguments));
        return command;
    }

    private String runPackageCommand(Map<String, String> environment, String role, int seconds,
                                     String[] arguments) throws Exception {
        List<String> command = packageArguments(arguments);
        Process child = launch(role, command, environment);
        try {
            boolean finished = seconds <= 0 ? waitForever(child) : child.waitFor(seconds, TimeUnit.SECONDS);
            if (!finished) {
                String output = readLog(role);
                String tail = output.length() > 1200 ? output.substring(output.length() - 1200) : output;
                throw new IllegalStateException("应用安装超时（" + role + "，" + seconds + " s）\n" + tail);
            }
            String output = readLog(role);
            if (child.exitValue() != 0 || !output.contains("guest_command_result=0"))
                throw new IllegalStateException(output);
            return output;
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(2, TimeUnit.SECONDS);
            }
            children.remove(role);
            logs.remove(role);
        }
    }

    public void scanMedia(String path) throws Exception {
        if (!isAlive() || desktopEnvironment == null) throw new IllegalStateException("请先启动 " + runtime.label());
        Map<String, String> environment = new HashMap<>(desktopEnvironment);
        Process scanner = launch("media-scan", Arrays.asList("app_process64", "-Xnoimage-dex2oat",
                "/system/bin", "io.a10vm.guest.GuestMediaScan", path), environment);
        try {
            if (!scanner.waitFor(35, TimeUnit.SECONDS)) throw new IllegalStateException("客体媒体扫描超时");
            String output = readLog("media-scan");
            if (scanner.exitValue() != 0 || !output.contains("guest_media_scan_complete=1"))
                throw new IllegalStateException("客体媒体扫描失败：" + output);
        } finally { scanner.destroy(); children.remove("media-scan"); }
    }

    /** 会话致命角色：它们死了这台虚拟机才是真的死了。 */
    private static final java.util.Set<String> SESSION_CRITICAL = new java.util.HashSet<>(
            Arrays.asList("properties", "broker", "manager", "hwbroker", "hwmanager", "server"));

    public String error() {
        // 只有框架必需角色退出才算会话死亡。第 82 轮：这里原来遍历所有子进程，
        // 结果 artd（镜像自带的 ART 守护进程，容器里会 code=134 自杀）一死，
        // 宿主就把刚刚 RUNNING 的虚拟机判成"框架进程退出"，用户又被踢回启动画面。
        // 一次性角色（input/client）和外围守护进程都只记 WARN，不杀会话。
        for (Map.Entry<String, Process> child : children.entrySet()) {
            if (!SESSION_CRITICAL.contains(child.getKey())) continue;
            if (!child.getValue().isAlive()) return runtime.label() + " 框架进程退出：" + child.getKey() + " code=" + child.getValue().exitValue();
        }
        return "";
    }

    /**
     * Kills every guest process that belongs to THIS instance directory.
     *
     * <p>Guest app processes are forked by A15Zygote inside the server, so they are
     * grandchildren of the host app. When the server is SIGKILLed (Watchdog,
     * RescueParty, host stop) they are reparented to init and stay alive. Real
     * device 2026-10-30 02:02: 233 guest processes / 5.9 GB RSS, 182 of them
     * orphans (ppid=1) from earlier sessions, MemAvailable down to 2.2 GB of
     * 15.5 GB; the next boot then starves and the host LMKD starts killing.
     *
     * <p>The instance directory is part of every guest process's argv
     * ({@code -Djava.library.path=<instance>/vm/lib}, {@code -cp <instance>/vm/…})
     * and of its cwd ({@code <instance>/rootfs}), so the sweep is instance-scoped
     * and never touches another VM's processes. Called before a (re)start to clean
     * a previous crash, and after close so nothing is left behind.
     */
    private void reapGuestProcesses(String stage) {
        String marker = directory.getAbsolutePath();
        int killed = 0;
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            String name = entry.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            int pid;
            try { pid = Integer.parseInt(name); } catch (NumberFormatException notPid) { continue; }
            if (pid == android.os.Process.myPid()) continue;
            try {
                boolean mine = false;
                byte[] command = Files.readAllBytes(new File(entry, "cmdline").toPath());
                if (new String(command, StandardCharsets.UTF_8).contains(marker)) mine = true;
                else {
                    String cwd = Os.readlink(new File(entry, "cwd").getPath());
                    if (cwd != null && cwd.contains(marker)) mine = true;
                }
                if (!mine) continue;
                Os.kill(pid, OsConstants.SIGKILL);
                ++killed;
            } catch (Exception skip) {
                // The process exited between listFiles() and the read: not an error.
            }
        }
        if (killed > 0) {
            runtime.startupLog().record("CLEAN", stage + ": reaped " + killed + " guest processes");
        }
    }

    private String readLog(String role) throws Exception {
        File file = logs.get(role);
        return file != null && file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
    }

    private int processPid(String role, String key) throws Exception {
        String prefix = key + "=";
        for (String line : readLog(role).split("\\n")) {
            if (line.startsWith(prefix)) {
                try { return Integer.parseInt(line.substring(prefix.length()).trim()); }
                catch (NumberFormatException ignored) { break; }
            }
        }
        throw new IllegalStateException("Missing process identity: " + key + " in " + role);
    }

    public String logs() throws Exception {
        StringBuilder output = new StringBuilder();
        for (String role : logs.keySet()) output.append("\nFRAMEWORK ").append(role).append('\n').append(readLog(role));
        return output.toString();
    }

    @Override public void close() throws Exception {
        if (logTail != null) {
            logTail.close();
            logTail = null;
        }
        List<Process> processes = new ArrayList<>(children.values());
        children.clear();
        Exception failure = null;
        for (int i = processes.size() - 1; i >= 0; --i) {
            Process child = processes.get(i);
            try {
                if (child.isAlive()) child.destroy();
                if (!child.waitFor(2, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    if (!child.waitFor(2, TimeUnit.SECONDS)) throw new IllegalStateException("Framework child stop timeout");
                }
            } catch (Exception problem) {
                child.destroyForcibly();
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        // The server's zygote children are grandchildren of this process: destroying
        // the server does not signal them, so sweep them by instance directory.
        reapGuestProcesses("session-close");
        if (failure != null) throw failure;
    }
}
