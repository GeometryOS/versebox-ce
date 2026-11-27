// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.a10vm.domain.settings.model.preferences.PredictiveBackAnimation
import io.a10vm.domain.settings.model.preferences.ThemeState
import io.a10vm.ui.animation.predictiveback.verseBoxNavTransition
import io.a10vm.ui.boot.BootLauncher
import io.a10vm.ui.boot.BootRestartBus
import io.a10vm.ui.vm.openDesktop
import io.a10vm.ui.verse.LocalAppState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.kanadex.geometryos.backup.BackupAction
import me.kanadex.geometryos.backup.BackupViewModel
import me.kanadex.geometryos.backup.LocalBackup
import io.a10vm.ui.page.main.settings.preferred.about.AboutPage
import io.a10vm.ui.page.main.settings.preferred.about.OpenSourceLicensePage
import io.a10vm.ui.page.main.settings.preferred.theme.ThemeSettingsPage
import io.a10vm.ui.page.miuix.settings.preferred.about.MiuixAboutPage
import io.a10vm.ui.page.miuix.settings.preferred.about.ossLicensePage.MiuixOpenSourceLicensePage
import io.a10vm.ui.page.miuix.settings.preferred.theme.MiuixThemeSettingsPage
import io.a10vm.ui.processlimit.ProcessLimitAlertHost
import io.a10vm.ui.page.main.settings.history.RecordDetailPage
import io.a10vm.ui.page.main.settings.preferred.LabPage
import io.a10vm.ui.page.main.settings.preferred.NetworkPage
import io.a10vm.ui.page.main.vm.CreateVmPage
import io.a10vm.ui.page.main.vm.ProcessLimitPage
import io.a10vm.ui.page.main.vm.VmManagePage
import io.a10vm.ui.page.miuix.settings.history.MiuixRecordDetailPage
import io.a10vm.ui.page.miuix.settings.preferred.MiuixLabPage
import io.a10vm.ui.page.miuix.settings.preferred.MiuixNetworkPage
import io.a10vm.ui.page.miuix.vm.MiuixCreateVmPage
import io.a10vm.ui.page.miuix.vm.MiuixProcessLimitPage
import io.a10vm.ui.page.miuix.vm.MiuixVmManagePage
import io.a10vm.ui.util.rememberDeviceCornerRadius
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 导航宿主，逐行照 InstallerX 的 VerseBoxNavContainer：
 *
 * - 返回栈是 miuix-nav 的 NavBackStack（可保存/恢复），Miuix 与 M3 两套页面共用；
 * - 转场是五套自绘 NavTransition（AOSP / Miuix / 缩放 / 经典 / 关闭），按主题设置里的
 *   「预测性返回动画 + 退出方向」实时切换；
 * - 圆角裁剪半径取自设备真实屏幕圆角（rememberDeviceCornerRadius），不是写死 32dp；
 * - 每条路由的 swipeDismiss 显式声明，两条「表单类」页面关闭侧滑返回。
 */
