package io.a10vm.runtime

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import me.kanadex.geometryos.audit.AuditTrigger
import me.kanadex.geometryos.audit.android.AuditBootstrap
import me.kanadex.geometryos.audit.android.AuditIndex
import me.kanadex.geometryos.audit.model.AuditEventKind
import me.kanadex.geometryos.audit.model.SessionId
import io.a10vm.nativedisplay.NativeWindow
import io.a10vm.records.RecordCollector
import io.a10vm.records.RecordKind
import io.a10vm.records.RecordStatus
import io.a10vm.ui.MainActivity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 虚拟机运行服务。
 *
 * 每台注册过的虚拟机都有独立的会话、工作队列、诊断队列和原生窗口；同一份 ROM 可以
 * 同时启动多台，互不共享 socket、状态根与 rootfs。
 */
class VmService : Service() {
    companion object {
        const val REGISTER = 1
        const val UNREGISTER = 2
        const val STATE = 3
        const val LOGS = 4
        const val REFRESH_NETWORK = 5
        const val INSPECT = "io.a10vm.INSPECT"
        const val START = "io.a10vm.START_NATIVE_DISPLAY"
        const val CHECK_DESKTOP = "io.a10vm.CHECK_DESKTOP_BOOT"
        const val STOP = "io.a10vm.STOP"
        const val INSTALL_APK = "io.a10vm.INSTALL_GUEST_APK"
        const val IMPORT_APPS = "io.a10vm.IMPORT_HOST_APPS"
        const val IMPORT_FILES = "io.a10vm.IMPORT_GUEST_FILES"
        const val ENABLE_MICROPHONE = "io.a10vm.ENABLE_MICROPHONE"
        const val ENABLE_CAMERA = "io.a10vm.ENABLE_CAMERA"
        const val DELETE = "io.a10vm.DELETE_DEVICE"
        const val EXTRA_INSTANCE = "instance"
        const val CHANNEL = "native-display"

        private const val TAG = "A10VM_NATIVE"

        /**
         * 「停止」在 worker 卡住时的兜底宽限（20 s）。
         *
         * 为什么需要：正常停止是「置 stopRequested → worker 收尾 → finally 里再发一次 STOP」，
         * 但 worker 可能卡在解包自愈、或客体 spawn 一直不返回，于是 stopRequested 永远没人检查，
         * 用户看到的就是「点了停止没反应、还在跑」（2026-11-09 真机：两台实例 BUSY 卡了 15 分钟）。
         */
        private const val FORCE_STOP_GRACE_MILLIS = 20_000L

        /** 「正在关机」后先等多久让 worker 自己收尾；超过就替它走一次正常停止的 close()。 */
        private const val SAFE_STOP_CLOSE_MILLIS = 3_000L

        private val ACTIONS = setOf(INSPECT, START, STOP, CHECK_DESKTOP, INSTALL_APK,
            IMPORT_APPS, IMPORT_FILES, ENABLE_MICROPHONE, ENABLE_CAMERA, DELETE)
        private val windows = ConcurrentHashMap<String, NativeWindow>()

        private var activeService: VmService? = null

        internal suspend fun <T> captureStopped(id: String, capture: (VmDevice, () -> Unit) -> T): T {
            val caller = currentCoroutineContext()
            val result = withContext(Dispatchers.Main.immediate) {
                val service = checkNotNull(activeService) { "虚拟机服务尚未连接" }
                service.enqueueCapture(id) { device ->
                    capture(device) {
                        caller.ensureActive()
                        check(!service.destroyed) { "虚拟机服务已断开" }
                    }
                }
            }
            return result.await()
        }

        fun window(device: String): NativeWindow? = windows[device]
    }

