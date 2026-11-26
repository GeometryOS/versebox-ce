package io.a10vm.ui.verse

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.kanadex.geometryos.audit.AuditTrigger
import me.kanadex.geometryos.audit.android.AuditBootstrap
import me.kanadex.geometryos.audit.android.AuditIndex
import me.kanadex.geometryos.audit.model.AuditState
import me.kanadex.geometryos.audit.model.SessionId
import io.a10vm.records.RecordCollector
import io.a10vm.records.RecordKind
import io.a10vm.records.RecordStatus
import io.a10vm.runtime.limits.ChildProcessLimit
import io.a10vm.runtime.limits.ChildProcessLimitMonitor
import me.kanadex.geometryos.rom.RomKeys
import me.kanadex.geometryos.rom.RomTexts
import me.kanadex.geometryos.rom.RomVersionDecision
import me.kanadex.geometryos.rom.RomVersionEntry
import me.kanadex.geometryos.rom.RomVersionPolicy
import io.a10vm.runtime.RenameResult
import io.a10vm.runtime.RomCatalog
import io.a10vm.runtime.RomSpec
import io.a10vm.runtime.VmConnection
import io.a10vm.runtime.VmDevice
import io.a10vm.runtime.VmRegistry
import io.a10vm.runtime.VmService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一台虚拟机的运行态。UI 只读这个结构，不再自己拆 Bundle。 */
data class VmRuntime(
    val state: String = "IDLE",
    val detail: String = "",
    val busy: Boolean = false,
    val windowReady: Boolean = false,
    val frameReady: Boolean = false,
    val frames: Long = 0,
    val bootSession: String = "",
    val bootSequence: Long = 0,
    val bootElapsedMillis: Long = 0,
    val bootLines: List<String> = emptyList(),
    /** 本次操作在记录中心的行号；启动页用它把"查看记录"直接定位到这一条。 */
    val recordId: Long? = null,
    /**
     * 与服务的连接断开了（窗口切后台 / 服务重启）。
     *
     * 单独一个字段而不是把 state 伪造成 ERROR：**连接断开不等于虚拟机崩了**，
     * 第一屏据此不弹「运行异常」（`docs/ui-rework/09/03` §3）。
     */
    val disconnected: Boolean = false,
    /** 用户已经请求停止，服务正在收尾（启动中也能停）。 */
    val stopping: Boolean = false,
) {
    val running: Boolean get() = state == "RUNNING"
    val failed: Boolean get() = state == "ERROR"
}

/** 记录中心的定位请求：落到列表 → 滚到该条 → 闪两下（`docs/ui-rework/09/05` §2/§3）。 */
data class RecordFocus(
    val vmId: String?,
    val recordId: Long?,
    val blink: Boolean = true,
)

/**
 * ROM 管理的定位请求。
 *
 * @param key ROM 主键（运行期主键 `<id>@<version>`）
 * @param repairVmId 非空 = 这条是「修完要重跑」的自动修复链（`09/04`）
 */
data class RomFocus(
    val key: String,
    val blink: Boolean = true,
    val repairVmId: String? = null,
    /**
     * 要定位的**那一行**（`<来源>:<romKey>`）。
     *
     * 同版本可能存在内置行与本地行两行：只给 romKey 时列表只能"取第一条"，
     * 于是修复的可能是另一份内容（用户看到"修了，但点开还是老样子"）。
     * 为空 = 旧调用点（只记了 romKey），列表按第一行匹配。
     */
    val rowId: String? = null,
)

/**
 * 应用级状态：虚拟机注册表 + 运行态 + 日志通道。
 *
 * 生命周期跟 Activity 走（[bind]/[unbind]），但对象本身在 Compose 树顶层只建一次，
 * 所以切 tab、进二级页都不会丢连接 —— 这正是「回到主页不是重开一个主页」的基础。
 */
class AppState(private val context: Context) {
    private val registry by lazy { VmRegistry.get(context) }
    private val roms = RomCatalog(context)
    /** ROM 仓库（扫描/导入/解包/删除的唯一入口，见 docs/rom-package/06）。 */
    private val romRepo = me.kanadex.geometryos.rom.RomRepository.get(context)
    private val _roms = MutableStateFlow<List<RomSpec>>(emptyList())
    val availableRoms: StateFlow<List<RomSpec>> = _roms.asStateFlow()

    /** ROM 管理页要的行（内置/本地/旧格式 + 状态）。 */
    private val _romRows = MutableStateFlow<List<me.kanadex.geometryos.rom.RomRow>>(emptyList())
    val romRows: StateFlow<List<me.kanadex.geometryos.rom.RomRow>> = _romRows.asStateFlow()

    /**
     * 主页数字卡上的 ROM 数量 = **ROM 总数**（内置 + 本地 + 旧格式），与 ROM 管理列表的行数一致。
     *
     * 不许拿 [availableRoms] 的 size 顶替：那是"已解包、可以直接启动"的 ROM。新装应用时内置包
     * 还没解包（这是设计：不在启动时全解包，浪费时间），于是卡片显示 0 —— 用户看到的是
     * "安装包里根本没有 ROM"，而真实现象只是"还没解包"（2026-11-14 真机点名）。
     * 口径只有一条：**卡片上的数字 == 点进去以后列表里的行数**（`docs/rom-package/06` §1.2）。
     */
    private val _romTotal = MutableStateFlow(0)
    val romTotal: StateFlow<Int> = _romTotal.asStateFlow()

