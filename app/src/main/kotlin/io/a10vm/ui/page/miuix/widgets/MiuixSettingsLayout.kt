// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.a10vm.ui.icons.AppMiuixIcons
import io.a10vm.ui.navigation.LocalNavigator
import io.a10vm.ui.theme.getMiuixAppBarColor
import io.a10vm.ui.theme.rememberMiuixBlurBackdrop
import io.a10vm.ui.theme.verseBoxMiuixBlurEffect
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTitleDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * # Miuix 设置类页面的唯一版式来源
 *
 * 为什么以前每个页面都要"自己造轮子"：miuix 的间距不是没给，而是全部内置在组件内部
 * （0.9.4-292d8ec6 的 sources 实测）：
 *
 * | 组件 | 内置 insideMargin | 含义 |
 * |---|---|---|
 * | `SmallTitle` | `PaddingValues(28.dp, 8.dp)` | 左右 28 = 卡片外边距 12 + 内容内边距 16 |
 * | `Card` | `PaddingValues(0.dp)` | 卡片自己不占内边距 |
 * | `BasicComponent` | `PaddingValues(16.dp)` | 卡片文字左右 16 |
 *
 * 于是"分类小标题的文字左边缘 == 卡片文字左边缘"这条 miuix 规则，
 * 只在 **列表横向不加 padding + 卡片自己加 12dp 外边距** 时成立：28 = 12 + 16。
 *
 * 一旦给 LazyColumn 的 contentPadding 再加 12dp，标题就变成 40dp，比卡片文字多 12dp；
 * 一旦再给 Card 加 12dp，卡片就变成 24dp —— 这就是各页面左边距各不相同、越调越乱的原因。
 *
 * 本文件把这些数字收敛成一份常量 + 三个 API，业务页面里不再出现任何手写 padding：
 *
 * - [MiuixSettingsScaffold]：页面骨架（TopAppBar + 唯一的 LazyColumn + 12dp 纵向网格）
 * - [miuixSection]：一个分类（可选 SmallTitle + 一张 Card）
 * - [miuixCardInset]：页面里的特殊卡片（如首页状态卡）需要的外边距
 */
object MiuixListMetrics {
    /** 卡片相对安全区边缘的左右外边距。改这里 = 全应用所有卡片一起改。 */
    val CardInset = 12.dp

    /** 列表四周的基础间距，同时也是列表的纵向网格间距（任意两个 item 之间都是它）。 */
    val SectionGap = 12.dp
}

/**
 * 分类标题的水平内边距沿用 miuix 原生值（28dp = 12 + 16），
 * 纵向置 0 交给 [MiuixSettingsScaffold] 的 12dp 网格统一排，
 * 这样"卡片↔标题↔卡片"的间距永远相等。
 */
private val TitleInsideMargin = PaddingValues(
    horizontal = SmallTitleDefaults.InsideMargin.calculateStartPadding(LayoutDirection.Ltr),
    vertical = 0.dp,
)

/**
 * 设置类页面的统一骨架。替代"每个页面自己拼 Scaffold + TopAppBar + LazyColumn + contentPadding"。
 *
 * @param title AppBar 标题。
 * @param enableBlur 是否启用 Miuix 模糊（沿用各页面原来的开关）。
 * @param outerPadding 外层（Pager / 上级 Scaffold）给的 padding，只取其中的底部值。
 * @param showBackButton 二级页显示返回键。
 * @param bottomContentPadding 列表底部在 outerPadding 之上额外留的空（有悬浮底栏的页面传大一点，如 88.dp）。
 * @param floatingActionButton / @param snackbarHost 透传给 miuix Scaffold。
 * @param closeButton 左上角用「关闭 ×」而不是返回箭头（表单页用，配合 unsaved-changes 拦截）。
 * @param imePadding 表单页把 Scaffold 垫到键盘上方（`Modifier.imePadding()`）。
 * @param content 列表内容，用 [miuixSection] / [miuixCardInset] 写。
 */
@Composable
fun MiuixSettingsScaffold(
    title: String,
    enableBlur: Boolean = true,
    outerPadding: PaddingValues = PaddingValues(),
    showBackButton: Boolean = false,
    closeButton: Boolean = false,
    bottomContentPadding: Dp = MiuixListMetrics.SectionGap,
    imePadding: Boolean = false,
    topBarActions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    /**
     * 列表状态。需要「滚到某一行」的页面（记录定位 09/05、ROM 定位 09/04）自己持有并传进来；
     * 其余页面用默认值即可。**不要在页面里另建一个 LazyColumn**——那会绕开骨架的内距规则。
     */
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberMiuixBlurBackdrop(enableBlur)
    val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        modifier = if (imePadding) Modifier.imePadding() else Modifier,
        topBar = {
            TopAppBar(
                modifier = Modifier.verseBoxMiuixBlurEffect(backdrop),
                color = backdrop.getMiuixAppBarColor(),
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = when {
                    closeButton -> {
                        { MiuixBackButton(icon = AppMiuixIcons.Close, onClick = { navigator.pop() }) }
                    }

                    showBackButton -> {
                        { MiuixBackButton(onClick = { navigator.pop() }) }
                    }

                    else -> {
                        {}
                    }
                },
                actions = topBarActions,
            )
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            // 横向只留安全区，绝不再 +12：12dp 由卡片自己承担（见 MiuixListMetrics.CardInset）。
            contentPadding = PaddingValues(
                start = horizontalInsets.calculateStartPadding(layoutDirection),
                top = paddingValues.calculateTopPadding() + MiuixListMetrics.SectionGap,
                end = horizontalInsets.calculateEndPadding(layoutDirection),
                // 底部内边距与主题设置页保持同一条规则：
                //  - Pager 里的页面传了 outerPadding（外层底栏高度已经含导航栏），直接用，别重复叠加；
                //  - 独立路由页没有外层底栏，用本 Scaffold 的 systemBars 底部内边距，
                //    否则最后一行会被导航栏盖住（这就是之前子页面被遮的原因）。
                bottom = (
                    outerPadding.calculateBottomPadding().takeIf { it > 0.dp }
                        ?: paddingValues.calculateBottomPadding()
                    ) + bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(MiuixListMetrics.SectionGap),
            overscrollEffect = null,
            content = content,
        )
    }
}

/**
 * 一个"分类"：可选的 [SmallTitle] + 一张卡片，间距由骨架的网格负责。
 *
 * ```kotlin
 * miuixSection("运行环境") {
 *     BasicComponent(title = "机型", summary = Build.MODEL)
 *     BasicComponent(title = "系统", summary = "Android 15")
 * }
 * ```
 */
fun LazyListScope.miuixSection(
    title: String? = null,
    cardColors: CardColors? = null,
    cardModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (title != null) {
        item { SmallTitle(text = title, insideMargin = TitleInsideMargin) }
    }
    item {
        val colors = cardColors ?: CardDefaults.defaultColors()
        Card(
            modifier = Modifier
                .padding(horizontal = MiuixListMetrics.CardInset)
                .then(cardModifier)
                .fillMaxWidth(),
            colors = colors,
            content = content,
        )
    }
}

/**
 * 页面里不走 [miuixSection] 的自定义卡片（首页状态卡、数字卡行等）必须套上它，
 * 才能和 [miuixSection] 里的卡片左右对齐。
 */
fun Modifier.miuixCardInset(): Modifier = this.padding(horizontal = MiuixListMetrics.CardInset)
