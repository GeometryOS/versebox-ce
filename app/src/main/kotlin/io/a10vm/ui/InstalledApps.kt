// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package io.a10vm.ui

import android.app.Activity

/**
 * 「导入已安装应用」的入口。
 *
 * 选中列表本身是原生 View 实现的（搜索 + 多选 + 图标，见 [InstalledAppsDialog]），
 * 这里只把它包成一个能在 Compose 里直接调用的函数：Compose 页面不需要知道它内部是 View。
 */
fun showInstalledAppsDialog(activity: Activity, onImport: (List<String>) -> Unit) {
    InstalledAppsDialog(activity, onImport).show()
}