    /**
     * romId → [RomSpec] 的索引：`android15`（旧 id）和 `android15@1.0.0`（romKey）都能命中，
     * 同一 id 上装好的 ROM 包优先（docs/rom-package/02 §8）。
     *
     * 存在内存里是为了让 `romOf()` 这个在卡片渲染里被反复调用的路径**不碰数据库**
     * （docs/ui-rework/05 §1）。
     */
    @Volatile private var specIndex: Map<String, RomSpec> = emptyMap()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private companion object {
        /** 等停止真正结束的上限；到点也照样去清一次任务卡片（幂等）。 */
        const val STOP_TASK_CLEANUP_TIMEOUT = 90_000L
    }

    // 启动期不要在构造里读磁盘：AppState 是在组合里创建的，SQLite 建表/迁移 + 首次
    // 查询会把首帧堵住（冷启动慢的元凶）。改成后台读，读完再推给 UI。
    // 异步期间 devices 为空，页面显示空态；读完立刻刷新，代价远小于阻塞首帧。
    private val _devices = MutableStateFlow<List<VmDevice>>(emptyList())

    /** 注册表是否已读完：异步加载期间页面不要显示「还没有虚拟机」，避免冷启动闪空态。 */
    private val _devicesLoaded = MutableStateFlow(false)
    val devicesLoaded: StateFlow<Boolean> = _devicesLoaded.asStateFlow()
    private val _runtimes = MutableStateFlow<Map<String, VmRuntime>>(emptyMap())
    private val _logs = MutableStateFlow("")