@Composable
fun VerseBoxNavContainer(uiState: ThemeState, mainContent: @Composable () -> Unit) {
    val backStack = rememberNavBackStack<Route>(Route.Main)
    val navigator = remember(backStack) { Navigator(backStack) }
    val onBack = remember(navigator) { { navigator.pop() } }
    val useBlur = uiState.useBlur
    val appState = LocalAppState.current
    val context = LocalContext.current

    val backup = BackupViewModel.rememberViewModel()
    val backupState by backup.state.collectAsStateWithLifecycle()
    LaunchedEffect(backupState.createdBackup) {
        if (backupState.createdBackup != null) {
            backup.dispatch(BackupAction.ConsumeCaptured)
            navigator.push(Route.BackupList())
        }
    }
    LaunchedEffect(backupState.pendingRomUri) {
        if (backupState.pendingRomUri != null && navigator.current() != Route.RomList(restoreImport = true)) {
            navigator.push(Route.RomList(restoreImport = true))
        }
    }
    LaunchedEffect(backupState.createdVmId) {
        val id = backupState.createdVmId ?: return@LaunchedEffect
        backup.dispatch(BackupAction.ConsumeCreated)
        navigator.popUntil { it is Route.Main }
        appState.requestVmTab()
        navigator.push(Route.VmManage(id))
    }

    // 第一屏的 C2 / C3 入口（09/04 §2 第 1 步）：先回到 Main 再 push ROM 列表，
    // 这样返回键回到主页，而不是停在半路某个二级页。
    LaunchedEffect(appState.openRomListRequest) {
        if (appState.openRomListRequest <= 0) return@LaunchedEffect
        navigator.popUntil { key -> key is Route.Main }
        navigator.push(Route.RomList())
    }

    // 「要选一个 ROM 版本」：**非破坏性跳转**，不是弹窗。
    //
    // 路径固定为：回 Main → 虚拟机管理页 → ROM 版本管理页。
    // 中间那一页不是多余的：从版本管理页按返回，用户应该回到这台虚拟机的管理页，
    // 而不是被扔回主页（"我点的是这台机器，返回怎么到主页去了"）。
    //
    // 两次 push 之间等一帧：否则同一帧里连推两页，转场没有配对对象，用户看到的是硬切
    // （docs/ui-rework/07 §9.1）。
    val navScope = rememberCoroutineScope()
    LaunchedEffect(appState.vmRomVersionRequest) {
        val id = appState.vmRomVersionRequest ?: return@LaunchedEffect
        appState.consumeVmRomVersionRequest()
        navigator.popUntil { key -> key is Route.Main }
        if (navigator.current() !is Route.VmManage) navigator.push(Route.VmManage(id))
        // 第二步**不能**写在这个 LaunchedEffect 里：consume 会把 key 变成 null，
        // effect 重启并取消这个协程，于是"进版本页"这一步永远不执行（真机：只停在管理页）。
        navScope.launch {
            withFrameNanos { }
            navigator.push(Route.VmRomVersion(id))
        }
    }

    // 记录定位（09/05 §2 第 1 步）：必须先回到 Main 再谈「切到记录 tab」。
    // 少了这一步，从二级页（虚拟机管理 / ROM 列表…）过来时栈顶还是那一页，
    // 记录 tab 虽然在下面切了，用户看到的却像是「直接跳走了」。
    LaunchedEffect(appState.recordsTabRequest) {
        if (appState.recordsTabRequest <= 0) return@LaunchedEffect
        navigator.popUntil { key -> key is Route.Main }
    }

    // 自动修复完成（09/04 §2 第 6 步）：把「重跑一次」交给第一屏，并把它的任务提到前台。
    // 启动窗口不重建（同一个 Activity 实例、同一段日志接在后面）。
    LaunchedEffect(appState.vmRestartRequest) {
        val id = appState.consumeVmRestart() ?: return@LaunchedEffect
        BootRestartBus.request(id)
        val device = appState.device(id) ?: return@LaunchedEffect
        if (!BootLauncher.bringVmToFront(context, device)) {
            openDesktop(context, device, appState.romOf(device))
        }
    }

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalBackup provides backup,
    ) {
        val navCornerRadius = rememberDeviceCornerRadius(defaultRadius = 0.dp)
        val roundAllCorners = uiState.predictiveBackAnimation == PredictiveBackAnimation.AOSP ||
            uiState.predictiveBackAnimation == PredictiveBackAnimation.Scale ||
            uiState.predictiveBackAnimation == PredictiveBackAnimation.Classic
        val backdropColor = if (uiState.useMiuix) {
            MiuixTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
        val effects = remember(navCornerRadius, roundAllCorners, backdropColor) {
            NavDisplayEffects(
                enableCornerClip = true,
                cornerClipRadius = if (roundAllCorners && navCornerRadius <= 0.dp) 32.dp else navCornerRadius,
                cornerClipMode = if (roundAllCorners) NavCornerClipMode.All else NavCornerClipMode.Leading,
                dimAmount = 0.5f,
                backdropColor = backdropColor,
                blockInputDuringTransition = false,
            )
        }
        val transition = remember(uiState.predictiveBackAnimation, uiState.predictiveBackExitDirection) {
            verseBoxNavTransition(
                animation = uiState.predictiveBackAnimation,
                exitDirection = uiState.predictiveBackExitDirection,
            )
        }
        val swipeBackDirection = when (LocalLayoutDirection.current) {
            LayoutDirection.Rtl -> NavSwipeDirection.RightToLeft
            LayoutDirection.Ltr -> NavSwipeDirection.LeftToRight
        }
        val interceptPredictiveBack =
            uiState.predictiveBackAnimation == PredictiveBackAnimation.None && backStack.size > 1

        NavDisplay(
            backStack = backStack,
            onBack = onBack,
            transition = transition,
            effects = effects,
        ) {
            entry<Route.Main> {
                VerseBoxNavEntry(interceptPredictiveBack, onBack) { mainContent() }
            }
            entry<Route.Theme>(swipeDismiss = swipeBackDirection) {
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) MiuixThemeSettingsPage() else ThemeSettingsPage()
                }
            }
            entry<Route.About>(swipeDismiss = swipeBackDirection) {
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) MiuixAboutPage(useBlur) else AboutPage(useBlur)
                }
            }
            entry<Route.OpenSourceLicense>(swipeDismiss = swipeBackDirection) {
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) MiuixOpenSourceLicensePage(useBlur) else OpenSourceLicensePage(useBlur)
                }
            }
            entry<Route.Network>(swipeDismiss = swipeBackDirection) {
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) MiuixNetworkPage(useBlur) else NetworkPage(useBlur)
                }
            }
            entry<Route.Lab>(swipeDismiss = swipeBackDirection) {
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) MiuixLabPage(useBlur) else LabPage(useBlur)
                }
            }
            entry<Route.ProcessLimit>(swipeDismiss = swipeBackDirection) {
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) MiuixProcessLimitPage(useBlur) else ProcessLimitPage(useBlur)
                }
            }
            // 表单类页面：编辑中误触侧滑会把输入丢掉，所以关掉侧滑返回（InstallerX 对 EditConfig 同样处理）。
            entry<Route.CreateVm>(swipeDismiss = NavSwipeDirection.None) {
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) MiuixCreateVmPage(useBlur) else CreateVmPage(useBlur)
                }
            }
            entry<Route.VmManage>(swipeDismiss = swipeBackDirection) { key ->
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) MiuixVmManagePage(key.id, useBlur) else VmManagePage(key.id, useBlur)
                }
            }
            // 分辨率设置页：列表型单选页，保留侧滑返回（docs/display-settings/02 §2）。
            entry<Route.VmDisplay>(swipeDismiss = swipeBackDirection) { key ->
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) {
                        io.a10vm.ui.page.miuix.vm.MiuixVmDisplayPage(key.id, useBlur)
                    } else {
                        io.a10vm.ui.page.main.vm.VmDisplayPage(key.id, useBlur)
                    }
                }
            }
            // ROM 列表：管理（主页进来）与选择（新建页进来）是同一个页面的两种用途，保留侧滑返回。
            entry<Route.BackupList>(swipeDismiss = swipeBackDirection) { key ->
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) me.kanadex.geometryos.backup.ui.miuix.MiuixBackupPage(useBlur, key.pick, key.detailId)
                    else me.kanadex.geometryos.backup.ui.main.BackupPage(useBlur, key.pick, key.detailId)
                }
            }
            entry<Route.RomList>(swipeDismiss = swipeBackDirection) { key ->
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    val mode = if (key.pickForCreate) {
                        me.kanadex.geometryos.rom.RomListMode.Pick
                    } else {
                        me.kanadex.geometryos.rom.RomListMode.Manage
                    }
                    if (uiState.useMiuix) {
                        io.a10vm.ui.page.miuix.vm.MiuixRomListPage(useBlur, mode, key.restoreImport)
                    } else {
                        io.a10vm.ui.page.main.vm.RomListPage(useBlur, mode, key.restoreImport)
                    }
                }
            }
            entry<Route.RomDetail>(swipeDismiss = swipeBackDirection) { key ->
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    val mode = if (key.pickForCreate) {
                        me.kanadex.geometryos.rom.RomListMode.Pick
                    } else {
                        me.kanadex.geometryos.rom.RomListMode.Manage
                    }
                    if (uiState.useMiuix) {
                        io.a10vm.ui.page.miuix.vm.MiuixRomDetailPage(key.rowId, mode, useBlur, key.backupId)
                    } else {
                        io.a10vm.ui.page.main.vm.RomDetailPage(key.rowId, mode, useBlur, key.backupId)
                    }
                }
            }
            // ROM 版本管理：列表型单选页，保留侧滑返回（docs/rom-package/14-版本管理/01 §2）。
            entry<Route.VmRomVersion>(swipeDismiss = swipeBackDirection) { key ->
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) {
                        io.a10vm.ui.page.miuix.vm.MiuixRomVersionPage(key.id, useBlur)
                    } else {
                        io.a10vm.ui.page.main.vm.RomVersionPage(key.id, useBlur)
                    }
                }
            }
            entry<Route.RecordLog>(swipeDismiss = swipeBackDirection) { key ->
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) {
                        io.a10vm.ui.page.miuix.settings.history.MiuixRecordLogPage(key.id, key.path, useBlur)
                    } else {
                        io.a10vm.ui.page.main.settings.history.RecordLogPage(key.id, key.path, useBlur)
                    }
                }
            }
            entry<Route.RecordDetail>(swipeDismiss = swipeBackDirection) { key ->
                VerseBoxNavEntry(interceptPredictiveBack, onBack) {
                    if (uiState.useMiuix) MiuixRecordDetailPage(key.id, useBlur) else RecordDetailPage(key.id, useBlur)
                }
            }
        }

        // 子进程限制提示：宿主放在导航层（Route.Main 与所有二级页之上），
        // 所以启动时的自动检测命中异常时，不管当前在哪个 tab 都能弹出来。
        ProcessLimitAlertHost(useMiuix = uiState.useMiuix)
        if (uiState.useMiuix) {
            io.a10vm.ui.page.miuix.widgets.MiuixBlockingLoadingDialog(
                visible = backupState.working, text = backupState.progressText,
            )
        } else {
            io.a10vm.ui.page.main.widget.setting.BlockingLoadingIndicator(
                visible = backupState.working, text = backupState.progressText, backdrop = null,
            )
        }
        LaunchedEffect(backupState.error, backupState.message) {
            val text = backupState.error ?: backupState.message ?: return@LaunchedEffect
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
            backup.dispatch(BackupAction.DismissError)
            backup.dispatch(BackupAction.ConsumeMessage)
        }

    }
}

@Composable
private fun VerseBoxNavEntry(
    interceptPredictiveBack: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = interceptPredictiveBack,
        onBackCompleted = onBack,
    )
    content()
}