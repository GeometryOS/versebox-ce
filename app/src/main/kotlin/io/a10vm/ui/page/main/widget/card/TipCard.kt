// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.main.widget.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.a10vm.ui.icons.AppIcons

/**
 * Material 3 版的提示卡：
 * tertiary 80% 底 + 24dp 图标（库默认尺寸）+ bodyMediumEmphasized 文字，图标与文字间距 8dp，
 * 外面默认 16dp 横 / 8dp 纵边距。
 *
 * 与 Miuix 的 `MiuixTipCard` 成对，两套 UI 共用同一份文案；
 * 样式按各自体系来。
 */
@Composable
fun InfoTipCard(
    text: String,
    modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    icon: ImageVector? = AppIcons.Tip,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
            contentColor = MaterialTheme.colorScheme.onTertiary,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiary,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMediumEmphasized,
            )
        }
    }
}
