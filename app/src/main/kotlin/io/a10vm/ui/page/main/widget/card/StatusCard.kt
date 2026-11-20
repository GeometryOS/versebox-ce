// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.widget.card

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.a10vm.ui.icons.AppIcons

/**
 * Material 3 版的大状态卡：整卡可点（重跑一次检测），配色由调用方给的角色色决定。
 *
 * 解剖结构与主页那张状态卡（`HomePage.kt` 的 `ProcessStatusCard`）逐项相同：
 * `ElevatedCard` + `Row`(24dp 内距) + 28dp 图标（左右各 4dp 内距，tint = contentColor）
 * + 起始 20dp 的三行文字（标题 / 说明 / 操作文字）。
 *
 * 字号与颜色取同一套角色：
 * - 标题 `titleMediumEmphasized`，contentColor；
 * - 说明 `bodySmallEmphasized`，contentColor；
 * - 操作文字 `bodySmallEmphasized`，contentColor 的 80% —— 与主页卡的第三行同一处方，
 *   它是"这张卡现在能不能点、点了做什么"的唯一提示，不允许删。
 *
 * 边距：默认 16dp 横 / 8dp 纵，由组件自己承担，页面不写任何横向 `dp` 字面量
 * （见 `docs/ui-rework/03` §7）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusCard(
    title: String,
    description: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    actionText: String? = null,
    icon: ImageVector = AppIcons.Info,
) {
    val actionColor = contentColor.copy(alpha = 0.8f)
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .size(28.dp)
                    .padding(horizontal = 4.dp),
            )
            Column(
                modifier = Modifier.padding(start = 20.dp),
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = contentColor,
                )
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = description,
                    style = MaterialTheme.typography.bodySmallEmphasized,
                    color = contentColor,
                )
                if (actionText != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = actionText,
                        style = MaterialTheme.typography.bodySmallEmphasized,
                        color = actionColor,
                    )
                }
            }
        }
    }
}
