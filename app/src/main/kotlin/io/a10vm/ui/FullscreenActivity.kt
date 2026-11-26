// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.a10vm.nativedisplay.NativeSurfaceView
import io.a10vm.runtime.RomCatalog
import io.a10vm.runtime.VmConnection
import io.a10vm.runtime.VmDevices
import io.a10vm.runtime.VmService
import io.a10vm.ui.boot.BootFault
import io.a10vm.ui.boot.BootHost
import io.a10vm.ui.boot.BootLauncher
import me.kanadex.geometryos.boot.BootGateSequence
import io.a10vm.ui.boot.BootRestartBus
import io.a10vm.ui.boot.BootViewModel
import io.a10vm.ui.boot.BootUiState
import io.a10vm.ui.boot.BootTexts
import io.a10vm.ui.boot.rememberBootViewModel
import io.a10vm.ui.icons.AppMiuixIcons
import io.a10vm.ui.settings.AppPreferences
import io.a10vm.ui.theme.VerseBoxTheme
import io.a10vm.ui.verse.VmRuntime
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Surface as MiuixSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一台虚拟机自己的窗口（即 `docs/ui-rework/09` 说的「第一屏」宿主）。
 *
 * 它只做五件事：
 * 1. 把客体的原生 Surface 挂进来，**并且不主动断开**——异常时保留最后一帧（`09/03` §1 B 类）；
 * 2. 把 [VmConnection] 报上来的运行态交给 `BootViewModel`（**页面不解释状态**）；
 * 3. 第一屏只在「还没出过首帧」或「正在关闭」时占屏（`09/03` §5 第 1 条）；
 * 4. 关闭完成后自动回到管理页，而不是把窗口停在那里（`09/03` §4）；
 * 5. 返回管理页时保留本 Activity 与独立任务卡片，供下次进入复用。
 *
 * 主题、组件、间距全部跟随 [VerseBoxTheme]；悬浮的「管理」胶囊按分支选库组件。
 * 画面外的底色固定黑色：客体分辨率窄于屏幕时留边必须是黑，不能跟着浅色主题变白
 * （浅色模式下白边会被误认为「画面挂了」）。
 */
class FullscreenActivity : ComponentActivity() {
    private val preferences by lazy { AppPreferences.get(this) }

    /** 运行态的唯一来源（StateFlow：ViewModel 需要 collect 它，而不是在组合里读 Compose 状态）。 */
    private val runtimeFlow = MutableStateFlow(VmRuntime())

    private var deviceId = ""
    private var deviceName = "虚拟机"
    private var romLine = ""
    private var display: NativeSurfaceView? = null

    /** 组合里创建的第一屏逻辑；`onStart` 用它把"重新进入"告诉状态层。 */
    private var bootViewModel: BootViewModel? = null