    /**
     * 宿主是否还在限制子进程（Android 12+）。
     *
     * 只是 [ChildProcessLimitMonitor] 的**只读视图**（主页状态卡用）。检测的启动、取消、
     * 状态推进都不在这里：页面通过 `ProcessLimitViewModel` 发 Action，runtime 层的独立线程执行，
     * 页面销毁后结论仍然保留。
     */
    val childLimit: StateFlow<ChildProcessLimit> =
        ChildProcessLimitMonitor.state
            .map { it.limit }
            // **WhileSubscribed 而不是 Eagerly**：这个流只有主页那张卡在看。
            // Eagerly 会让它在整个进程生命周期里一直占着一个 Main 上的协程，
            // 每次检测回传（fork 中 / 结论文案变化）都要过一次 map + 一次派发，
            // 而这时用户多半根本不在那一页（2026-11-15 卡顿排查）。
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ChildProcessLimit.Unknown("尚未检测"))

    /**
     * 日志/归档的容量视图 —— 唯一真相（docs/log-audit/02 §5）。
     *
     * 记录页、设置页、审计页都只读这一个流：页面不算字节、不判阈值，
     * 数据来自 `AuditEngine` 的 `StateFlow<AuditState>`。
     */
    val auditState: StateFlow<AuditState> = AuditBootstrap.engine(context).state

    /** 是否需要弹「系统在限制子进程」对话框（检测到受限且未被「不再提示」抑制时为 true）。 */
    val limitAlert: StateFlow<Boolean> =
        ChildProcessLimitMonitor.state
            .map { it.alert }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)
    init {
        // 启动即检测一次（无论用户进不进那个页面、无论虚拟机是否在跑），
        // 复用与点击检测完全相同的异步入口：这里只投递一条 Intent，UI 线程不等。
        // attach 先把上次落盘的结论读出来，所以首屏看到的是上次状态，随后结论回来再刷新。
        ChildProcessLimitMonitor.attach(context)
        // 启动即检测一次（无论用户进不进那个页面、无论虚拟机是否在跑），
        // 复用与点击检测完全相同的异步入口：这里只投递一条 Intent，UI 线程不等。
        // attach 先把上次落盘的结论读出来，所以首屏看到的是上次状态，随后结论回来再刷新。
        // **fork 发生在 :phantom 进程**（PhantomProbeService），不在本进程，不占主线程；
        // 所以这里不许以"怕卡"为由做节流——节流会让检测结果过期（2026-11-15 维护者纠正）。
        scope.launch(Dispatchers.Default) {
            ChildProcessLimitMonitor.start(io.a10vm.runtime.limits.LimitOrigin.AUTO)
        }
        scope.launch(Dispatchers.IO) {
            // 1) 注册表：SQLite 首次打开 + 迁移都在这里做，不占主线程。
            val list = runCatching { registry.list() }.getOrElse { emptyList() }
            _devices.value = list
            _devicesLoaded.value = true
            // 2) ROM 目录：首次 list() 会解析 assets 里的 YAML，顺手预热，
            //    免得主页组合时在主线程序列化一次；随后扫一遍已安装的 ROM。
            reloadRomsLocked()
        }
    }

    /**
     * 重新扫 ROM（IO 线程上跑：读自述、量目录、查登记表）。
     *
     * **只扫一次**：先用一次 [me.kanadex.geometryos.rom.RomRepository.rows] 拿到全部登记，
     * 再由它算选择页用的 Spec 列表与 romId 索引——不要在这里扫两遍。
     */
    private fun reloadRomsLocked() {
        val rows = runCatching { romRepo.rows() }.getOrElse { emptyList() }
        _romRows.value = rows
        _romTotal.value = rows.size
        val specs = if (rows.isEmpty()) {
            runCatching { roms.list() }.getOrElse { emptyList() }
        } else {
            runCatching { romRepo.specsOf(rows) }.getOrElse { emptyList() }
        }
        // 索引键有三档（`romOf` 在卡片渲染里被反复调用，必须纯内存）：
        //   1. `romKey@<sha8>`：**精确身份**（同版本两份内容靠它分开）；
        //   2. `romKey`：老实例只记了 romKey；
        //   3. `id`：老实例只记了 `android15` 这种 id。
        // 每一档都只放**先到的那一条**（rows 已按来源/就绪排序），所以老键不会把精确键盖掉。
        val index = LinkedHashMap<String, RomSpec>()
        for (row in rows) {
            if (!row.isReady || row.source == me.kanadex.geometryos.rom.RomSource.Legacy) continue
            val spec = runCatching { romRepo.specOf(row) }.getOrNull() ?: continue
            index.putIfAbsent(identityKey(row.key, row.payloadSha256), spec)
            index.putIfAbsent(row.key, spec)
            index.putIfAbsent(row.id, spec)
        }
        for (spec in specs) {
            index.putIfAbsent(spec.id, spec)
            index.putIfAbsent(me.kanadex.geometryos.rom.RomKeys.idOf(spec.id), spec)
        }
        _roms.value = specs
        specIndex = index
    }

    /** 内存索引的精确身份键：`romKey@<sha8>`；没有指纹时就是 romKey（老实例语义）。 */
    private fun identityKey(romKey: String, payload: String): String {
        if (romKey.isEmpty() || payload.isEmpty()) return romKey
        return romKey + "@" + me.kanadex.geometryos.rom.RomLayout.payloadTag(payload)
    }

    /** 这台虚拟机绑定的 ROM 身份（给 `:vm-rom` 的判据用；空字段表示老实例）。 */
    private fun refOf(device: VmDevice?): me.kanadex.geometryos.rom.RomRef {
        val key = device?.romId.orEmpty()
        val source = me.kanadex.geometryos.rom.RomSource.values()
            .firstOrNull { it.name == device?.romSource }
        return me.kanadex.geometryos.rom.RomRef(key, source, device?.romPayload.orEmpty())
    }

    /** ROM 变化后（导入/删除/清缓存）重新扫一遍；IO 上跑，不阻塞 UI。 */
    fun refreshRoms() {
        scope.launch(Dispatchers.IO) { reloadRomsLocked() }
    }

    /** 虚拟机要启动时先停掉检测，避免和真实负载抢系统的清理名额。 */
    fun cancelChildLimitProbe() = ChildProcessLimitMonitor.cancel()

    /** 用户点了「不再提示」：抑制到下次真正恢复正常为止。 */
    fun muteLimitAlert() = ChildProcessLimitMonitor.mute()

    /** 对话框已展示，清掉一次性事件。 */
    fun consumeLimitAlert() = ChildProcessLimitMonitor.consumeAlert()

    /** 最近一次「查看日志」返回的原文（原始通道，保留给启动页/调试用）。 */
    val rawLogs: StateFlow<String> = _logs.asStateFlow()
    val devices: StateFlow<List<VmDevice>> = _devices.asStateFlow()
    val runtimes: StateFlow<Map<String, VmRuntime>> = _runtimes.asStateFlow()

    /**
     * 记录中心的**定位**请求（`docs/ui-rework/09/05` §2）。
     *
     * 不是「打开详情」：用户要看到的是「这条在整份记录里的位置」，所以落点是列表 + 滚动 + 闪烁。
     * 消费点唯一（HistoryViewModel 消费即清），页面不自己找记录。
     */
    var recordFocus by mutableStateOf<RecordFocus?>(null)
        private set

    /** 切到「记录」tab 的请求计数：每次自增，主壳据此切换（0 = 从未请求过）。 */
    var recordsTabRequest by mutableStateOf(0)
        private set

    /**
     * 切到「虚拟机」tab 的请求计数（0 = 从未请求过）。
     *
     * 用途只有一个：分辨率改完自动重启时，用户要求“回到主页的虚拟机 tab，再从那里启动”，
     * 而不是把启动塞进加载框里（docs/display-settings/02 §3）。
     * 主壳的两个分支（Miuix / Material 3）都监听它。
     */
    var vmTabRequest by mutableStateOf(0)
        private set

    /** ROM 管理的定位请求：滚到该行 + 闪两下，修复链里还会自动入队（`09/04` §2）。 */
    var romFocus by mutableStateOf<RomFocus?>(null)
        private set

    /** 「打开 ROM 管理」的请求计数（C2 导入入口用它）。 */
    var openRomListRequest by mutableStateOf(0)
        private set

    /** 「打开某台虚拟机的 ROM 版本管理」的请求（vmId）；消费即清。 */
    var vmRomVersionRequest by mutableStateOf<String?>(null)
        private set

    /** 修复完成后请求这台虚拟机重新启动（`09/04` §2 第 6 步）。 */
    var vmRestartRequest by mutableStateOf<String?>(null)
        private set

    /** 新建虚拟机流程当前选中的 ROM（运行期主键 romKey）：创建时用它。 */
    val createVmRomId = MutableStateFlow<String?>(null)

    /**
     * 用户**具体选中的那一行**（`<source>:<romKey>`）。
     *
     * 同一个 romKey 可能同时有内置行与本地行（版本号相同、内容可能相同也可能不同）。
     * 只记 romKey 会让两行同时显示为"已选"——那不是同一个对象（2026-11-12 用户点名）。
     */
    val createVmRowId = MutableStateFlow<String?>(null)

    private val vm = VmConnection(
        context = context,
        onState = ::onBundle,
        onLogs = { _logs.value = it },
        onDisconnected = { _runtimes.value = emptyMap() },
    )

    /**
     * 绑定服务，并在启动一次之后做一次「孤儿记录收尾」：
     * 只有**没有运行时认领**的 RUNNING 记录才会被标成 CANCELLED 并补归档，
     * 所以正在启动/正在运行的虚拟机不会被动到。
     */
    fun bind() {
        vm.bind()
        scope.launch {
            // 等服务把在跑的实例报上来再判断（最多等 5 秒），避免把活着的记录误收尾。
            val deadline = System.currentTimeMillis() + 5_000
            while (_runtimes.value.isEmpty() && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(250)
            }
            val live = _runtimes.value.values.mapNotNull { it.recordId }.toSet()
            withContext(Dispatchers.IO) {
                io.a10vm.records.RecordRecovery.reconcile(context, live)
            }
            // 收尾可能补写了归档，容量视图要跟着更新一次（页面顶部读的就是它）。
            refreshAudit()
        }
    }

    fun unbind() = vm.unbind()

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            _devices.value = runCatching { registry.list() }.getOrElse { emptyList() }
            _devicesLoaded.value = true
        }
    }

    fun romOf(device: VmDevice?): RomSpec? {
        val id = device?.romId ?: return null
        // 精确身份优先：同版本两份内容（内置一份 + 本地一份）各自有自己的解包目录，
        // 按 romKey 命中会把 A 那份的 romRoot 交给正在用 B 的实例（P5 的运行时一半）。
        specIndex[identityKey(id, device.romPayload)]?.let { return it }
        return specIndex[id] ?: _roms.value.firstOrNull { it.id == id }
    }

    /**
     * **精确**解析：`rom_id` 必须正好是某一份就绪 ROM 的 key，不做"同 id 猜一个"。
     *
     * 界面上一律用这个回答"这台虚拟机用的是哪个版本"。用 [romOf] 会把
     * `rom_id = android15`（旧格式 id，实际并不存在这一份）显示成 `Android 15 V1.1.0`——
     * 用户看到的是"已经选了"，实际一启动就被闸门拦住（2026-11-12 真机复现）。
     */
    fun exactRomOf(device: VmDevice?): RomSpec? {
        val id = device?.romId ?: return null
        if (id.isEmpty()) return null
        return specIndex[identityKey(id, device.romPayload)]?.takeIf { it.id == id }
    }

    fun roms(): List<RomSpec> = _roms.value

    fun device(id: String?): VmDevice? = _devices.value.firstOrNull { it.id == id }

    fun runtime(id: String?): VmRuntime = _runtimes.value[id] ?: VmRuntime()

    // ------------------------------------------------------------- 动作

    /**
     * 启动一台虚拟机。
     *
     * 启动前先在 IO 上做一次**解包自检**：payload 缺文件、但原始包还在的话，
     * 不把 START 发给服务（服务端自愈会在没人看的地方卡在「正在校验」，用户只看到
     * 点了没反应），而是直接走 ROM 管理里的修复链：定位那一行 → 自动重解（有进度）
     * → 修完回到这台虚拟机的启动页重新跑一次（`docs/ui-rework/09/04`）。
     *
     * 判断放在这里而不是页面里：它要读盘，而且两个 UI 分支、快捷入口都共用同一条启动路径。
     */
    fun start(id: String) {
        cancelChildLimitProbe()
        scope.launch {
            val vm = device(id) ?: registry.find(id) ?: return@launch
            // ① 连 rom_id 都没有（旧实例 / 数据被清）：先去版本管理页选一个，别把 START 发下去。
            if (vm.romId.isBlank()) {
                vmRomVersionRequest = id
                return@launch
            }
            val decision = withContext(Dispatchers.IO) {
                runCatching { romDecision(vm) }.getOrNull()
            }
            when (decision) {
                // 需要**选一个版本**：这不是"提示"，是一次**非破坏性跳转**。
                //
                // 为什么不能弹窗：
                //   1. 弹窗挂在 MainActivity 的导航层，用户可能正在启动页（另一个 Activity）上，
                //      他根本看不到；看到也只是一行字，选不了版本；
                //   2. "选版本"是页面能做的事（版本管理页），不是对话框能做的事。
                //
                // 所以：回虚拟机管理页 → 进 ROM 版本管理页，让用户在那里选。
                // 数据、实例目录、正在运行的会话一律不动（非破坏性）。
                is RomVersionDecision.CurrentMissing, is RomVersionDecision.Unavailable -> {
                    vmRomVersionRequest = id
                    return@launch
                }

                // 版本更新提示由启动页 Activity 的启动闸门唯一呈现，闸门结论出来前不发 START。
                is RomVersionDecision.UpdateAvailable -> return@launch

                else -> Unit
            }
            // ② 解包体不全但原包还在：也**不能先开窗口**，否则修复提示同样被启动页盖住。
            //    定位必须带 rowId：同版本可能有内置/本地两行，只给 romKey 会修错那一份。
            val broken = brokenRom(vm)
            if (broken != null) {
                focusRom(broken.key, repairVmId = id, rowId = broken.rowId)
                return@launch
            }
            beginStart(vm)
        }
    }

    /**
     * 判据全部通过之后才做的事：**开窗口 → 发 START**。
     *
     * 顺序不能反。上一版是 `startAndOpen` 里先 `openDesktop` 再 `start`，
     * 于是所有闸门（选版本 / 可选更新 / 需要解包）都发生在启动页**背后**：
     * 用户看到的是一个 0% 的启动页，上层没有任何提示——他只会以为"点了没反应"。
     * 启动页是"已经开始启动"的证据，不是"准备启动"的容器。
     */
    private suspend fun beginStart(vm: VmDevice) {
        io.a10vm.ui.vm.openDesktop(context, vm, romOf(vm))
        perform(VmService.START, vm.id)
    }

    /**
     * 真正发起启动（跳过了版本闸门）。
     *
     * 与 [start] 分开是因为「继续启动」必须能绕过提示，否则点一次弹一次。
     */
    private suspend fun launchNow(id: String) = perform(VmService.START, id)

    /**
     * 这台虚拟机现在该不该提示更新 / 还开不开得起来（唯一判据在 [RomVersionPolicy]）。
     *
     * **只在 IO 线程调用**（会扫 ROM 登记）。
     */
    fun romDecision(device: VmDevice?): RomVersionDecision? {
        val romId = device?.romId ?: return null
        if (romId.isEmpty()) return null
        val entries = romRepo.versionEntries()[RomKeys.idOf(romId)].orEmpty()
        if (entries.isEmpty()) return null
        // 判据吃的是**身份**（key + 来源 + 内容指纹），不是 romKey 字符串：
        // 只看 key 会把"同版本的本地那一份"当成"我正在用的那一份"（2026-11-12 真机）。
        return RomVersionPolicy.decision(entries, refOf(device))
    }

    // 这里曾经有 ensureRomReady()（创建前就地解包）与 romGate()（创建前拦一道），两个都删了：
    // 创建只写注册表；要不要解包由**启动链**回答（RomRepository.gate → 跳 ROM 管理解包 → 回启动页）。
    // 创建页塞解包 = 用户起好名字点保存却被拽去解包，回来还得重填（2026-11-15 维护者点名）。

    /** 某台虚拟机的同系列版本清单（版本管理页用）。只在 IO 线程调用。 */
    fun romVersions(romId: String?): List<RomVersionEntry> {
        if (romId.isNullOrEmpty()) return emptyList()
        return romRepo.versionEntries()[RomKeys.idOf(romId)].orEmpty()
            .let { RomVersionPolicy.series(it, RomKeys.idOf(romId)) }
    }

    /** 直接打开版本管理页（VM 管理页入口）。 */
    fun openVmRomVersion(vmId: String) {
        vmRomVersionRequest = vmId
    }

    fun consumeVmRomVersionRequest(): String? = vmRomVersionRequest.also { vmRomVersionRequest = null }

    /**
     * 写这台虚拟机的 ROM 版本（唯一写入点）。
     *
     * 写的是**身份**（`rom_id` + 内容指纹 + 来源），不是只写一个字符串：同版本号的内置一份与
     * 本地一份内容不同时，只写 romKey 会让"切换"变成空操作（`docs/rom-package/14-版本管理/04` R3）。
     *
     * 1. 写库经 [VmRegistry.setRomId]（同一连接、事务内读回自证，三列都核对）；
     * 2. 写审计事件 `vm_rom_version_changed`（含 from/to 的指纹，事后能分清换的是哪一份）；
     * 3. 写一条记录（[RecordKind.ROM_VERSION]），记录中心里能查到换成了哪一版；
     * 4. 成功后 [refresh]，卡片的系统名立刻跟着变。
     *
     * 调用方必须在 IO 线程上（RomVersionViewModel 用 withContext(Dispatchers.IO)）。
     */
    fun setRomVersion(
        id: String,
        romKey: String,
        payloadSha256: String = "",
        source: me.kanadex.geometryos.rom.RomSource? = null,
    ): Boolean {
        val device = device(id) ?: registry.find(id) ?: return false
        val saved = registry.setRomId(id, romKey, payloadSha256, source?.name.orEmpty())
        AuditBootstrap.emit(
            context,
            "vm_rom_version_changed",
            mapOf(
                "vm" to id,
                "from" to device.romId,
                "to" to romKey,
                "from_payload" to device.romPayload,
                "to_payload" to payloadSha256,
                "from_source" to device.romSource,
                "to_source" to source?.name.orEmpty(),
                "ok" to saved,
            ),
        )
        if (!saved) return false
        // 记录里写的是"换成了哪一份"：同版本两行时，摘要必须能分开。
        val rom = romRepo.versionEntries()[RomKeys.idOf(romKey)].orEmpty()
            .firstOrNull { entry ->
                entry.key == romKey &&
                    (payloadSha256.isEmpty() || entry.payloadSha256 == payloadSha256) &&
                    (source == null || entry.source == source)
            }
        val changed = device.romId != romKey || device.romPayload != payloadSha256
        RecordCollector.recordMeta(
            context = context,
            vmId = id,
            vmName = device.name,
            romId = romKey,
            romName = rom?.displayName,
            apiLevel = rom?.apiLevel ?: 0,
            kind = RecordKind.ROM_VERSION,
            status = RecordStatus.SUCCESS,
            detail = "ROM 版本：" + (rom?.nameWithVersion ?: romKey) +
                (rom?.let { "（" + RomTexts.sourceLabel(it.source) + "）" } ?: "") +
                (if (changed) "" else "（没有变化）"),
            operationEvents = listOf(
                "读取当前ROM身份完成 key=" + device.romId,
                "注册表ROM身份写入和读回结果 ok=" + saved,
                "审计事件vm_rom_version_changed已写入",
            ),
        )
        refresh()
        refreshRoms()
        return true
    }

    /**
     * 这台实例绑定的 ROM 现在是不是「解包坏了但还能修」。
     *
     * 找的是**这台实例正在用的那一行**：同版本号可能有内置/本地两行，只按 romKey 取第一条会修错
     * 那一份（把另一份重解一遍，用户看到"修了但没变"）。
     *
     * @return 需要重解的那一行；null = 不用管（就绪 / 旧格式 / 原包也不在了）
     */
    private suspend fun brokenRom(vm: VmDevice): me.kanadex.geometryos.rom.RomRow? =
        withContext(Dispatchers.IO) {
            if (vm.romId.isEmpty()) return@withContext null
            val row = runCatching { romRepo.rowFor(refOf(vm)) }.getOrNull() ?: return@withContext null
            // 判据只有一份：[me.kanadex.geometryos.rom.RomGate]（创建链问的也是它）。
            //   - NeedsExtract：解包体缺了/没解，但原包还在 → 走 ROM 管理里的修复链（有进度）；
            //   - Missing：连原包都没有 → 交给服务拒绝启动并给出「请重新导入」；
            //   - Ready / 旧格式：不用管。
            // 这里以前自己拼了一段「problems 非空 + hasArchive」的判断，与创建链各写一份。
            if (romRepo.gate(row) == me.kanadex.geometryos.rom.RomGate.NeedsExtract) row else null
        }

    /**
     * 停止一台虚拟机，并在它真正停下来之后**把它的窗口任务卡片一并移除**。
     *
     * 为什么要在这里做：用户常常是在「虚拟机」列表里点停止的，那个窗口在后台，
     * 没有 Activity 组合能收到"停完了"。留一张后台卡片等于留一个幽灵（2026-11-09 反馈）。
     * 窗口在前台的情况由 `FullscreenActivity` 自己 `finishAndRemoveTask()`，两边幂等。
     */
    fun stop(id: String) {
        perform(VmService.STOP, id)
        scope.launch {
            val deadline = System.currentTimeMillis() + STOP_TASK_CLEANUP_TIMEOUT
            while (System.currentTimeMillis() < deadline) {
                val runtime = runtime(id)
                if (!runtime.running && !runtime.busy) break
                kotlinx.coroutines.delay(300)
            }
            val device = device(id) ?: return@launch
            io.a10vm.ui.vm.removeTask(context, device)
        }
    }

    fun inspect(id: String) = perform(VmService.INSPECT, id)

    fun checkDesktop(id: String) = perform(VmService.CHECK_DESKTOP, id)

    fun enableMicrophone(id: String) = perform(VmService.ENABLE_MICROPHONE, id)

    fun enableCamera(id: String) = perform(VmService.ENABLE_CAMERA, id)

    fun delete(id: String) = perform(VmService.DELETE, id)

    fun installApk(id: String, uri: String) = perform(VmService.INSTALL_APK, id, uri)

    /**
     * 导入宿主已安装应用到某台虚拟机。
     *
     * 传输格式（JSON 数组）**只在逻辑层编码**：页面交一份 List，不许自己拼分隔符。
     * 本轮真机事故就是页面拼了竖线、服务端按 JSON 解析，一个应用都没装进去。
     */
    fun importApps(id: String, packages: List<String>) =
        perform(VmService.IMPORT_APPS, id, org.json.JSONArray(packages).toString())

    /** 导入文件到客体 Download 目录（同上：编码在逻辑层）。 */
    fun importFiles(id: String, uris: List<String>) =
        perform(VmService.IMPORT_FILES, id, org.json.JSONArray(uris).toString())

    fun requestLogs(id: String?) {
        vm.requestLogs(id)
    }

    private fun perform(action: String, id: String, uri: String? = null) {
        runCatching { vm.perform(action, uri, id) }
    }

    // ------------------------------------------------------------- 注册表

    /**
     * 以某个 ROM 新建一台虚拟机。
     *
     * 这里同时做三件必须一起做的事（缺一条就会出现「建了但查不到为什么建了」）：
     * 1. 写注册表（[VmRegistry.create]，数据层）；
     * 2. 写一条记录（[io.a10vm.records.RecordKind.CREATE_VM]）——它没有客体日志，只写索引行；
     * 3. 发一条审计事件 `vm_created`（进 audit.log）。
     *
     * 调用方必须已经在 IO 线程上（两个 ViewModel 都用 withContext(Dispatchers.IO)）。
     */
    fun create(
        romId: String,
        payloadSha256: String = "",
        source: me.kanadex.geometryos.rom.RomSource? = null,
    ): VmDevice? {
        val started = System.currentTimeMillis()
        val spec = specIndex[identityKey(romId, payloadSha256)] ?: specIndex[romId]
        // 身份从第一刻就写全：只写 romKey 的实例，之后每次启动都分不清用的是哪一份内容。
        val result = runCatching { registry.create(romId, payloadSha256, source?.name.orEmpty()) }
        val device = result.getOrNull()
        val failure = result.exceptionOrNull()?.message
        val detail = when {
            device != null -> "已创建 " + device.name + "（" + (spec?.name ?: romId) + "），数据目录 " + device.root
            else -> "创建失败：" + (failure ?: "未知错误")
        }
        RecordCollector.recordMeta(
            context = context,
            vmId = device?.id ?: "create",
            vmName = device?.name ?: "创建虚拟机",
            romId = romId,
            romName = spec?.name,
            apiLevel = spec?.apiLevel ?: 0,
            kind = RecordKind.CREATE_VM,
            status = if (device != null) RecordStatus.SUCCESS else RecordStatus.FAILED,
            detail = detail,
            startedAt = started,
            operationEvents = listOf("vm_instance注册表创建结果 ok=" + (device != null), "ROM身份保存 key=" + romId),
            failure = result.exceptionOrNull(),
        )
        AuditBootstrap.emit(context, "vm_created", mapOf(
            "vm" to (device?.id ?: ""),
            "rom" to romId,
            "ok" to (device != null),
        ))
        if (device != null) refresh()
        return device
    }

    fun rename(id: String, name: String): RenameResult {
        val result = registry.rename(id, name)
        refresh()
        return result
    }

    /**
     * 写一台虚拟机的显示模式（分辨率 / DPI）。
     *
     * 这是显示设置的**唯一写入点**：两个 UI 分支、以及以后加的任何入口都只走它。
     * 1. 写库经 [VmRegistry.setDisplayMode]（同一个 SQLiteOpenHelper，事务内读回自证）；
     * 2. 写审计事件 vm_display_changed（docs/display-settings/06 §1）；
     * 3. 写一条记录（[RecordKind.DISPLAY]），记录中心里能查到改了什么；
     * 4. 成功后 [refresh]，卡片的摘要与解析结果立刻跟着变。
     *
     * 调用方必须在 IO 线程上（VmDisplayViewModel 用 withContext(Dispatchers.IO)）。
     *
     * @return 写库并读回自证成功才 true；失败时**不**写记录、不刷新，页面必须提示失败。
     */
    fun setDisplayMode(id: String, mode: me.kanadex.geometryos.display.VmDisplayMode): Boolean {
        val device = device(id) ?: registry.find(id) ?: return false
        val saved = registry.setDisplayMode(id, mode.token)
        val host = me.kanadex.geometryos.display.android.DisplaySettings.hostDisplay(context)
        val geometry = mode.resolve(host)
        AuditBootstrap.emit(
            context,
            "vm_display_changed",
            mapOf(
                "vm" to id,
                "mode" to mode.token,
                "width" to geometry.width,
                "height" to geometry.height,
                "dpi" to geometry.densityDpi,
                "ok" to saved,
            ),
        )
        if (!saved) return false
        val rom = romOf(device)
        RecordCollector.recordMeta(
            context = context,
            vmId = id,
            vmName = device.name,
            romId = device.romId,
            romName = rom?.name,
            apiLevel = rom?.apiLevel ?: 0,
            kind = RecordKind.DISPLAY,
            status = RecordStatus.SUCCESS,
            detail = "分辨率：" + mode.title(host) + "，" + geometry.summary,
            operationEvents = listOf("读取宿主屏幕参数完成", "vm_registry显示模式写入与读回结果 ok=" + saved),
        )
        refresh()
        return true
    }

    /** 请求主壳切到「虚拟机」tab（分辨率改完自动重启时用）。 */
    fun requestVmTab() {
        vmTabRequest++
    }

    /**
     * 记录中心的定位入口：第一屏的「查看运行记录」、异常弹窗的链接、虚拟机管理页的
     * 「查看这台虚拟机的记录」都走这一条（`09/05` §2）。
     */
    fun focusRecord(recordId: Long?, vmId: String? = null) {
        recordFocus = RecordFocus(vmId = vmId, recordId = recordId, blink = true)
        recordsTabRequest++
    }

    /** 只按虚拟机筛选（不带具体记录）。 */
    fun openRecordsFor(vmId: String?) = focusRecord(null, vmId)

    /** 消费即清，防重复闪烁（`09/05` §2 最后一步）。 */
    fun consumeRecordFocus(): RecordFocus? = recordFocus.also { recordFocus = null }

    /**
     * ROM 管理的定位请求。
     *
     * [repairVmId] 非空 = 这是**启动链**的修复请求：解包/修完要让那台虚拟机重新启动。
     * 为空 = 只定位（滚到那一行闪两下，不动磁盘）——解包是 GB 级动作，不许被"某人定位一下"隐式触发。
     */
    fun focusRom(key: String, repairVmId: String? = null, rowId: String? = null) {
        romFocus = RomFocus(key = key, blink = true, repairVmId = repairVmId, rowId = rowId)
        openRomListRequest++
    }

    fun consumeRomFocus(): RomFocus? = romFocus.also { romFocus = null }

    /** 只要求打开 ROM 管理（C2 的「导入这个 ROM」）。 */
    fun openRomList() {
        openRomListRequest++
    }

    fun requestVmRestart(id: String?) {
        vmRestartRequest = id
    }

    fun consumeVmRestart(): String? = vmRestartRequest.also { vmRestartRequest = null }

    // ------------------------------------------------------------- 内部

    private fun onBundle(data: Bundle) {
        val id = data.getString(VmService.EXTRA_INSTANCE) ?: return
        if (data.getBoolean("deleted")) {
            _runtimes.value = _runtimes.value - id
            refresh()
            return
        }
        val runtime = VmRuntime(
            state = data.getString("state") ?: "IDLE",
            detail = data.getString("detail").orEmpty(),
            busy = data.getBoolean("busy"),
            stopping = data.getBoolean("stopping"),
            windowReady = data.getBoolean("windowReady"),
            frameReady = data.getBoolean("frameReady"),
            frames = data.getLong("frames"),
            bootSession = data.getString("bootSession").orEmpty(),
            bootSequence = data.getLong("bootSequence"),
            bootElapsedMillis = data.getLong("bootElapsedMillis"),
            bootLines = data.getStringArrayList("bootLines").orEmpty(),
            recordId = data.getLong("recordId", -1L).takeIf { it > 0 },
        )
        // 无条件替换：这份运行态里带着**画面进度**（frames / frameReady / uptime），
        // 每 250ms 必须让它流下去。以前试过"相等就 return"，结果启动页的进度被吞掉，
        // 表现为忽快忽慢（2026-11-15 维护者点名撤回）。
        _runtimes.value = _runtimes.value + (id to runtime)
    }

    /** 手动删除记录（索引 + 归档一起删），供记录页调用。 */
    fun deleteRecords(store: io.a10vm.records.RecordStore, ids: Collection<Long>) {
        val records = ids.mapNotNull { store.find(it) }
        RecordCollector.deleteArchives(context, records)
        store.delete(ids)
        // 删完重算一次容量视图：否则记录页顶部的"已占用"要等下一次触发才更新。
        refreshAudit()
    }

    /**
     * 触发一次审计维护（唯一自动清理入口的异步封装）。
     *
     * 永远不在主线程做：一次维护要遍历会话目录、算归档大小，还可能删掉几个 GB。
     */
    fun refreshAudit() {
        AuditBootstrap.scope.launch {
            AuditIndex.maintain(context, AuditTrigger.USER_REQUEST)
        }
    }

    /**
     * 归档触硬上限后，用户确认清理"成功记录"。
     *
     * @param confirm false 只重算状态、不删任何东西（UI 用它刷新提示文案）
     */
    fun cleanupAuditArchives(confirm: Boolean) {
        AuditBootstrap.scope.launch {
            AuditIndex.cleanup(context, confirm)
        }
    }

    /** 改名后写一条记录，失败也留痕（有据可查）：供 RenameSheet 调用。 */
    fun recordRename(device: VmDevice, ok: Boolean, detail: String) {
        scope.launch(Dispatchers.IO) {
            val id = RecordCollector.begin(context, device, romOf(device), RecordKind.RENAME) ?: return@launch
            RecordCollector.appendOperation(context, id, device, "registry", "rename_completed", detail)
            RecordCollector.finish(
                context,
                id,
                device,
                RecordKind.RENAME,
                if (ok) RecordStatus.SUCCESS else RecordStatus.FAILED,
                detail,
                SessionId.Legacy,
            )
        }
    }
}