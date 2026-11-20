// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui.page.miuix.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.a10vm.ui.icons.AppMiuixIcons
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 大状态卡：Card(defaultColors) + Tilt + 波纹，
 * 右上角 170dp 背景大图标，前景 20sp 标题 / 14sp 说明 / 36dp 后的操作文字。
 */
@Composable
fun MiuixStatusCard(
    title: String,
    description: String,
    containerColor: Color,
    contentColor: Color,
    iconTint: Color,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    icon: ImageVector = AppMiuixIcons.Info,
    onClick: () -> Unit,
) {
    val descTextColor = contentColor.copy(alpha = 0.8f)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = containerColor),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    modifier = Modifier
                        .padding(top = 38.dp)
                        .size(170.dp),
                    imageVector = icon,
                    tint = iconTint.copy(alpha = 0.8f),
                    contentDescription = null,
                )
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = description,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = descTextColor,
                )
                if (actionText != null) {
                    Spacer(Modifier.height(36.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = actionText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = descTextColor,
                    )
                }
            }
        }
    }
}

/** 并排数字卡（高度由外层 IntrinsicSize.Min 对齐）。 */
@Composable
fun MiuixStatCard(modifier: Modifier = Modifier, title: String, value: String, onClick: () -> Unit) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = title,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 提示卡：primaryContainer 25% 底 + primary 色 SemiBold 正文 + 16dp 内边距，没有图标。
 * 文字统一用 body2。

 */
@Composable
fun MiuixTipCard(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            text = text,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}