    private val vm by lazy {
        VmConnection(
            context = this,
            onState = { data ->
                if (data.getString(VmService.EXTRA_INSTANCE) != deviceId) return@VmConnection
                runtimeFlow.value = VmRuntime(
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
                // 只在「有窗口」时 attach；**不主动 detach**。
                // 旧实现每次状态回来都 connect(null|window)，客体一崩就被摘掉 → 最后一帧丢失 →
                // 只剩启动页，正是「框架退出仍被启动页遮挡」的一半原因。摘除只发生在 onStop/断开。
                if (data.getBoolean("windowReady")) {
                    display?.connect(VmService.window(deviceId))
                }
            },
            onLogs = {},
            onDisconnected = {
                display?.connect(null)
                // 断开连接 ≠ 虚拟机崩了：只标记断开，别伪造 ERROR。
                runtimeFlow.value = runtimeFlow.value.copy(disconnected = true)
            },
        )
    }

    private val startGuest: () -> Unit = { vm.perform(VmService.START, null, deviceId) }

    private val navigateAfterGate: (BootGateSequence.Result) -> Unit = { result ->
        when (result) {
            BootGateSequence.Result.OpenVersions -> BootLauncher.openRomVersion(this, deviceId)
            BootGateSequence.Result.OpenRomList -> BootLauncher.openRomList(this)
            is BootGateSequence.Result.Repair -> BootLauncher.openRomRepair(this, result.key, deviceId, result.rowId)
            else -> error("Unexpected navigation result: $result")
        }
        moveTaskToBack(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(VmService.EXTRA_INSTANCE).orEmpty()
        if (deviceId.isEmpty()) {
            // C1：入口没带参数。给一张「打不开这台虚拟机」的告知框再退出，而不是白屏消失
            // （`09/03` §2 C1：判据是「不崩」，动作是「返回首页」）。
            showNoInstance()
            return
        }
        val device = VmDevices(this).find(deviceId)
        val romId = intent.getStringExtra("romId") ?: device?.romId
        // 带上这台实例的内容指纹：同版本两份内容时，只按 romKey 解析会拿到另一份的 romRoot。
        val rom = me.kanadex.geometryos.rom.RomRepository.get(this)
            .resolve(romId, device?.romPayload.orEmpty())
            ?: RomCatalog(this).find(romId)
        deviceName = intent.getStringExtra("name") ?: device?.name ?: deviceName
        if (intent.data != Uri.Builder().scheme("versebox").authority("vm").appendPath(deviceId).build()) {
            finishAndRemoveTask()
            return
        }
        romLine = buildString {
            val name = rom?.name ?: intent.getStringExtra("romName") ?: "Android"
            append(name)
            // 包名本身常常就叫 "Android 15"，再拼一次版本号会变成 "Android 15 · Android 15 · API 35"。
            rom?.let { spec ->
                if (!spec.androidVersion.isBlank() && spec.androidVersion !in name) {
                    append(" · Android ").append(spec.androidVersion)
                }
                append(" · API ").append(spec.apiLevel)
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching {
            window.attributes = window.attributes.apply {
                preferredRefreshRate = io.a10vm.nativedisplay.WindowBridge.REFRESH_HZ.toFloat()
            }
        }
        setContent {
            BackHandler { returnToManager() }
            val theme by preferences.theme.collectAsStateWithLifecycle()
            VerseBoxTheme(theme) {
                val viewModel = rememberBootViewModel(
                    vmId = deviceId,
                    vmName = deviceName,
                    romLine = romLine,
                    runtime = runtimeFlow,
                    startGuest = startGuest,
                    navigate = navigateAfterGate,
                )
                bootViewModel = viewModel
                val bootState by viewModel.state.collectAsStateWithLifecycle()

                LaunchedEffect(viewModel) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        BootRestartBus.vmIds.collect {
                            if (BootRestartBus.consume(deviceId)) viewModel.requestStart()
                        }
                    }
                }

                // 停止完成 → 回到管理页，并**彻底移除这个窗口的任务卡片**：
                // 用户的心智是"我停了它"，留一张后台卡片等于留一个幽灵（2026-11-09 反馈）。
                LaunchedEffect(bootState.exitRequested) {
                    if (bootState.exitRequested) finishAndRemoveTask()
                }

                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { context ->
                            NativeSurfaceView(context).also {
                                it.contentDescription = deviceName + " 桌面"
                                display = it
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    BootHost(
                        useMiuix = theme.useMiuix,
                        state = bootState,
                        onAction = viewModel::dispatch,
                    )
                    ManagementPillLayer(useMiuix = theme.useMiuix, onClick = { returnToManager() })
                }
            }
        }
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        if (deviceId.isNotEmpty()) {
            // 用户（重新）进入这个窗口：走干净的启动路径 —— 第一屏、进度、日志重新来，
            // 上一次的异常以对话框形式摆出来（09/03 §7）。
            bootViewModel?.onEnterFromOutside()
            vm.bind()
        }
    }

    override fun onStop() {
        bootViewModel?.cancelStart()
        if (deviceId.isNotEmpty()) vm.unbind()
        display?.connect(null)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** C1 的落点：只有一张告知框，没有第一屏（没有实例就没有进度可看）。 */
    private fun showNoInstance() {
        setContent {
            val theme by preferences.theme.collectAsStateWithLifecycle()
            VerseBoxTheme(theme) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    BootHost(
                        useMiuix = theme.useMiuix,
                        state = BootUiState(hadFirstFrame = true, fault = BootFault.NoInstance),
                        onAction = { finish() },
                    )
                }
            }
        }
    }

    /**
     * 返回管理页：**把本任务压到后面**，不结束它。
     *
     * 两条硬要求同时满足：
     * 1. 任务卡片必须保留（运行中的虚拟机只因为"点了个返回"就丢卡片是不可接受的）；
     * 2. 转场方向必须是"退出"：`moveTaskToBack` 是"离开这个任务"的标准动作，
     *    系统把上一个任务（主壳）带回前台，效果是虚拟机页退出去，而不是"又进入了 app"。
     *
     * 以前用 `startActivity(MainActivity, CLEAR_TOP)`（进入方向）和 `finish()`（丢卡片）都不对。
     * 卡片只在**关机完成**时移除（`finishAndRemoveTask` / `VmLauncher.removeTask`）。
     */
    private fun returnToManager() {
        // 必须把**主壳那个任务**提到前台，而不是 moveTaskToBack：
        //   moveTaskToBack 是把本任务压到**整个栈的最底**，于是露出来的是桌面（launcher），
        //   不是"上一层页面"。真机现象就是"返回直接回桌面"（2026-11-09 反馈）。
        // 同时本窗口**不 finish**：任务卡片要保留（运行中的虚拟机只因为点了个返回就丢卡片是不可接受的）。
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
        )
        // 转场方向：只让**本页**向右滑走（等于"退出"），主壳不再播"进入"动画。
        // 以前两个方向都用系统默认的进入动画，所以看起来像"又从虚拟机跳进了 app"。
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.slide_out_right)
    }

