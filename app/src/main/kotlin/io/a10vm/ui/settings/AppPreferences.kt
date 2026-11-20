package io.a10vm.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import io.a10vm.domain.settings.model.preferences.PredictiveBackAnimation
import io.a10vm.domain.settings.model.preferences.PredictiveBackExitDirection
import io.a10vm.domain.settings.model.preferences.ThemeState
import io.a10vm.domain.settings.model.preferences.theme.PaletteStyle
import io.a10vm.domain.settings.model.preferences.theme.ThemeColorSpec
import io.a10vm.domain.settings.model.preferences.theme.ThemeMode
import io.a10vm.ui.theme.material.PresetColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用设置的唯一写入口。
 *
 * 为什么不用 DataStore：本工程只有一张扁平的偏好表（二十来个键），
 * SharedPreferences + StateFlow 已经能做到「读写都走一个地方、UI 只订阅」，
 * 少一个依赖、少一个序列化层。语义与 InstallerX 的设置项一一对应。
 */
class AppPreferences private constructor(context: Context) {
    companion object {
        private const val FILE = "versebox-settings"
        private const val K_USE_MIUIX = "theme.useMiuix"
        private const val K_MODE = "theme.mode"
        private const val K_PALETTE = "theme.palette"
        private const val K_SPEC = "theme.spec"
        private const val K_DYNAMIC = "theme.dynamic"
        private const val K_MONET = "theme.monet"
        private const val K_FLOATING_BAR = "theme.floatingBar"
        private const val K_SEED = "theme.seed"
        private const val K_BLUR = "theme.blur"
        private const val K_BACK = "theme.predictiveBack"
        private const val K_BACK_DIR = "theme.predictiveBackDirection"
        private const val K_HIDE_ICON = "app.hideLauncherIcon"

        /** 虚拟机 Tab 图标上的数量角标是否显示（设置 → 外观）。 */
        private const val K_SHOW_VM_BADGE = "app.showVmBadge"

        /**
         * 记录总开关。**公开**是有意的：记录采集在数据层（RecordCollector）也要读它，
         * 键名只允许在这里出现一次。
         */
        const val FILE_NAME = "versebox-settings"
        private const val K_MAIN_PAGE = "app.mainPage"
        const val KEY_COLLECT_RECORDS = "app.collectRecords"

        @Volatile private var instance: AppPreferences? = null

        fun get(context: Context): AppPreferences =
            instance ?: synchronized(this) {
                instance ?: AppPreferences(context.applicationContext).also { instance = it }
            }
    }

    private val context = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(readTheme())
    private val _hideLauncherIcon = MutableStateFlow(prefs.getBoolean(K_HIDE_ICON, false))
    private val _collectRecords = MutableStateFlow(prefs.getBoolean(KEY_COLLECT_RECORDS, true))
    private val _showVmBadge = MutableStateFlow(prefs.getBoolean(K_SHOW_VM_BADGE, true))

    val theme: StateFlow<ThemeState> = _theme.asStateFlow()
    val hideLauncherIcon: StateFlow<Boolean> = _hideLauncherIcon.asStateFlow()
    val collectRecords: StateFlow<Boolean> = _collectRecords.asStateFlow()

    /** 虚拟机数量角标开关：两套 UI 分支都读它（默认开）。 */
    val showVmBadge: StateFlow<Boolean> = _showVmBadge.asStateFlow()

    private fun readTheme(): ThemeState = ThemeState(
        useMiuix = prefs.getBoolean(K_USE_MIUIX, true),
        themeMode = ThemeMode.fromValueOrDefault(prefs.getString(K_MODE, "") ?: ""),
        paletteStyle = PaletteStyle.fromValueOrDefault(prefs.getString(K_PALETTE, "") ?: ""),
        colorSpec = ThemeColorSpec.fromValueOrDefault(prefs.getString(K_SPEC, "") ?: ""),
        useDynamicColor = prefs.getBoolean(K_DYNAMIC, true),
        useMiuixMonet = prefs.getBoolean(K_MONET, false),
        useAppleFloatingBar = prefs.getBoolean(K_FLOATING_BAR, false),
        // 注意：Color.value 是 Compose 的 packed ULong，不是 ARGB。必须 toArgb()，
        // 否则存进去的是截断后的垃圾值，重启后主题色会变成黑色系。
        seedColor = prefs.getInt(K_SEED, PresetColors[0].color.toArgb()),
        useBlur = prefs.getBoolean(K_BLUR, Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
        predictiveBackAnimation = PredictiveBackAnimation.fromValueOrDefault(prefs.getString(K_BACK, "") ?: ""),
        predictiveBackExitDirection = PredictiveBackExitDirection.fromValueOrDefault(prefs.getString(K_BACK_DIR, "") ?: ""),
    )

    /** 所有写操作统一走这里：先落盘、再更新内存流，UI 只订阅流。 */
    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _theme.value = readTheme()
    }

    /** 备份还原之后重新读一遍，让 UI 立刻跟着变。 */
    fun reload() {
        _theme.value = readTheme()
        _hideLauncherIcon.value = prefs.getBoolean(K_HIDE_ICON, false)
        _collectRecords.value = prefs.getBoolean(KEY_COLLECT_RECORDS, true)
        _showVmBadge.value = prefs.getBoolean(K_SHOW_VM_BADGE, true)
    }

    fun setUseMiuix(value: Boolean) = edit { putBoolean(K_USE_MIUIX, value) }
    fun setThemeMode(value: ThemeMode) = edit { putString(K_MODE, value.name) }
    fun setPaletteStyle(value: PaletteStyle) = edit { putString(K_PALETTE, value.name) }
    fun setColorSpec(value: ThemeColorSpec) = edit { putString(K_SPEC, value.name) }
    fun setUseDynamicColor(value: Boolean) = edit { putBoolean(K_DYNAMIC, value) }
    fun setUseMiuixMonet(value: Boolean) = edit { putBoolean(K_MONET, value) }
    fun setUseAppleFloatingBar(value: Boolean) = edit { putBoolean(K_FLOATING_BAR, value) }
    fun setSeedColor(value: Int) = edit { putInt(K_SEED, value) }
    fun setUseBlur(value: Boolean) = edit { putBoolean(K_BLUR, value) }
    fun setPredictiveBack(value: PredictiveBackAnimation) = edit { putString(K_BACK, value.value) }
    fun setPredictiveBackDirection(value: PredictiveBackExitDirection) = edit { putString(K_BACK_DIR, value.value) }

    fun setHideLauncherIcon(value: Boolean) {
        prefs.edit().putBoolean(K_HIDE_ICON, value).apply()
        _hideLauncherIcon.value = value
    }

    fun setCollectRecords(value: Boolean) {
        prefs.edit().putBoolean(KEY_COLLECT_RECORDS, value).apply()
        _collectRecords.value = value
    }

    fun setShowVmBadge(value: Boolean) {
        prefs.edit().putBoolean(K_SHOW_VM_BADGE, value).apply()
        _showVmBadge.value = value
    }

    /** 上次停留的主页面 Tab（照 InstallerX 的 lastMainPageIndex）。 */
    fun mainPageIndex(): Int = prefs.getInt(K_MAIN_PAGE, 0)

    fun setMainPageIndex(index: Int) {
        prefs.edit().putInt(K_MAIN_PAGE, index).apply()
    }

    /** 忽略电池优化：没开关可用时就返回 true，避免把「不支持」显示成「未优化」。 */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }
}

@Composable
fun rememberAppPreferences(): AppPreferences {
    val context = LocalContext.current
    return remember(context) { AppPreferences.get(context) }
}
