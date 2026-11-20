// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.vm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppMiuixIcons
import io.a10vm.ui.page.miuix.widgets.MiuixBackButton
import io.a10vm.ui.page.miuix.widgets.MiuixBadge
import io.a10vm.ui.page.miuix.widgets.MiuixSettingsScaffold
import io.a10vm.ui.page.miuix.widgets.MiuixStatusCard
import io.a10vm.ui.page.miuix.widgets.MiuixTipCard
import io.a10vm.ui.page.miuix.widgets.miuixCardInset
import io.a10vm.ui.page.miuix.widgets.miuixSection
import io.a10vm.ui.processlimit.ProcessLimitSupport
import io.a10vm.ui.theme.VerseBoxTheme
import io.a10vm.ui.vm.ProcessLimitAction
import io.a10vm.ui.vm.rememberProcessLimitViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * 安卓 12 进程限制（Miuix 分支）。
 *
 * 与 Material 3 分支共用同一个 [io.a10vm.ui.vm.ProcessLimitViewModel] 与同一份文案
 * （[ProcessLimitSupport]），这里只有组件选型：
 *
 * 1. [MiuixStatusCard]：标题 + 说明 + 操作文字，整卡可点 = 重新检测；
 * 2. [MiuixTipCard]：为什么会被清理 + 结果时效；
 * 3. [miuixSection] 里「打开开发者选项」与三档按机型方案，点开是 [WindowBottomSheet]。
 *
 * 页面里没有 `when` 判断、没有 `Build.VERSION`、没有线程操作；
 * 左右 12dp 与纵向 12dp 网格全部来自 [MiuixSettingsScaffold] + [miuixSection]。
 */
