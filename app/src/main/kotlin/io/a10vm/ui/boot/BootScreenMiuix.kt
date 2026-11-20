// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
//
// 本文件是**第一屏的 Miuix 分支**：只 import top.yukonga.miuix.kmp.* 与项目自己的 miuix 薄封装
// （与 page/miuix/** 同一套约束，见 docs/ui-rework/01 §1 判据 2）。
package io.a10vm.ui.boot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import io.a10vm.ui.page.miuix.widgets.MiuixBadge
import io.a10vm.ui.page.miuix.widgets.MiuixListMetrics
import io.a10vm.ui.page.miuix.widgets.miuixToneColors
import me.kanadex.geometryos.rom.RomTone
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 第一屏（Miuix 分支）。
 *
 * 与 Material 3 分支**共用同一个** [BootUiState] 与同一个 [BootLogList]，只有组件选型不同：
 *
 * | 位置 | 组件 | 说明 |
 * | --- | --- | --- |
 * | 页面底色 | `colorScheme.surface` | 与 `MiuixSettingsScaffold` 的 Scaffold 默认色一致 |
 * | 日志面板 | 库的 [Card]（默认色 = `surfaceContainer`） | **与虚拟机列表项同一张卡**：卡片比页面亮一档，形成对比 |
 * | 状态胶囊 | [MiuixBadge] | 白名单里的 24dp 胶囊 |
 * | 品牌字标 | [BootWordmarkFont] | Archivo Black，贴在 logo 与「正在启动 X」之间 |
 * | 「查看运行记录」 | 库的 [Text] + primary + clickable | 低频动作，不做成按钮（`09/01` §5） |
 *
 * 颜色全部取主题角色色；日志正文用 `onSurface`（强对比），只有「次提示」才用 `onSurfaceVariantSummary`。
 */
@Composable
fun MiuixBootScreen(state: BootUiState, onAction: (BootAction) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MiuixListMetrics.CardInset * 2)
                // 上紧下松：底边留白比顶边多两档，避免最下面那行贴着屏幕边缘（真机反馈 2026-11-08）。
                .padding(top = MiuixListMetrics.SectionGap, bottom = MiuixListMetrics.SectionGap * 3),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MiuixListMetrics.SectionGap),
        ) {
            BootOrbit(
                ringColor = scheme.surfaceVariant,
                arcColor = scheme.primary,
                satelliteColor = scheme.tertiaryContainer,
                // 与桌面图标/主页 tab 同一个字形（docs/ui-rework/09/01 §4）。
                // 曾经指着 drawable/ic_launcher —— 那是 A10VM 时代的旧图标，2026-11-15 删掉了。
                logoColor = colorResource(me.kanadex.versebox.R.color.ic_launcher_glyph),
                logo = painterResource(me.kanadex.versebox.R.drawable.ic_tile_icon),
            )
            // 品牌字标：logo 与「正在启动 X」之间，Archivo Black（很粗的无衬线）。
            Text(
                text = "VerseBox",
                fontSize = MiuixTheme.textStyles.title2.fontSize,
                fontFamily = BootWordmarkFont,
                fontWeight = FontWeight.Normal,
                color = scheme.onBackground,
            )
            Text(
                text = state.headline,
                style = MiuixTheme.textStyles.title4,
                color = scheme.onBackground,
            )
            if (state.romLine.isNotBlank()) {
                Text(
                    text = state.romLine,
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onBackgroundVariant,
                )
            }
            val (stageTextColor, stageContainerColor) = miuixToneColors(RomTone.Primary)
            MiuixBadge(
                text = BootTexts.stageOrIdle(state.stageLine),
                textColor = stageTextColor,
                containerColor = stageContainerColor,
            )
            Text(
                text = BootTexts.panelMeta(state.waitSeconds),
                style = MiuixTheme.textStyles.footnote2,
                color = miuixToneColors(state.waitTone).first,
            )

            MiuixBootLogPanel(state = state, modifier = Modifier.fillMaxWidth().weight(1f))

            Text(
                modifier = Modifier
                    .clickable { onAction(BootAction.OpenRecord) }
                    .padding(vertical = MiuixListMetrics.SectionGap),
                text = BootTexts.LINK_OPEN_RECORD,
                style = MiuixTheme.textStyles.body1,
                color = scheme.primary,
            )
        }
    }
}

/** 日志面板：标题行 + 分隔线 + 逐行滚动（`09/02` §1「顶部 ● 实时开机记录 标题行 + 分隔线」）。 */
@Composable
private fun MiuixBootLogPanel(state: BootUiState, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    // 不传 colors → 用库的默认（surfaceContainer），与虚拟机列表项的卡片逐项相同。
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(BootCardPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "● " + BootTexts.PANEL_TITLE,
                    style = MiuixTheme.textStyles.footnote2,
                    color = scheme.primary,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = BootTexts.panelMeta(state.waitSeconds),
                    style = MiuixTheme.textStyles.footnote2,
                    color = scheme.onSurfaceVariantSummary,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = MiuixListMetrics.SectionGap))
            // 滚动 / 裁切 / alpha 全部在 [BootLogTerminalView]（旧 BootTerminalView 的逐行移植）里，
            // 这里只负责外壳（标题行 + 分隔线）与主题角色色。
            BootLogList(
                queue = state.queue,
                resetToken = state.resetToken,
                errorColor = scheme.error,
                normalColor = scheme.onSurface,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = MiuixListMetrics.SectionGap),
            )
        }
    }
}
