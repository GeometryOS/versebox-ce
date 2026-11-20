package io.a10vm.runtime

import android.content.Context
import android.os.SystemClock
import io.a10vm.nativedisplay.GuestLibraryFiles
import io.a10vm.nativedisplay.NativeWindow
import io.a10vm.guestruntime.GuestRuntime
import io.a10vm.guestruntime.FrameworkSession
import io.a10vm.guestruntime.HostNetworkBridge
import io.a10vm.guestruntime.HostAudioBridge
import io.a10vm.guestruntime.HostSensorBridge
import io.a10vm.guestruntime.HostVibratorBridge
import io.a10vm.guestruntime.HostBatteryBridge
import io.a10vm.guestruntime.HostBatterySysfs
import io.a10vm.guestruntime.HostCameraBridge
import io.a10vm.guestruntime.HostSharedMemoryBridge
import java.io.File
import me.kanadex.geometryos.display.DisplayGeometry
import java.util.UUID

/**
 * 启动过程被用户主动取消（点「停止」）。
 *
 * 它不是失败：VmService 抓到它时只把实例放回 IDLE，不写 ERROR 记录、不弹异常对话框。
 */
class SessionCancelled : Exception("启动已取消")

/**
 * 一台虚拟机的一次运行会话。
 *
 * 目录全部由 [VmDevice] 与 [RomSpec] 传入：状态根、客体文件系统、socket 目录互不共用，
 * 所以同一份 ROM 可以同时跑多台，旧目录与新目录也能用同一段代码。
 */