    private fun hideSystemBars() = SystemUi.fullscreen(window)
}

/**
 * 右上角的「管理」胶囊：外观保持现状（同一枚 [AppIcons.Tune]、48dp 圆形容器），
 * 只恢复拖动（`09/01` §6），并且**容器按分支选组件库**（`09/01` §7 第 4 条不许混用）：
 *
 * - Miuix：miuix 的 [MiuixSurface]（`shape/color/contentColor/onClick` 都是库的参数）+ miuix 的 Icon；
 * - Material 3：[FilledTonalIconButton]（M3 的 tonal 图标按钮）。
 *
 * 位置用 `rememberSaveable` 存成两个 Float，转屏/重建都保留；拖动范围钳制在安全区内，松手不吸边；
 * 位移小于触控阈值时仍按点击处理（[detectDragGestures] 自带 slop，点击照常落到按钮上）。
 */
@Composable
private fun ManagementPillLayer(useMiuix: Boolean, onClick: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val limitX = with(density) { (maxWidth - 48.dp - 16.dp).toPx() }
        val limitY = with(density) { (maxHeight - 48.dp - 16.dp).toPx() }
        var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
        var offsetY by rememberSaveable { mutableFloatStateOf(0f) }

        val drag = Modifier
            .align(Alignment.TopEnd)
            .padding(16.dp)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(48.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, gesture ->
                    change.consume()
                    offsetX = (offsetX + gesture.x).coerceIn(-limitX, 0f)
                    offsetY = (offsetY + gesture.y).coerceIn(0f, limitY)
                }
            }

        if (useMiuix) {
            MiuixSurface(
                onClick = onClick,
                modifier = drag,
                shape = CircleShape,
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                contentColor = MiuixTheme.colorScheme.onSurface,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    MiuixIcon(
                        imageVector = AppMiuixIcons.Tune,
                        contentDescription = BootTexts.MANAGE,
                    )
                }
            }
        } else {
            FilledTonalIconButton(onClick = onClick, modifier = drag) {
                Icon(imageVector = Icons.Rounded.Tune, contentDescription = BootTexts.MANAGE)
            }
        }
    }
}