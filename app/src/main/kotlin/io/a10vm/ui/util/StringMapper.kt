package io.a10vm.ui.util

import io.a10vm.ui.theme.material.RawColor

/**
 * 色板显示名。
 *
 * 只保留 ColorSwatchPreview 需要的这一个扩展：InstallerX 里同名文件还带着安装器业务
 * （DataType/Mmz 的标题映射），那些与本工程无关，不搬。
 */
fun RawColor.getDisplayName(): String = key
