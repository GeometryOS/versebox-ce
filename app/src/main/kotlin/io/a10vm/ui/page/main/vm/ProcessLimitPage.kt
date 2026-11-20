// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.vm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.a10vm.ui.icons.AppIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.page.main.widget.card.InfoTipCard
import io.a10vm.ui.page.main.widget.card.StatusCard
import io.a10vm.ui.page.main.widget.setting.NavigationItemWidget
import io.a10vm.ui.page.main.widget.setting.SegmentedColumn
import io.a10vm.ui.processlimit.ProcessLimitSupport
import io.a10vm.ui.theme.getMaterial3AppBarColor
import io.a10vm.ui.theme.rememberMaterial3BlurBackdrop
import io.a10vm.ui.theme.verseBoxMaterial3BlurEffect
import io.a10vm.ui.vm.ProcessLimitAction
import io.a10vm.ui.vm.rememberProcessLimitViewModel
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * 安卓 12 进程限制（Material 3 分支）。
 *
 * 与 Miuix 分支**同一份状态**（[io.a10vm.ui.vm.ProcessLimitViewModel]）与同一份内容
 * （[ProcessLimitSupport]），只是组件换成 M3：
 *
 * 1. [StatusCard]：标题 / 实测说明 / 操作文字，整卡可点 = 重新检测；
 * 2. [InfoTipCard]：为什么会被清理 + 检测结果的时效性；
 * 3. [SegmentedColumn] 里的「打开开发者选项」与三档按机型方案；
 * 4. 方案详情用 [ModalBottomSheet]，只放内容，下滑或点外部关闭。
 *
 * 页面里没有一处 `when` 判断、没有 `Build.VERSION`、没有 `running`、没有手动线程——
 * 状态由 ViewModel 给，检测由 runtime 层的独立线程跑。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProcessLimitPage(useBlur: Boolean) {
    val viewModel = rememberProcessLimitViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val backdrop = rememberMaterial3BlurBackdrop(useBlur)
    var sheet by remember { mutableStateOf<ProcessLimitSupport.Plan?>(null) }

    // 进页面自动检测一次；等转场动画结束再发 Action，避免 fork 和首帧抢 CPU。
    // 是否真的跑由 ViewModel 判断（已有结论就不重复 fork）。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        viewModel.dispatch(ProcessLimitAction.Check)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.verseBoxMaterial3BlurEffect(backdrop),
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text("安卓 12 进程限制") },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(AppIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backdrop.getMaterial3AppBarColor(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    scrolledContainerColor = backdrop.getMaterial3AppBarColor(),
                ),
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
        ) {
            // 1. 状态卡（整卡可点 = 重新检测）
            item {
                StatusCard(
                    title = state.title,
                    description = state.detail + "\n\n" + ProcessLimitSupport.WHY,
                    containerColor = if (state.ok) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (state.ok) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    actionText = state.actionText,
                    icon = AppIcons.Android,
                    onClick = { viewModel.dispatch(ProcessLimitAction.Recheck) },
                )
            }

            // 2. 后果与时效
            item {
                InfoTipCard(
                    text = ProcessLimitSupport.CONSEQUENCE + "\n" + ProcessLimitSupport.TIP +
                        "\n" + ProcessLimitSupport.FOOTNOTE,
                )
            }

            // 3. 系统入口
            item {
                SegmentedColumn {
                    item {
                        NavigationItemWidget(
                            icon = AppIcons.Developer,
                            title = "打开开发者选项",
                            description = "去系统设置里找「停止限制子进程」",
                            onClick = { ProcessLimitSupport.openDeveloperOptions(context) },
                        )
                    }
                }
            }

            // 4. 分机型方案
            item {
                SegmentedColumn(title = "按机型选方案") {
                    ProcessLimitSupport.plans().forEach { plan ->
                        item {
                            NavigationItemWidget(
                                icon = AppIcons.Android,
                                title = plan.title,
                                description = plan.summary,
                                onClick = { sheet = plan },
                            )
                        }
                    }
                }
            }
        }
    }

    val plan = sheet
    if (plan != null) {
        // 内容自己让开系统导航栏：contentWindowInsets 归零 + 内容末尾补一条导航栏高度，
        // 否则最后一行会被导航栏压住。
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentWindowInsets = {
                WindowInsets(left = 0.dp, top = 0.dp, right = 0.dp, bottom = 0.dp)
            },
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
 * 链接只写标签本身（样式由这里显式给 primary + 下划线：M3 的 Text 不会给 LinkAnnotation 自动上色），
 * 与「系统在限制子进程」那张对话框里的链接同一套样式，不再放独立按钮或完整 URL。
 */
@Composable
private fun PlanDetailContent(
    plan: ProcessLimitSupport.Plan,
    onOpenLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = plan.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        plan.steps.forEachIndexed { index, step ->
            item {
                Text(
                    text = (index + 1).toString() + ". " + step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
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
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                                linkInteractionListener = { onOpenLink() },
                            ),
                        ) {
                            append(label)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}
