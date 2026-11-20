// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
//
// 本文件是**第一屏的 Material 3 分支**：只 import androidx.compose.material3.* 与项目自己的 M3 薄封装
// （与 page/main/** 同一套约束，见 docs/ui-rework/01 §1 判据 2）。
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import io.a10vm.ui.page.main.widget.chip.CapsuleTag
import io.a10vm.ui.page.main.widget.chip.material3ToneColors
// 纵向网格只用全仓那一份常量（docs/ui-rework/03 §3「全仓只允许 MiuixListMetrics 一个 object」）。
// 这里借用的是**数值来源**，不是 miuix 组件，所以不构成两个分支混用。
import io.a10vm.ui.page.miuix.widgets.MiuixListMetrics
import me.kanadex.geometryos.rom.RomTone

/**
 * 第一屏（Material 3 / Expressive 分支）。
 *
 * 与 Miuix 分支**共用同一个** [BootUiState] 与同一个 [BootLogList]，只有组件选型不同：
 *
 * | 位置 | 组件 | 说明 |
 * | --- | --- | --- |
 * | 页面底色 | `colorScheme.surfaceContainer` | 与记录页 Scaffold 的 `surfaceContainer` 一致 |
 * | 日志面板 | [Card] + `surfaceBright` | **与 M3 分段列表项同一档**：比页面亮一档，形成对比 |
 * | 状态胶囊 | [CapsuleTag] | 项目已登记的 M3 胶囊 |
 * | 品牌字标 | [BootWordmarkFont] | Archivo Black，贴在 logo 与「正在启动 X」之间 |
 * | 「查看运行记录」 | [Text] + primary + clickable | 低强调链接（`09/01` §5） |
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Material3BootScreen(state: BootUiState, onAction: (BootAction) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.surfaceContainer),
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
            Text(
                text = "VerseBox",
                fontSize = MaterialTheme.typography.headlineSmall.fontSize,
                fontFamily = BootWordmarkFont,
                fontWeight = FontWeight.Normal,
                color = scheme.onSurface,
            )
            Text(
                text = state.headline,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
            )
            if (state.romLine.isNotBlank()) {
                Text(
                    text = state.romLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            val (stageTextColor, stageContainerColor) = material3ToneColors(RomTone.Primary)
            CapsuleTag(
                text = BootTexts.stageOrIdle(state.stageLine),
                containerColor = stageContainerColor,
                contentColor = stageTextColor,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = BootTexts.panelMeta(state.waitSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = material3ToneColors(state.waitTone).first,
            )

            Material3BootLogPanel(state = state, modifier = Modifier.fillMaxWidth().weight(1f))

            Text(
                modifier = Modifier
                    .clickable { onAction(BootAction.OpenRecord) }
                    .padding(vertical = MiuixListMetrics.SectionGap),
                text = BootTexts.LINK_OPEN_RECORD,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.primary,
            )
        }
    }
}

/** 日志面板：标题行 + 分隔线 + 逐行滚动（`09/02` §1）。与 Miuix 侧逐项同构。 */
@Composable
private fun Material3BootLogPanel(state: BootUiState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        // 与 M3 分段列表项同一档底色：比页面亮一档，形成对比（03 §7 第 3 条）。
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceBright),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(BootCardPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "● " + BootTexts.PANEL_TITLE,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = BootTexts.panelMeta(state.waitSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = MiuixListMetrics.SectionGap))
            // 滚动 / 裁切 / alpha 全部在 [BootLogTerminalView]（旧 BootTerminalView 的逐行移植）里。
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