class NativeGraphicsSession(
    private val context: Context,
    val device: VmDevice,
    val rom: RomSpec,
    /**
     * 这台虚拟机**本次运行**要用的显示几何（分辨率 + DPI）。
     *
     * 由 [DisplaySettings.repository] 从注册表读出的模式 + 宿主真实几何解析而来
     * （`docs/display-settings/04` §2）。它同时下发到两处，缺一处画面就会不对：
     * 1. 宿主渲染器（`NativeWindow`）→ 决定离屏帧缓冲尺寸与上报给客体的 FB 参数；
     * 2. 客体属性（`GuestRuntime.applyDisplayDensity`）→ `ro.sf.lcd_density`。
     */
    private val display: DisplayGeometry,
) : AutoCloseable {
    private companion object {
        // The guest boots SurfaceFlinger and system_server before it composes for the first time;
        // the host GPU endpoint is live as soon as it answers, not when the guest is ready.
        const val GPU_READY_MILLIS = 30_000L
        const val FIRST_FRAME_MILLIS = 120_000L
        // 首次开机要先装 rootfs、再让 SystemServer 做完首次 dexopt（约 74 个系统包），
        // 首帧要等这段初始化结束，给足 10 分钟；之后的启动仍是 120 秒。
        const val FIRST_BOOT_FRAME_MILLIS = 600_000L
    }

    private val directory = File(context.filesDir.canonicalFile, device.windowDir)
    private val stateRoot = if (device.root.isEmpty()) context.filesDir.canonicalFile
        else File(context.filesDir.canonicalFile, device.root)
    private val runtimeRoot = File(context.filesDir.canonicalFile, device.runtimeDir)
    /** rootfs 还不存在 = 这台虚拟机第一次启动，首帧要等首次安装与 dexopt。 */
    private val firstBoot = !File(runtimeRoot, "rootfs").isDirectory
    private val files = GuestLibraryFiles(context, directory)
    // romRoot == null 时行为与改造前逐字相同（清单、payload、客体 .so 全部来自 APK）；
    // 非 null 时清单与 payload 从已解包的 ROM 目录读（docs/rom-package/03 §3.1）。
    private val runtime = GuestRuntime(context, runtimeRoot, rom.runtimeManifest,
        rom.apiLevel, rom.name, stateRoot, rom.romRoot)
    private val nonce = UUID.randomUUID().toString()
    private val network = HostNetworkBridge(context, stateRoot)
    private val audio = HostAudioBridge(context, stateRoot)
    private val sensors = HostSensorBridge(context, stateRoot)
    private val vibrator = HostVibratorBridge(context, stateRoot)
    private val battery = HostBatteryBridge(context, stateRoot)
    /**
     * Android 9's healthd reads /sys/class/power_supply; the guest path layer
     * leaves /sys on the host sysfs, which this app cannot read.  The sysfs
     * writer materialises the same snapshot under the instance state root and
     * liba9vm-healthd-sysfs.so redirects that prefix in the guest.  Android 10
     * uses the health HAL over the battery socket instead.
     */
    private val batterySysfs = HostBatterySysfs(context, stateRoot)
    private val camera = HostCameraBridge(context, stateRoot)
    private val sharedMemory = HostSharedMemoryBridge(context, stateRoot)
    @Volatile var window: NativeWindow? = null
        private set
    @Volatile private var framework: FrameworkSession? = null
    private var startedAt = 0L
    private var displayInteractive: Boolean? = null
    val alive get() = window != null && framework?.isAlive == true
    val uptimeSeconds get() = if (alive) (SystemClock.elapsedRealtime() - startedAt) / 1000 else 0L

    fun inspect(): String {
        val manifest = files.readInputs(rom.displayManifest)
        check(manifest.getString("backend") == "native-arm64-gles-surface")
        check(!manifest.getBoolean("rootRequired") && manifest.getBoolean("desktopImplemented"))
        // 显示清单里的库全部由安装包的 lib/ 提供，换 ROM 版本后逐字节比对必然失败。
        // 只记警告并继续：把「安装包与 ROM 版本不同」当成拒绝启动，等于一份旧 ROM 都开不了机。
        for (line in files.verifyPackagedInputs(manifest.getJSONArray("files")))
            runtime.startupLog().record("WARN", "packaged-input-mismatch " + line)
        runtime.verify()
        return "${rom.name} 桌面、ART、私有 Binder 及图形组件校验通过。"
    }

    /**
     * 用户点了「停止」：让正在进行的 [start] 在**下一个检查点**安全退出。
     *
     * 与"强制杀死"的区别：这里只是设置标记，[start] 自己抛 [SessionCancelled] 并走它自己的
     * `catch → close()`（与正常停止完全同一条收尾路径），不会留下半启动的窗口/框架。
     */
    fun requestCancel() {
        cancelRequested = true
    }

    @Volatile private var cancelRequested = false

    private fun ensureNotCancelled() {
        if (cancelRequested) throw SessionCancelled()
    }

    fun start() {
        check(!alive) { "原生显示已经运行" }
        val log = runtime.startupLog()
        log.begin()
        try {
            ensureNotCancelled()
            log.record("VERIFY", "Checking display and Android runtime")
            inspect()
            directory.mkdirs()
            log.record("PREPARE", "Preparing private Android filesystem")
            runtime.prepare()
            // prepare() 会把 guest.prop 按清单 sha256 复原，所以密度必须在它之后写：
            // prepare() → 显示密度 → startDesktop()，顺序是固定的（docs/display-settings/04 §3）。
            runtime.applyDisplayDensity(display.densityDpi)
            log.record("DISPLAY", display.summary)
            // prepare 是最长的一段（首次装 rootfs）：它一回来就先看一次取消，
            // 免得用户在"正在准备系统文件"期间点的停止要等到 GPU/框架阶段才生效。
            ensureNotCancelled()
            for (name in listOf("gpu.sock", "desktop-input.sock")) File(directory, name).delete()
            startedAt = SystemClock.elapsedRealtime()
            sharedMemory.start()
            log.record("SHMEM", "Shared memory bridge ready")
            log.record("ART", "Checking Android Java runtime")
            runtime.checkJava(nonce)
            log.record("ART", "Runtime identity verified")
            ensureNotCancelled()
            startHostDevices()
            log.record("DEVICES", "Host device bridges ready")
            ensureNotCancelled()
            val renderer = NativeWindow(directory, display.width, display.height, display.densityDpi).also { window = it }
            await("宿主 GPU 初始化", GPU_READY_MILLIS) { renderer.metrics()[0] != 0L }
            log.record("GPU", "Host renderer ready; starting Android services")
            ensureNotCancelled()
            framework = runtime.startDesktop(nonce, File(directory, "gpu.sock"), File(directory, "desktop-input.sock"))
            // 统一机制（A9/A10/A15 同一套）：
            //   · 框架一起来就交画面（首帧由渲染器自然接上，不再同步等、不再因为等不到就判死）；
            //   · 触摸走**独立重试循环**，不再依赖"RUNNING 时顺带重试"——
            //     第 82 轮就是这条依赖，让 A10 的触摸在早启动失败后再也没起来。
            log.record("READY", "Android framework accepting input; first frame is observed in background")
            startInputRetry()
        } catch (failure: Throwable) {
            log.record("ERROR", failure.message ?: failure.javaClass.simpleName)
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        } finally { log.finish() }
    }

    fun checkDesktop(): String {
        check(!alive) { "请先停止当前运行，再检查桌面启动" }
        inspect()
        runtime.prepare()
        runtime.applyDisplayDensity(display.densityDpi)
        try {
            sharedMemory.start()
            startHostDevices()
            runtime.startDesktop(nonce).use {
                return "${rom.name} 系统服务及 Launcher3 桌面启动检查通过。"
            }
        } finally { close() }
    }

    private fun startHostDevices() {
        network.start()
        audio.start()
        sensors.start()
        vibrator.start()
        battery.start()
        // API 28's healthd reads sysfs; A10 uses the HIDL health HAL. A15's
        // BatteryService uses the *AIDL* health HAL, and its image service
        // (health-service.example) reads /sys/class/power_supply just like
        // healthd did. The guest path bridge maps that subtree to this writer's
        // instance directory (GuestPaths.cpp:104-121), so A15 needs it too;
        // without it the directory does not exist and battery stays at 0.
        // Start before the guest framework so the first health read sees it.
        if (rom.apiLevel <= 28 || rom.apiLevel >= 35) batterySysfs.start()
        camera.start()
        runtime.configureHostDevices(if (vibrator.available()) emptySet() else setOf(HostVibratorBridge.HAL_NAME))
    }

    fun installApk(apk: File): String = checkNotNull(framework) { "请先启动 ${rom.name}" }.installApk(apk)
    fun installApks(apks: List<File>): String = checkNotNull(framework) { "请先启动 ${rom.name}" }.installApks(apks)

    fun refreshNetwork() = network.refresh()

    private var lastInputAttempt = 0L
    private var inputReady = false
    @Volatile private var inputRetrying = false

    /**
     * 补触摸通道。间隔 2 秒（原来是 10 秒，配合下面独立重试循环），
     * 失败不把整台虚拟机的会话判死。
     */
    @Synchronized
    fun ensureInput() {
        if (inputReady) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastInputAttempt < 2_000) return
        lastInputAttempt = now
        inputReady = framework?.ensureInput() == true
        if (inputReady) runtime.startupLog().record("READY", "input: desktop_input_ready=1")
    }

    /**
     * 独立的触摸重试循环。第 82 轮把触摸提前到"框架一起来"就启动，但重试原先只挂在
     * "状态 == RUNNING 时顺带调 updateDisplayPower"上：A10 第一次早启动失败后就再没
     * 有人来重试，用户看到的就是停在"正在启动触摸操作"且没有界面。
     * 这里用一个守护线程自己负责重试，和状态机、和桌面确认都解耦。
     */
    private fun startInputRetry() {
        if (inputRetrying) return
        inputRetrying = true
        Thread({
            var attempt = 0
            while (!inputReady && attempt < 60 && alive) {
                attempt++
                try { ensureInput() } catch (failure: Throwable) { /* 单次失败不影响会话 */ }
                if (inputReady) break
                try { Thread.sleep(2_000) } catch (stop: InterruptedException) { return@Thread }
            }
            if (!inputReady) runtime.startupLog().record("WARN", "触摸通道多次重试仍未就绪，会话继续运行")
        }, "guest-input-retry").apply { isDaemon = true }.start()
    }

    fun updateDisplayPower() {
        ensureInput()
        val renderer = window ?: return
        val services = framework ?: return
        val interactive = renderer.metrics()[9] != 0L
        if (interactive == displayInteractive) return
        displayInteractive = interactive
        services.setDisplayInteractive(interactive)
    }

    private fun await(stage: String, timeoutMillis: Long, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!ready()) {
            // 10 ms 一轮：用户点停止后最多 10 ms 就能退出等待（这是"启动中可以停"的关键）。
            ensureNotCancelled()
            check(window?.error().isNullOrEmpty()) { window?.error().orEmpty() }
            check(framework?.isAlive != false) { "${rom.name} 系统进程退出：${logs()}" }
            check(SystemClock.elapsedRealtime() < deadline) { "$stage 超时：${logs()}" }
            Thread.sleep(10)
        }
    }

    fun logs(): String = framework?.diagnostics() ?: runtime.diagnostics()
    fun bootLog() = runtime.startupLog().snapshot()
    fun scanMedia(path: String) = requireNotNull(framework) { "请先启动 ${rom.name}" }.scanMedia(path)
    fun error(): String = window?.error().orEmpty().ifEmpty {
        framework?.error().orEmpty()
    }

    /** 导入与文件管理使用的暂存目录：必须位于该实例的状态根下，客体才读得到。 */
    fun importsDirectory(): File = File(stateRoot, "guest-imports").apply { mkdirs() }

    /** 客体自己的根文件系统目录（导入文件、检查数据都从这里出发）。 */
    fun guestRoot(): File = File(runtimeRoot, "rootfs")

    /** 已关闭的会话不能再用：VmService 据此丢掉它并重建（ROM 换过 / 上一次启动失败过）。 */
    @Volatile private var closed = false
    val isClosed: Boolean get() = closed

    override fun close() {
        closed = true
        val renderer = window
        val services = framework
        window = null
        framework = null
        displayInteractive = null
        try {
            renderer?.requestExit()
            services?.close()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            audio.close()
            sensors.close()
            vibrator.close()
            battery.close()
            batterySysfs.close()
            camera.close()
            sharedMemory.close()
            network.close()
            renderer?.close()
        }
    }
}
