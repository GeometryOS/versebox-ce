// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package io.a10vm.ui.page.main.widget.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun BaseItemContainer(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val baseShape = LocalSegmentedItemShape.current
    val backgroundColor = MaterialTheme.colorScheme.surfaceBright
    val itemContent: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }

    if (onClick == null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = backgroundColor,
            shape = baseShape,
            content = itemContent,
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            color = backgroundColor,
            shape = baseShape,
            content = itemContent,
        )
    }
}
