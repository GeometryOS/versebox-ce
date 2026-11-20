package io.a10vm.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.a10vm.ui.settings.AppPreferences
import io.a10vm.ui.verse.AppState
import io.a10vm.ui.verse.VerseBoxApp

/**
 * 应用主窗口：一个 Compose 宿主，里面是四 Tab + 二级页面。
 *
 * singleTask + 复用自己：从启动页/通知点「管理」时回到的是**已经存在的这个窗口**，
 * 不会另起一个主页、也不会把虚拟机窗口关掉。
 *
 * 从第一屏（`FullscreenActivity`）过来的三个请求都在 [consumeIntent] 里落到 [AppState] 上：
 * 记录定位（09/05）、ROM 定位 + 自动修复（09/04）、打开 ROM 管理（C2 导入）。
 */
class MainActivity : ComponentActivity() {
    private lateinit var preferences: AppPreferences
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences.get(this)
        state = AppState(this)
        // 系统栏图标由 VerseBoxTheme 在 Compose 里设置：setContent 之后 DecorView 才存在，
        // 在这里调 window.insetsController 会空指针（已踩过一次）。
        consumeIntent(intent)
        setContent {
            VerseBoxApp(preferences, state)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    /**
     * 把一次入口 Intent 翻译成 [AppState] 上的请求，翻译完就把 extra 删掉，
     * 免得下次 [onNewIntent] 拿着同一个意图又定位一遍。
     */
    private fun consumeIntent(intent: Intent?) {
        if (intent == null) return
        val recordId = intent.getLongExtra(EXTRA_FOCUS_RECORD, -1L)
        if (recordId > 0) {
            state.focusRecord(recordId, intent.getStringExtra(EXTRA_FOCUS_RECORD_VM))
            intent.removeExtra(EXTRA_FOCUS_RECORD)
            intent.removeExtra(EXTRA_FOCUS_RECORD_VM)
        }
        val romKey = intent.getStringExtra(EXTRA_FOCUS_ROM)
        if (!romKey.isNullOrEmpty()) {
            // rowId 也要一起传：同版本可能有内置/本地两行（或同来源两份内容），
            // 只带 romKey 会让 ROM 列表"取第一行"，修的是另一份内容。
            state.focusRom(
                key = romKey,
                repairVmId = intent.getStringExtra(EXTRA_ROM_REPAIR_VM),
                rowId = intent.getStringExtra(EXTRA_FOCUS_ROM_ROW),
            )
            intent.removeExtra(EXTRA_FOCUS_ROM)
            intent.removeExtra(EXTRA_FOCUS_ROM_ROW)
            intent.removeExtra(EXTRA_ROM_REPAIR_VM)
        }
        val versionVm = intent.getStringExtra(EXTRA_ROM_VERSION)
        if (!versionVm.isNullOrEmpty()) {
            state.openVmRomVersion(versionVm)
            intent.removeExtra(EXTRA_ROM_VERSION)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_ROM_LIST, false)) {
            state.openRomList()
            intent.removeExtra(EXTRA_OPEN_ROM_LIST)
        }
    }

    override fun onStart() {
        super.onStart()
        state.bind()
    }

    override fun onStop() {
        state.unbind()
        super.onStop()
    }

    companion object {
        const val EXTRA_FOCUS_RECORD = "focusRecord"

        /** 与记录一起带过来的虚拟机 id：定位时顺带把筛选限定到这台（09/05 §2）。 */
        const val EXTRA_FOCUS_RECORD_VM = "focusRecordVm"

        /** ROM 主键：落到 ROM 管理并定位到这一行（09/04 §2）。 */
        const val EXTRA_FOCUS_ROM = "focusRom"

        /** 修完要重跑的虚拟机 id（09/04 §2 第 6 步）。 */
        const val EXTRA_ROM_REPAIR_VM = "romRepairVm"

    /** 要定位/修复的**那一行**（`<来源>:<romKey>@<sha8>`）；空 = 旧调用点，只按 romKey 定位。 */
    const val EXTRA_FOCUS_ROM_ROW = "focusRomRow"

        /** 只要求打开 ROM 管理（C2 的「导入这个 ROM」）。 */
        const val EXTRA_OPEN_ROM_LIST = "openRomList"

        /** 打开某台虚拟机的 ROM 版本管理（启动页的「去版本管理」）。 */
        const val EXTRA_ROM_VERSION = "romVersionVm"
    }
}