    private val roms by lazy { RomCatalog(this) }
    private val registry by lazy { VmDevices(this) }
    private val instances = ConcurrentHashMap<String, Instance>()
    private val clients = CopyOnWriteArrayList<Messenger>()
    private val main = Handler(Looper.getMainLooper())
    private val watcher = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "a10vm-vm-watch")
    }
    private val diagnostics = Executors.newSingleThreadExecutor { task ->
        Thread(task, "a10vm-diagnostics")
    }
    @Volatile private var destroyed = false
    @Volatile private var seenRevision = -1L
    /** ROM 仓库的 revision（tick 里比对，变了就刷所有实例的 ROM 缓存）。 */
    @Volatile private var seenRomRevision = -1L

    private inner class Instance(device: VmDevice) {
        /**
         * 这台虚拟机的注册表记录。
         *
         * **必须是可刷新的**：除了名字，它还带着 `displayMode`（分辨率/DPI）。
         * 原来这里是 `val`，于是"改完分辨率、重启虚拟机仍是旧分辨率"——会话是用
         * 创建 Instance 那一刻的快照建的，之后库里的新值永远读不到（2026-11-09 真机，A10 复现）。
         * 观察器刷新这份运行态快照；启动命令另取注册表快照，不等待观察器。
         */
        @Volatile var device = device
        val id = device.id
        // 名字来自注册表，可以被"重命名"改掉：必须可变，由 refreshNames() 刷新。
        @Volatile var name = device.name
        private val romCache = VmRomResolutionCache()
        val rom: RomSpec? get() = resolveRom(device)
        val romSnapshot: RomSpec? get() = romCache.snapshot

        fun resolveRom(device: VmDevice): RomSpec? {
            val repository = me.kanadex.geometryos.rom.RomRepository.get(this@VmService)
            return romCache.resolve(device.romBinding(), repository.revision.value) { binding ->
                repository.resolve(binding.key, binding.payload)
                    ?: if (binding.payload.isEmpty()) roms.find(binding.key) else null
            }
        }

        /**
         * 这个实例的 ROM 现在"解不出来"，但**原始包还在**——那就还有救，不该直接拒绝启动。
         *
         * 判据刻意做得便宜（只看 archives / 内置包），因为它会在主线程的入口闸门里被调用。
         *
         * **只许在入口/启动链上调（每次用户点启动一次）**：
         * 它内部要碰文件系统（hasArchive）。把它塞进 [Instance.init] 会让
         * `instances.computeIfAbsent` 在 tick 线程上同步做 IO，把 250ms 的 publish 卡住，
         * 表现就是启动页画面进度忽快忽慢（2026-11-15 维护者点名）。init 里绝不许调它。
         */
        fun canRepairRom(device: VmDevice): Boolean = runCatching {
            me.kanadex.geometryos.rom.RomRepository.get(this@VmService)
                .hasArchive(device.romId, device.romPayload)
        }.getOrDefault(false)

        /**
         * 开机前的**自愈**：ROM 的 payload 缺文件时，从**原始 ROM 包**重新解出来。
         *
         * 这条对应 A10/A9 一直有的机制：每次启动按清单逐条比对，缺了就从不可变来源重拷。
         * A15 的 payload 现在住在 files/roms/（可写区，会被"充满电"这类系统行为删文件），
         * 所以修复来源必须是压缩包本身，而不是解包目录。
         *
         * 只对"精确绑定某个 romKey"的实例生效：旧格式实例从 APK assets 取内容，
         * 那条路径自带不可变来源，不需要也不该走这里。
         */
        fun ensureRomUsable(device: VmDevice) {
            val key = device.romId
            val repository = me.kanadex.geometryos.rom.RomRepository.get(this@VmService)
            // 按**身份**取这一行：同版本可能存在内置/本地两行（或两份不同内容），
            // 取第一行就会去修另一份——修完这台实例用的还是坏的那一份（P5/P3 的同一类错误）。
            val row = runCatching {
                repository.rowFor(
                    me.kanadex.geometryos.rom.RomRef(key, null, device.romPayload),
                )
            }.getOrNull() ?: return
            if (row.source == me.kanadex.geometryos.rom.RomSource.Legacy) return
            if (repository.payloadProblems(row).isEmpty()) return
            // 具名传参：ensureUsable 的最后一个参数是 isCancelled（函数类型），
            // 用尾随 lambda 会被绑到 isCancelled 上。
            // isCancelled 让「启动页点停止」能把解包/校验打断，不再出现"停在正在校验停不下来"。
            repository.ensureUsable(
                key = key,
                payloadSha256 = row.payloadSha256,
                progress = { progress ->
                    update(
                        this,
                        "BUSY",
                        "正在用原始 ROM 修复缺失文件：" + me.kanadex.geometryos.rom.RomTexts.progress(progress),
                    )
                },
                isCancelled = { stopRequested.get() },
            )
        }

        // 当前操作在记录中心里的行号与事件类型（失败/成功时由 finally 收口）。
        @Volatile var recordId: Long? = null
        @Volatile var recordKind: RecordKind = RecordKind.LAUNCH
        @Volatile var session: NativeGraphicsSession? = null
        /** 本次运行所属的审计会话（日志淘汰的最小单位）；见 docs/log-audit/02 §2.2。 */
        @Volatile var auditSession: SessionId? = null
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "a10vm-vm-$id") }
        val diagnostics = Executors.newSingleThreadExecutor { task -> Thread(task, "a10vm-diag-$id") }
        val busy = AtomicBoolean()
        @Volatile var capturing = false
        val stopRequested = AtomicBoolean()

        /** 强制停止只排一次（重复点停止不会叠加多个看门狗）。 */
        val forceStopScheduled = AtomicBoolean()
        @Volatile var powerBusy = false
        @Volatile var window: NativeWindow? = null
        @Volatile var state = "IDLE"
        /** 本次运行开始的时刻（毫秒）；0 = 没在运行。崩溃记录用它算"已经跑了多久"。 */
        @Volatile var runningSince = 0L
        @Volatile var detail = ""
        @Volatile var microphone = false
        @Volatile var camera = false
        @Volatile var deleting = false
        @Volatile var deleted = false

        init {
            // 先解一次，把结果落进缓存，这样主线程 publish 读 romSnapshot 时一定有值。
            //
            // **这里不许做任何 IO**（不许调 canRepairRom / hasArchive）：
            // Instance 是在 tick 线程上通过 computeIfAbsent 创建的，init 里的同步 IO
            // 会卡住 250ms 的 publish，启动页的画面进度就会忽快忽慢（2026-11-15）。
            // "尚未解包"与"ROM 缺失"的区分放在启动链（onStartCommand）上做，那里是用户点启动的一次性调用。
            val resolved = rom
            detail = if (resolved == null)
                "ROM 缺失（${device.romId}）。请到 ROM 管理里导入这个 ROM，或删除这台虚拟机。"
            else "点击启动 ${name} 的 ${resolved.name} 桌面。"
        }
    }

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        handle(message)
        true
    })

    override fun onCreate() {
        super.onCreate()
        activeService = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Android 虚拟机", NotificationManager.IMPORTANCE_LOW),
        )
        diagnostics.execute { registry.list().forEach { instance(it) } }
        watcher.scheduleWithFixedDelay({ tick() }, 250, 250, TimeUnit.MILLISECONDS)
        thread(name = "a10vm-trash") { cleanTrash() }
        // 唯一自动清理入口（docs/log-audit/04 §5）：服务一起来就在审计线程上做一次体检。
        // 上一次进程被杀留下的"永远进行中"的会话在这里收尾，旧的无标识日志在这里按会话组淘汰。
        AuditBootstrap.scope.launch {
            AuditIndex.maintain(this@VmService, AuditTrigger.SERVICE_START, liveSessions())
        }
    }

    override fun onBind(intent: Intent) = messenger.binder

    private fun instance(device: VmDevice): Instance =
        instances.computeIfAbsent(device.id) { Instance(device) }

    private fun instance(id: String): Instance? = registry.find(id)?.let { instance(it) }

    private fun <T> enqueueCapture(id: String, capture: (VmDevice) -> T): CompletableDeferred<T> {
        val inst = checkNotNull(instances[id]) { "虚拟机状态尚未加载" }
        check(!destroyed && !inst.deleted && inst.state == "IDLE" && inst.session?.isClosed != false &&
            !inst.powerBusy && !inst.forceStopScheduled.get() && inst.busy.compareAndSet(false, true)) {
            "请先关闭虚拟机并等待操作结束"
        }
        // Command admission and capture reservation both run on the service main thread.
        inst.capturing = true
        val result = CompletableDeferred<T>()
        inst.worker.execute {
            val outcome = runCatching {
                val device = checkNotNull(registry.find(id)) { "虚拟机已不存在" }
                capture(device)
            }
            inst.busy.set(false)
            inst.capturing = false
            outcome.fold(result::complete, result::completeExceptionally)
            publish(inst)
        }
        publish(inst)
        return result
    }

    /**
     * 会话在第一次真正需要时才创建：服务被系统拉起时不预热任何一个客体的设备桥，
     * 也不会因为某台虚拟机的初始化问题拖住其它已注册的实例。
     */
    /**
     * 「停止」的兜底：worker 卡住时，[FORCE_STOP_GRACE_MILLIS] 之后强制收尾。
     *
     * 只在这个实例仍然 busy 且没被删的时候生效；正常路径（worker 很快做完并把
     * stopRequested 转成一次 STOP）不会走到这里。放在 diagnostics 线程上做，
     * 不占主线程、也不和 worker 抢。
     */
    private fun scheduleSafeStop(inst: Instance) {
        if (!inst.forceStopScheduled.compareAndSet(false, true)) return
        diagnostics.execute {
            // 第一档（3 s）：还卡着就替 worker 走一次正常停止的收尾。
            // session.close() 就是"运行中点停止"用的同一个动作，不是杀进程。
            val soft = System.currentTimeMillis() + SAFE_STOP_CLOSE_MILLIS
            while (System.currentTimeMillis() < soft) {
                if (!inst.busy.get() || inst.deleted) {
                    inst.forceStopScheduled.set(false)
                    return@execute
                }
                Thread.sleep(100)
            }
            if (!inst.busy.get() || inst.deleted) {
                inst.forceStopScheduled.set(false)
                return@execute
            }
            Log.i(TAG, inst.id + " 3 s 未自行收尾，替它执行一次正常停止")
            runCatching { inst.session?.close() }
                .onFailure { Log.w(TAG, "停止收尾失败：" + it.message) }
            // 第二档（20 s）：worker 仍未回来，才放开 busy 并置 IDLE（最后兜底）。
            val deadline = System.currentTimeMillis() + FORCE_STOP_GRACE_MILLIS
            while (System.currentTimeMillis() < deadline) {
                if (!inst.busy.get() || inst.deleted) {
                    inst.forceStopScheduled.set(false)
                    return@execute
                }
                Thread.sleep(250)
            }
            inst.forceStopScheduled.set(false)
            if (!inst.busy.get() || inst.deleted) return@execute
            Log.w(TAG, inst.id + " 停止超时，强制关闭会话")
            runCatching { inst.session?.close() }
                .onFailure { Log.w(TAG, "强制关闭会话失败：" + it.message) }
            inst.stopRequested.set(false)
            inst.runningSince = 0L
            windows.remove(inst.id)
            inst.window = null
            inst.busy.set(false)
            update(inst, "IDLE", inst.name + " 已关机。")
            stopForegroundIfIdle()
        }
    }

    private fun sessionOf(inst: Instance, device: VmDevice, stopping: Boolean): NativeGraphicsSession {
        val current = inst.resolveRom(device)
        val existing = inst.session
        if (existing != null) {
            // 复用条件：会话还活着，且绑的还是同一个 ROM。
            //  · 已关闭（上一次启动失败 / 停止后又被丢弃）→ 必须重建；
            //  · ROM 换了（用户导入 / 删除了包，或同 id 的新版本）→ 旧会话的 romRoot 已经不对，重建；
            //  · ROM 被删但会话还在跑 → 不动它（不能因为删包把正在跑的虚拟机杀掉）。
            val stale = existing.isClosed || (!stopping && current != null &&
                (existing.rom.id != current.id || existing.rom.romRoot != current.romRoot))
            if (!stale) return existing
            runCatching { existing.close() }
            inst.session = null
        }
        val rom = requireNotNull(current) { "找不到 ROM ${device.romId}" }
        // 显示几何在**建会话时**解析一次：它决定渲染器帧缓冲与客体 DPI，
        // 两者必须在同一次运行里保持一致（docs/display-settings/04 §2）。
        val display = me.kanadex.geometryos.display.android.DisplaySettings.geometryOf(this, device)
        return NativeGraphicsSession(this, device, rom, display).also { inst.session = it }
    }

    private fun handle(message: Message) {
        when (message.what) {
            REGISTER -> {
                val reply = message.replyTo ?: return
                if (reply !in clients) clients.add(reply)
                diagnostics.execute {
                    registry.list().forEach { instance(it) }
                    instances.values.toList().forEach { publish(it, reply) }
                    refreshNetwork()
                }
            }
            UNREGISTER -> clients.remove(message.replyTo)
            LOGS -> {
                val reply = message.replyTo
                val target = message.data?.getString(EXTRA_INSTANCE) ?: "*"
                diagnostics.execute {
                    val targets = if (target == "*") instances.values.toList()
                    else listOfNotNull(instances[target] ?: instance(target))
                    val text = buildString {
                        targets.forEachIndexed { index, inst ->
                            if (index > 0) append("\n\n")
                            if (targets.size > 1) append("===== ${inst.name}（${inst.rom?.name ?: inst.device.romId}）=====\n")
                            append(diagnosticText(inst))
                        }
                    }
                    send(reply, LOGS, Bundle().apply {
                        putString("text", text.ifEmpty { "没有正在运行的虚拟机。" })
                    })
                }
            }
            REFRESH_NETWORK -> refreshNetwork()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent ?: return START_NOT_STICKY
        val action = command.action
        if (destroyed || action !in ACTIONS) return START_NOT_STICKY
        val id = command.getStringExtra(EXTRA_INSTANCE) ?: return START_NOT_STICKY
        if (action == START) {
            // The registry write may have committed before the watcher has observed it.
            diagnostics.execute {
                val device = registry.find(id) ?: return@execute
                val inst = instance(device)
                main.post {
                    if (!destroyed) handleCommand(command, START, inst, device)
                }
            }
            return START_NOT_STICKY
        }
        val inst = instances[id] ?: run {
            diagnostics.execute {
                if (instance(id) != null && !destroyed) main.post { startService(command) }
            }
            return START_NOT_STICKY
        }
        return handleCommand(command, action.orEmpty(), inst, inst.device)
    }

    private fun handleCommand(command: Intent, action: String, inst: Instance, device: VmDevice): Int {
        if (inst.deleted || inst.capturing) return START_NOT_STICKY
        if (action == START && inst.session?.alive == true && !inst.busy.get()) {
            publish(inst)
            return START_NOT_STICKY
        }
        inst.microphone = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                (inst.microphone || action == START || action == ENABLE_MICROPHONE)
        inst.camera = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                (inst.camera || action == START || action == ENABLE_CAMERA)
        if (Build.VERSION.SDK_INT >= 29) startForeground(10, notification(), foregroundTypes())
        else startForeground(10, notification())
        // 解析不到 ROM 时先看"包还在不在"：包在就交给 worker 里的自愈去重解，
        // 只有包真的不在了才拒绝启动（那种情况才需要用户重新导入）。
        //
        // 这里是入口链（用户点启动时的一次性调用），可以碰文件系统；
        // tick 线程上的 Instance.init 绝不许做同样的判断（会把画面进度卡出抖动）。
        val selectedRom = if (action == DELETE) inst.romSnapshot else inst.resolveRom(device)
        if (action != DELETE && selectedRom == null && !inst.canRepairRom(device) &&
            !(action == STOP && inst.session != null)
        ) {
            // 拒绝启动也必须留一条**记录**：否则用户点了一次启动、界面上什么痕迹都没有，
            // 记录中心里也查不到"为什么这台起不来"（真机反馈：删除 ROM 后启动失败的日志不见了）。
            //
            // 这里用 recordMeta：拒绝发生在创建会话之前，没有任何客体日志可归档；
            // 伪造一份"日志"比不写更坏（_audit/04 §2）。
            val reason = "ROM 缺失（${device.romId}）。请到 ROM 管理里导入这个 ROM，或删除这台虚拟机。"
            RecordCollector.recordMeta(
                context = this,
                vmId = inst.device.id,
                vmName = inst.name,
                romId = device.romId,
                romName = null,
                apiLevel = 0,
                kind = recordKindOf(action.orEmpty()),
                status = RecordStatus.FAILED,
                detail = reason + "（本次启动在创建会话之前被拒绝，所以没有客体日志）",
            )
            update(inst, "ERROR", reason)
            stopForegroundIfIdle()
            return START_NOT_STICKY
        }
        if (!inst.busy.compareAndSet(false, true)) {
            if (action == STOP) {
                // ① 先认账：立刻把状态改成"正在关机"，界面马上能看到（不是等 worker 排到队才变）。
                inst.stopRequested.set(true)
                update(inst, "BUSY", inst.name + " 正在关机…")
                // ② 请正在进行的启动/自愈在下一个检查点退出（协作取消）。
                runCatching { inst.session?.requestCancel() }
                // ③ 3 秒内还没收住就替它走一次**正常停止**的收尾（session.close()，和手动停止同一个动作），
                //    再兜 20 秒；两步都不是杀进程。
                scheduleSafeStop(inst)
            }
            return START_NOT_STICKY
        }
        if (action == STOP) inst.stopRequested.set(false)
        update(inst, "BUSY", when (action) {
            // rom 为 null 还能走到这里，说明上面「包真不在了」的拒绝分支没触发：
            // 原始包还在，只是还没解包（内置包首次使用 / 解包体被系统清掉）。
            // 这句是给用户看的准确文案；真正的解包在 worker 里的 ensureRomUsable()。
            START -> if (selectedRom != null)
                "正在启动 ${inst.name}（${selectedRom.name}）框架、私有 Binder 与原生显示。"
            else
                "这个 ROM 还没准备好（尚未解包或解包体不完整）。正在解包后继续开机…"
            CHECK_DESKTOP -> "正在检查 ${inst.name} 的桌面启动。"
            INSTALL_APK -> "正在将 APK 安装到 ${inst.name}…"
            IMPORT_APPS -> "正在将应用导入 ${inst.name}…"
            IMPORT_FILES -> "正在导入文件并更新 ${inst.name} 的图库…"
            DELETE -> "正在删除 ${inst.name}…"
            else -> "正在处理原生显示操作。"
        })
        val recordKind = recordKindOf(action.orEmpty())
        inst.recordKind = recordKind
        if (action == START) beginAuditSession(inst)
        inst.recordId = RecordCollector.begin(this, device, selectedRom, recordKind)
        inst.worker.execute {
            val background = action in setOf(INSTALL_APK, IMPORT_APPS, IMPORT_FILES)
            Process.setThreadPriority(if (background) Process.THREAD_PRIORITY_BACKGROUND else Process.THREAD_PRIORITY_DEFAULT)
            // 本次操作的真实失败原因（没有失败 = null）。
            // 记录状态必须看它，不能看 inst.state：状态是"界面上的"，结果才是"做没做成"。
            var operationFailure: String? = null
            try {
                if (action == DELETE) {
                    delete(inst)
                    return@execute
                }
                val operationDevice = if (action == START) device else registry.find(inst.id) ?: device
                if (action != STOP) {
                    RecordCollector.appendOperation(this, inst.recordId, operationDevice, "integrity", "check_started", "检查所选ROM解包体")
                    inst.ensureRomUsable(operationDevice)
                    kotlinx.coroutines.runBlocking { RecordCollector.awaitOperationArchives("rom") }
                    RecordCollector.appendOperation(this, inst.recordId, operationDevice, "integrity", "check_completed", "ROM解包体完整，可以继续准备实例")
                }
                val session = sessionOf(inst, operationDevice, stopping = action == STOP)
                when (action) {
                    INSPECT -> update(inst, if (session.alive) "RUNNING" else "IDLE", session.inspect())
                    CHECK_DESKTOP -> update(inst, "IDLE", session.checkDesktop())
                    START -> {
                        // 点了停止之后才轮到启动：别再起一轮，直接收尾。
                        if (inst.stopRequested.get()) throw SessionCancelled()
                        if (!session.alive) session.start()
                        inst.window = session.window
                        inst.window?.let { windows[inst.id] = it } ?: windows.remove(inst.id)
                        inst.runningSince = System.currentTimeMillis()
                        update(inst, "RUNNING",
                            "${inst.name} 已启动 · ${session.rom.name} 系统服务")
                    }
                    STOP -> {
                        inst.runningSince = 0L
                        windows.remove(inst.id)
                        inst.window = null
                        session.close()
                        update(inst, "IDLE", inst.name + " 已关机。")
                    }
                    ENABLE_MICROPHONE -> update(inst, if (session.alive) "RUNNING" else "IDLE",
                        if (inst.microphone) "麦克风已开启。请在 ${inst.name} 内允许应用录音。" else "请先允许 " + appLabel + " 使用麦克风。")
                    ENABLE_CAMERA -> update(inst, if (session.alive) "RUNNING" else "IDLE",
                        if (inst.camera) "相机已开启。请在 ${inst.name} 内允许应用使用相机。" else "请先允许 " + appLabel + " 使用相机。")
                    INSTALL_APK -> {
                        require(session.alive) { "请先启动 ${inst.name}" }
                        val uri = android.net.Uri.parse(requireNotNull(command.getStringExtra("uri")))
                        try {
                            update(inst, "RUNNING", GuestApkImport(this).install(uri, session, inst.name))
                        } catch (failure: Exception) {
                            val reason = "APK 安装失败：${failure.message}"
                            operationFailure = reason
                            update(inst, "RUNNING", reason)
                        }
                    }
                    IMPORT_APPS -> {
                        require(session.alive) { "请先启动 ${inst.name}" }
                        try {
                            val packages = payloadList(command.getStringExtra("uri"))
                            val outcome = GuestApkImport(this).importInstalled(packages, session, inst.name) { progress ->
                                update(inst, "BUSY", progress)
                            }
                            operationFailure = outcome.failure
                            update(inst, "RUNNING", outcome.text)
                        } catch (failure: Exception) {
                            val reason = "应用导入失败：${failure.message}"
                            operationFailure = reason
                            update(inst, "RUNNING", reason)
                        }
                    }
                    IMPORT_FILES -> {
                        require(session.alive) { "请先启动 ${inst.name}" }
                        try {
                            val uris = payloadList(command.getStringExtra("uri"))
                            update(inst, "RUNNING", GuestFileImport(this).importFiles(uris, session, inst.name))
                        } catch (failure: Exception) {
                            val reason = "文件导入失败：${failure.message}"
                            operationFailure = reason
                            update(inst, "RUNNING", reason)
                        }
                    }
                }
            } catch (cancel: SessionCancelled) {
                RecordCollector.appendOperation(this, inst.recordId, inst.device, "operation", "cancelled", cancel.message ?: "用户取消")
                update(inst, "IDLE", inst.name + " 已关机。")
            } catch (cancel: me.kanadex.geometryos.rom.RomCancelled) {
                RecordCollector.appendOperation(this, inst.recordId, inst.device, "operation", "cancelled", cancel.message ?: "用户取消")
                update(inst, "IDLE", "${inst.name} 已取消。")
            } catch (failure: Exception) {
                RecordCollector.appendOperation(this, inst.recordId, inst.device, "operation", "exception", failure.message ?: "操作失败", failure)
                failed(inst, failure, "操作失败")
            } catch (failure: LinkageError) {
                RecordCollector.appendOperation(this, inst.recordId, inst.device, "operation", "exception", failure.message ?: "原生组件装载失败", failure)
                failed(inst, failure, "原生组件装载失败")
            }
            finally {
                // 这次操作到底做成了没有：记录中心用它决定 SUCCESS / FAILED，
                // 不再靠 inst.state 反推（本轮真机就是"0/2 个应用导入成功"被记成 SUCCESS）。
                val operationError = operationFailure
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                // 记录收口：先于状态清理做，此时 detail/state 还是本次操作的真实结果。
                // 归档是 IO 重活，在这个专用 worker 线程上同步等它做完是对的：
                // 状态一改，UI 就可能立刻点开详情，那时归档必须已经落盘。
                kotlinx.coroutines.runBlocking {
                    RecordCollector.finish(
                        this@VmService,
                        inst.recordId,
                        inst.device,
                        inst.recordKind,
                        when {
                            inst.deleted -> RecordStatus.SUCCESS
                            inst.state == "ERROR" -> RecordStatus.FAILED
                            operationError != null -> RecordStatus.FAILED
                            else -> RecordStatus.SUCCESS
                        },
                        // FAILED 记录会取 detail 的第一行当 failure：先把真实原因放最前面。
                        if (operationError != null && inst.state != "ERROR") "$operationError\n${inst.detail}"
                        else inst.detail,
                        inst.auditSession ?: SessionId.Legacy,
                    )
                }
                inst.recordId = null
                if (action == STOP) endAuditSession(inst)
                inst.busy.set(false)
                if (action != STOP && inst.stopRequested.getAndSet(false) && !inst.deleted) {
                    main.post {
                        startService(Intent(this, VmService::class.java).apply {
                            this.action = STOP
                            putExtra(EXTRA_INSTANCE, inst.id)
                        })
                    }
                }
                if (!inst.deleted) {
                    if (inst.session?.alive != true) {
                        inst.microphone = false
                        inst.camera = false
                        stopForegroundIfIdle()
                    }
                    publish(inst)
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 注册表写入后（重命名）把新名字搬进内存实例。
     *
     * 复用已有的 250ms 定时器：只有 revision 变了才读库，因此稳态下没有额外开销，
     * 也不需要新线程、广播或跨进程通知。
     */
    private fun refreshNames() {
        val revision = registry.revision()
        if (revision == seenRevision) return
        seenRevision = revision
        var changed = false
        for (inst in instances.values.toList()) {
            if (inst.deleted) continue
            val device = registry.find(inst.id) ?: continue
            if (inst.name != device.name) {
                Log.i(TAG, "${inst.id} RENAMED: ${inst.name} -> ${device.name}")
                inst.name = device.name
                changed = true
            }
            // 记录整体刷新：displayMode 之类的设置改了之后，实例必须看到新值。
            if (inst.device != device) inst.device = device
        }
        if (changed) instances.values.forEach { if (!it.deleted) publish(it) }
    }

    private fun tick() {
        if (destroyed) return
        refreshNames()
        // ROM 一变（导入 / 删除 / 清缓存），后台把每个实例的 ROM 缓存刷一遍：
        // 这样「导入后立刻看管理页」就能看到系统名与可用状态，而不是等一次启动或重启 App。
        val revision = me.kanadex.geometryos.rom.RomRepository.get(this).revision.value
        if (revision != seenRomRevision) {
            seenRomRevision = revision
            instances.values.toList().forEach { instance -> runCatching { instance.rom } }
        }
        for (inst in instances.values.toList()) {
            if (inst.deleted) continue
            // 启动/安装期间也要持续发布：开机终端里的“正在检查系统文件 x/y”“正在准备
            // 系统文件 x/y”就是靠这条通道刷新的，不能因为 busy 就跳过。
            if (inst.state == "BUSY" || inst.busy.get()) {
                publish(inst)
                continue
            }
            if (inst.state != "RUNNING") continue
            val session = inst.session ?: continue
            val error = runCatching { session.error() }.getOrDefault("")
            // 客体可能"一声不响"地死掉：没有错误文本，但会话已经不活了。
            val dead = !session.alive
            val reason = error.ifEmpty {
                if (dead) "${inst.name} 的客体系统进程已退出（没有错误信息）" else ""
            }
            if (reason.isNotEmpty() && inst.busy.compareAndSet(false, true)) {
                inst.worker.execute {
                    // 崩溃必须留一条记录：只把状态改成 ERROR 的话，记录中心里看不到这次
                    // 事故，事后完全没法对时间线。
                    val uptime = uptimeText(inst)
                    val recordId = RecordCollector.begin(this, inst.device, inst.rom, RecordKind.CRASH)
                    try {
                        windows.remove(inst.id)
                        inst.window = null
                        session.close()
                        update(inst, "ERROR", reason + (uptime?.let { "\n已运行 $it 后崩溃" } ?: ""))
                    } catch (failure: Exception) {
                        update(inst, "ERROR", "$reason\n清理失败：${failure.message}")
                    } finally {
                        kotlinx.coroutines.runBlocking {
                            RecordCollector.finish(
                                this@VmService,
                                recordId,
                                inst.device,
                                RecordKind.CRASH,
                                RecordStatus.FAILED,
                                reason + (uptime?.let { "\n运行 $it 后崩溃" } ?: ""),
                                inst.auditSession ?: SessionId.Legacy,
                            )
                        }
                        // 崩溃也是一次会话的结束：收尾台账，否则这次日志永远不能淘汰。
                        endAuditSession(inst)
                        inst.runningSince = 0L
                        inst.busy.set(false)
                        inst.microphone = false
                        inst.camera = false
                        stopForegroundIfIdle()
                        if (!inst.deleted) publish(inst)
                    }
                }
            } else if (reason.isEmpty() && !inst.powerBusy) {
                // 每拍都推：**画面进度（帧数/是否出画）就是走这一份运行态**，
                // 降频或按字段过滤会让启动页的进度忽快忽慢（2026-11-15 维护者点名）。
                inst.powerBusy = true
                inst.worker.execute {
                    try { session.updateDisplayPower() }
                    catch (failure: Exception) { Log.w(TAG, "Display power: ${failure.message}") }
                    finally { inst.powerBusy = false }
                }
                publish(inst)
            }
        }
    }

    /** 本次运行持续了多久（崩溃记录里带上，方便判断"跑到一半挂"还是"刚起来就挂"）。 */
    private fun uptimeText(inst: Instance): String? {
        val since = inst.runningSince
        if (since <= 0L) return null
        val seconds = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "$seconds 秒"
            seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
            else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
        }
    }

    private fun foregroundTypes(): Int {
        val microphone = instances.values.any { it.microphone }
        val camera = instances.values.any { it.camera }
        return (if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) or
                (if (Build.VERSION.SDK_INT >= 30 && microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                (if (Build.VERSION.SDK_INT >= 30 && camera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
    }

    private fun stopForegroundIfIdle() {
        if (instances.values.none { it.session?.alive == true }) {
            instances.values.forEach { it.microphone = false; it.camera = false }
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    /**
     * 应用对外名称（通知标题、权限说明、诊断抬头统一用它）。
     *
     * 不许再写 `"A10VM"`：那是宿主层的旧代号，用户看到的应该是**应用名称**（`R.string.app_name`，
     * 也就是桌面图标下面那三个字）。改一次资源就全变（2026-11-15 维护者点名）。
     */
    private val appLabel: String
        get() = runCatching { getString(me.kanadex.versebox.R.string.app_name) }
            .getOrDefault("VerseBox")

    private fun notification(): Notification {
        val running = instances.values.filter { it.session?.alive == true }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            running.isEmpty() -> "桌面运行中"
            running.size == 1 -> "${running.first().name} · ${running.first().romSnapshot?.name ?: "Android"} 运行中"
            else -> "${running.size} 个虚拟机运行中"
        }
        // 小图标用**应用自己的字形**（单色版，系统会按状态栏要求染成白色系）；
        // 以前是 android.R.drawable.ic_media_play —— 一个和本应用没有任何关系的系统播放三角。
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(me.kanadex.versebox.R.drawable.ic_launcher_foreground_monochrome)
            .setContentTitle(appLabel).setContentText(text)
            .setContentIntent(open).setOngoing(true).build()
    }

    private fun update(inst: Instance, value: String, message: String) {
        if (inst.deleted) return
        Log.i(TAG, "${inst.id} $value: $message")
        inst.state = value
        inst.detail = message
        publish(inst)
        if (inst.deleting) return
        val entry = "${java.util.Date()}\ninstance=${inst.id}\nrom=${inst.device.romId}\nstate=$value\n${message.take(1500)}\n"
        inst.diagnostics.execute {
            try {
                File(registry.rootOf(inst.device), "session-state.log").apply {
                    parentFile?.mkdirs()
                    writeText(entry, Charsets.UTF_8)
                }
            } catch (failure: Exception) { Log.w(TAG, "State log: ${failure.message}") }
        }
        if (value == "RUNNING" || value == "IDLE" || value == "ERROR")
            getSystemService(NotificationManager::class.java).notify(10, notification())
    }

    @Suppress("DEPRECATION")
    private fun diagnosticText(inst: Instance): String {
        val info = packageManager.getPackageInfo(packageName, 0)
        val text = appLabel + " 运行记录（详细信息）\n虚拟机：${inst.name}（${inst.id}）\n" +
                "ROM：${inst.rom?.name ?: inst.device.romId} · Android ${inst.rom?.androidVersion ?: "?"}" +
                " · API ${inst.rom?.apiLevel ?: 0}\n" +
                "数据目录：${registry.rootOf(inst.device).path}\n" +
                "时间：${java.util.Date()}\n" +
                "应用：$packageName ${info.versionName} (${info.longVersionCode})\n" +
                "设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
                "内核：${System.getProperty("os.version")}\n状态：${inst.state}\n最近操作：${inst.detail.take(2400)}\n" +
                (inst.session?.logs() ?: "运行环境不可用：${inst.device.romId}")
        logChunks(text)
        return text
    }

    /** 完整诊断文本也写 logcat（`adb logcat -s A10VM_DIAG`），单条按 3 KB 分块。 */
    private fun logChunks(text: String) {
        if (text.isEmpty()) return
        var index = 0
        while (index < text.length) {
            val end = minOf(index + 3000, text.length)
            Log.i("A10VM_DIAG", text.substring(index, end))
            index = end
        }
    }

    private fun failed(inst: Instance, failure: Throwable, stage: String) {
        if (inst.deleted) return
        windows.remove(inst.id)
        inst.window = null
        try { inst.session?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        // 死会话必须丢掉：否则下次启动会复用它，用户看到的是「导入了 ROM 还是要重启 App」。
        inst.session = null
        val detail = failure.suppressed.joinToString("\n") { "清理失败：${it.message}" }
        logChunks("$stage：${failure.message}" + if (detail.isEmpty()) "" else "\n$detail")
        update(inst, "ERROR", "$stage：${failure.message}" + if (detail.isEmpty()) "" else "\n$detail")
    }

    private fun refreshNetwork() {
        for (inst in instances.values.toList()) {
            try { inst.session?.refreshNetwork() } catch (failure: Exception) {
                Log.w(TAG, "Network refresh: ${failure.message}")
            }
        }
    }

    /**
     * 删除一台虚拟机：先停会话，再把它的目录移到 `.trash-` 前缀下异步清理。
     *
     * 旧版 Android 10 的状态根就是 `filesDir` 本身，绝不可能整体删除；这种情况只删
     * 它自己的 `guest-runtime/` 与 `native-window/`，其余宿主文件保持不动。
     */
    private fun delete(inst: Instance) {
        inst.deleting = true
        // 状态根会被整体移进 .trash-：台账也随之消失，不要再补一次 onSessionEnd。
        inst.auditSession = null
        windows.remove(inst.id)
        inst.window = null
        update(inst, "BUSY", "正在删除 ${inst.name}…")
        try { inst.session?.close() } catch (failure: Exception) {
            Log.w(TAG, "删除前停止失败：${failure.message}")
        }
        val files = filesDir.canonicalFile
        val root = File(files, inst.device.root).canonicalFile
        val targets = if (inst.device.root.isNotEmpty() && root.path.startsWith(files.path + File.separator))
            listOf(root)
        else listOf(File(files, inst.device.runtimeDir), File(files, inst.device.windowDir))
        val stamp = System.currentTimeMillis()
        val trash = targets.mapNotNull { target ->
            if (!target.exists() || target == files) return@mapNotNull null
            val moved = File(files, ".trash-${inst.id}-$stamp-${target.name}")
            if (target.renameTo(moved)) moved else null
        }
        synchronized(inst) {
            registry.delete(inst.id)
            inst.deleted = true
        }
        instances.remove(inst.id, inst)
        publishDeleted(inst)
        stopForegroundIfIdle()
        inst.worker.shutdown()
        inst.diagnostics.shutdown()
        if (trash.isNotEmpty()) thread(name = "a10vm-trash-${inst.id}") {
            trash.forEach { cleanupTrash(it) }
        }
        Log.i(TAG, "${inst.id} DELETED: ${targets.joinToString()}")
    }

    /**
     * 孤儿 `.trash-*` 清理（docs/log-audit/05 阶段 4）。
     *
     * 旧实现删不掉只写一句 `Log.w`，用户那边表现为"磁盘莫名少了几百 MB 又没少"。
     * 现在超过宽限期仍删不掉就落一条审计事件（`trash_failed`），
     * 让"有 200 MB 垃圾没删掉"这件事可被查到，而不是只活在 logcat 里。
     */
    private fun cleanTrash() {
        val grace = runCatching { AuditBootstrap.policy(this).trashGraceMillis }.getOrDefault(0L)
        filesDir.listFiles { file -> file.isDirectory && file.name.startsWith(".trash-") }
            ?.forEach { trash ->
                val cleaned = runCatching { cleanupTrash(trash) }
                    .onFailure { Log.w(TAG, "Trash cleanup: ${it.message}") }
                    .getOrDefault(false)
                if (cleaned || !trash.exists()) return@forEach
                val age = System.currentTimeMillis() - trash.lastModified()
                if (age < grace) return@forEach
                AuditBootstrap.emit(
                    this,
                    AuditEventKind.TRASH_FAILED,
                    mapOf("dir" to trash.name, "age" to age, "bytes" to runCatching { trash.walkTopDown().sumOf { it.length() } }.getOrDefault(0L)),
                )
                Log.w(TAG, "Trash 无法清理（已 ${age / 60000} 分钟）：${trash.name}")
            }
    }

    private fun cleanupTrash(trash: File): Boolean {
        for (attempt in 0 until 20) {
            if (!trash.exists()) return true
            if (trash.deleteRecursively()) {
                Log.i(TAG, "Trash cleaned: ${trash.path}")
                return true
            }
            Thread.sleep(250)
        }
        Log.w(TAG, "Trash cleanup deferred: ${trash.path}")
        return false
    }

    /** 服务里仍然活着的审计会话：清理永不碰它们（docs/log-audit/02 §2.3）。 */
    private fun liveSessions(): Set<SessionId> = instances.values.mapNotNull { it.auditSession }.toSet()

    /**
     * 会话开始。sessionId 由**宿主**生成（时间戳只用于排序，唯一性来自 UUID），
     * 台账写进 `vm/sessions/<sessionId>/`：`.active` 标记 + `meta` 的 started。
     */
    private fun beginAuditSession(inst: Instance) {
        val session = SessionId.generate(System.currentTimeMillis(), java.util.UUID.randomUUID().toString())
        inst.auditSession = session
        val layout = runCatching { AuditIndex.layout(this, inst.device) }.getOrNull() ?: return
        AuditBootstrap.scope.launch {
            runCatching { AuditBootstrap.engine(this@VmService).onSessionBegin(layout, session) }
                .onFailure { Log.w(TAG, "审计会话开始失败：${it.message}") }
        }
    }

    /** 会话结束（停止或崩溃）：收尾台账，然后按会话组做一次维护。 */
    private fun endAuditSession(inst: Instance) {
        val session = inst.auditSession ?: return
        inst.auditSession = null
        val layout = runCatching { AuditIndex.layout(this, inst.device) }.getOrNull() ?: return
        AuditBootstrap.scope.launch {
            runCatching { AuditBootstrap.engine(this@VmService).onSessionEnd(layout, session) }
                .onFailure { Log.w(TAG, "审计会话收尾失败：${it.message}") }
            AuditIndex.maintain(this@VmService, AuditTrigger.SESSION_END, liveSessions())
        }
    }

    /**
     * 把一次操作携带的字符串列表解出来。
     *
     * 契约是 JSON 数组（`["a","b"]`）；这里同时接受旧的竖线分隔（`a|b`）。
     * 本轮真机事故：UI 传 `com.finalwire.aida64`、服务端直接 `JSONArray(...)`，
     * 抛 "cannot be converted to JSONArray"，一个应用都没装，而记录还被记成 SUCCESS。
     * 两侧都收紧之后，这条兜底仍然保留：它是"传输格式"的最后一道闸，
     * 不该让一个分隔符把整条导入链打穿。
     */
    private fun payloadList(extra: String?): org.json.JSONArray {
        val raw = extra.orEmpty().trim()
        if (raw.isEmpty()) return org.json.JSONArray()
        return runCatching { org.json.JSONArray(raw) }.getOrElse {
            org.json.JSONArray(raw.split('|').map { it.trim() }.filter { it.isNotEmpty() })
        }
    }

    private fun recordKindOf(action: String): RecordKind = when (action) {
        START -> RecordKind.LAUNCH
        STOP -> RecordKind.STOP
        INSTALL_APK -> RecordKind.INSTALL_APK
        IMPORT_APPS -> RecordKind.IMPORT_APPS
        IMPORT_FILES -> RecordKind.IMPORT_FILES
        INSPECT -> RecordKind.INSPECT
        CHECK_DESKTOP -> RecordKind.CHECK_DESKTOP
        DELETE -> RecordKind.DELETE
        else -> RecordKind.LAUNCH
    }

    private fun publishDeleted(inst: Instance) = main.post {
        if (destroyed) return@post
        val data = Bundle().apply {
            putString(EXTRA_INSTANCE, inst.id)
            putString("name", inst.name)
            putString("state", "DELETED")
            putString("detail", "${inst.name} 已删除。")
            putBoolean("busy", false)
            putBoolean("deleted", true)
        }
        clients.toList().forEach { if (!send(it, STATE, data)) clients.remove(it) }
    }


    private fun publish(inst: Instance, only: Messenger? = null) = main.post {
        if (destroyed || inst.deleted) return@post
        val session = inst.session
        if (inst.window == null) session?.window?.let { created ->
            inst.window = created
            windows[inst.id] = created
        }
        val renderer = inst.window
        val metrics = renderer?.metrics() ?: LongArray(17)
        val data = Bundle().apply {
            putString(EXTRA_INSTANCE, inst.id)
            putString("name", inst.name)
            putString("romId", inst.device.romId)
            putString("romName", inst.romSnapshot?.name)
            putString("androidVersion", inst.romSnapshot?.androidVersion)
            putInt("apiLevel", inst.romSnapshot?.apiLevel ?: 0)
            putString("state", inst.state)
            putString("detail", inst.detail)
            putBoolean("busy", inst.busy.get())
            // 用户已请求停止（启动中或运行中）：界面上要显示「正在停止 X」，而不是继续显示启动进度。
            putBoolean("stopping", inst.stopRequested.get())
            putLong("recordId", inst.recordId ?: -1L)
            if (inst.state == "BUSY" && session != null) {
                val boot = session.bootLog()
                if (boot.active) {
                    putString("bootSession", boot.session)
                    putLong("bootSequence", boot.sequence)
                    putLong("bootElapsedMillis", boot.elapsedMillis)
                    putStringArrayList("bootLines", boot.lines)
                }
            }
            putBoolean("windowReady", renderer != null)
            putBoolean("frameReady", metrics[2] > 0 && metrics[16] == metrics[6])
            putLong("frames", metrics[1])
            putString("gpu", renderer?.description().orEmpty())
            putLong("uptimeSeconds", session?.uptimeSeconds ?: 0L)
            putBoolean("anyRunning", instances.values.any { it.session?.alive == true })
        }
        val targets = if (only != null) listOf(only) else clients.toList()
        targets.forEach { client ->
            if (!send(client, STATE, data)) clients.remove(client)
        }
    }

    private fun send(client: Messenger?, what: Int, data: Bundle): Boolean = try {
        client?.send(Message.obtain(null, what).apply { this.data = data }); true
    } catch (_: RemoteException) { false }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        destroyed = true
        watcher.shutdownNow()
        diagnostics.shutdown()
        windows.clear()
        clients.clear()
        main.removeCallbacksAndMessages(null)
        instances.values.forEach { inst ->
            if (!inst.deleted) {
                inst.worker.execute {
                    try { inst.session?.close() }
                    catch (failure: Exception) { Log.e(TAG, "Session cleanup failed", failure) }
                }
                inst.worker.shutdown()
                inst.diagnostics.shutdown()
            }
        }
        super.onDestroy()
    }
}