@Composable
fun MiuixProcessLimitPage(useBlur: Boolean) {
    val viewModel = rememberProcessLimitViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var selectedPlan by remember { mutableStateOf<ProcessLimitSupport.Plan?>(null) }
    var showPlanSheet by remember { mutableStateOf(false) }

    // 已解除：与主页「未见子进程限制」那张卡同一套颜色 —— 动态取 secondaryContainer，
    // 非动态深色 0xFF1A3825 / 浅色 0xFFDFFAE4；未解除才用 error 容器。
    val statusContainer = when {
        state.ok && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        state.ok && VerseBoxTheme.isDark -> Color(0xFF1A3825)
        state.ok -> Color(0xFFDFFAE4)
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.errorContainer
        VerseBoxTheme.isDark -> Color(0xFF381A1A)
        else -> Color(0xFFFAEEEE)
    }
    val statusContent = when {
        state.ok && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.onSecondaryContainer
        state.ok -> MiuixTheme.colorScheme.onSurface
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.onErrorContainer
        else -> MiuixTheme.colorScheme.onSurface
    }
    // 背景大图标的绿色与主页卡同一档：动态取 primary，非动态 0xFF36D167。
    // 卡片内部（MiuixStatusCard）会再给这个 tint 套 80% 透明度，两套卡的行为一致。
    val statusIconTint = when {
        state.ok && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.primary
        state.ok -> Color(0xFF36D167)
        else -> MiuixTheme.colorScheme.error
    }

    // 进页面自动检测一次；等转场动画结束再发 Action，避免 fork 和首帧抢 CPU。
    // 是否真的跑由 ViewModel 判断（已有结论就不重复 fork）。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        viewModel.dispatch(ProcessLimitAction.Check)
    }

    MiuixSettingsScaffold(
        title = "安卓 12 进程限制",
        enableBlur = useBlur,
        showBackButton = true,
    ) {
        // 1. 状态卡（整卡可点 = 重新检测）
        item {
            MiuixStatusCard(
                modifier = Modifier.miuixCardInset(),
                title = state.title,
                description = state.detail + "\n\n" + ProcessLimitSupport.WHY,
                containerColor = statusContainer,
                contentColor = statusContent,
                iconTint = statusIconTint,
                actionText = state.actionText,
                onClick = { viewModel.dispatch(ProcessLimitAction.Recheck) },
            )
        }

        // 2. 后果与时效
        item {
            MiuixTipCard(
                text = ProcessLimitSupport.CONSEQUENCE + "\n" + ProcessLimitSupport.TIP +
                    "\n" + ProcessLimitSupport.FOOTNOTE,
                modifier = Modifier.miuixCardInset(),
            )
        }

        // 3. 系统入口
        miuixSection {
            BasicComponent(
                title = "打开开发者选项",
                summary = "去系统设置里找「停止限制子进程」",
                onClick = { ProcessLimitSupport.openDeveloperOptions(context) },
            )
        }

        // 4. 分机型方案
        miuixSection("按机型选方案") {
            ProcessLimitSupport.plans().forEach { plan ->
                BasicComponent(
                    title = plan.title,
                    summary = plan.summary,
                    endActions = { MiuixBadge(text = plan.badge) },
                    onClick = {
                        selectedPlan = plan
                        showPlanSheet = true
                    },
                )
            }
        }
    }

    // 方案详情：标题 + 左上关闭 + surfaceContainerHigh 底 + 24dp 横向内距，
    // 内容自己让开系统导航栏（见 [PlanDetailContent] 末尾那条 Spacer）。
    // 关闭动画期间内容必须还在，所以记录用 selectedPlan、显隐用 showPlanSheet：两个状态分开。
    selectedPlan?.let { plan ->
        WindowBottomSheet(
            show = showPlanSheet,
            backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
            startAction = {
                val dismissState = LocalDismissState.current
                MiuixBackButton(
                    icon = AppMiuixIcons.Close,
                    iconTint = MiuixTheme.colorScheme.onSurface,
                    contentDescription = "关闭",
                    onClick = { dismissState?.invoke() ?: run { showPlanSheet = false } },
                )
            },
            title = plan.title,
            insideMargin = DpSize(24.dp, 0.dp),
            onDismissRequest = { showPlanSheet = false },
            onDismissFinished = { selectedPlan = null },
        ) {
            PlanDetailContent(
                plan = plan,
                onOpenLink = {
                    if (plan.developerOptions) {
                        ProcessLimitSupport.openDeveloperOptions(context)
                    } else {
                        plan.link?.let { link -> runCatching { uriHandler.openUri(link) } }
                    }
                },
            )
        }
    }
}

/**
 * 方案详情的内容：说明、编号步骤、末尾一个可点链接。
 *
 * - 版式与记录详情弹窗同一套：12dp 纵向网格、文字直接落在弹窗表面上（不套卡片）；
 * - 链接只写标签本身（`styles = null`，由 miuix 的 Text 套 primary + 下划线），
 *   与「系统在限制子进程」那张对话框里的链接同一套样式，不再放完整 URL 或独立按钮；
 * - 末尾那条 Spacer 让开系统导航栏，否则最后一行会被导航栏压住。
 */
@Composable
private fun PlanDetailContent(
    plan: ProcessLimitSupport.Plan,
    onOpenLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = plan.summary,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontWeight = FontWeight.Medium,
            )
        }
        plan.steps.forEachIndexed { index, step ->
            item {
                Text(
                    text = (index + 1).toString() + ". " + step,
                    style = MiuixTheme.textStyles.main,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
        val label = plan.linkLabel
        if (label != null) {
            item {
                Text(
                    text = buildAnnotatedString {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "process_limit_plan_link",
                                styles = null,
                                linkInteractionListener = { onOpenLink() },
                            ),
                        ) {
                            append(label)
                        }
                    },
                    style = MiuixTheme.textStyles.main,
                )
            }
        }
        item {
            Spacer(
                modifier = Modifier.padding(
                    bottom = 24.dp +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                        WindowInsets.captionBar.asPaddingValues().calculateBottomPadding(),
                ),
            )
        }
    }
}
