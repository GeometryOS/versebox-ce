package io.a10vm.runtime

import android.content.Context
import java.io.File

/**
 * 一台虚拟机：编号、显示名、绑定的 ROM，以及它在宿主私有目录里的三处路径。
 *
 * - [root] 是客体状态根（`A10VM_STATE_ROOT`），脚本、截图、host-*.json 都在这里；
 * - [runtimeDir] 是这台虚拟机自己的 `/system`、`/vendor` 与运行库；
 * - [windowDir] 保存 GPU/输入 socket 与原生窗口日志。
 *
 * 三个路径都相对 `filesDir`，这样旧目录（`guest-runtime/`、`a9-state/`）和新目录
 * （`devices/<时间戳>/`）能用同一套代码表达，也不用把旧数据搬家。
 *
 * [displayMode] 是这台虚拟机的**显示模式标识**（真机竖屏/横屏，或 480x800@240 这样的预设），
 * 解析与文案在 `:vm-display` 的 `me.kanadex.geometryos.display` 里（docs/display-settings/03）。
 * 空串 = 默认值（真机分辨率竖屏），所以升级数据库不需要回填老实例。
 *
 * [romPayload] 与 [romSource] 一起构成「用的是哪一份 ROM」的完整身份
 * （`docs/rom-package/14-版本管理/04-身份与分类红线.md` R3）：
 * `rom_id` 只说"哪个系统的哪一版"，同版本号的**另一份内容**（内置一份 + 本地导入一份）
 * 必须靠内容指纹区分，来源则用来回答"列表里哪一行在生效"。
 *
 * 两者都允许空串：老实例只记了 `rom_id`（甚至只记了 `android15` 这种 id），
 * 空串 = "按旧的宽松语义解析"，不能当成"没有 ROM"。
 */
data class VmDevice(
    val id: String,
    val name: String,
    val romId: String,
    val root: String,
    val runtimeDir: String,
    val windowDir: String,
    val legacy: Boolean = false,
    val displayMode: String = "",
    /** 这台虚拟机绑定的 ROM 内容指纹（`payloadSha256`）；空串 = 旧实例，未记录。 */
    val romPayload: String = "",
    /** 这台虚拟机绑定的 ROM 来源（`RomSource` 的名字）；空串 = 旧实例，未记录。 */
    val romSource: String = "",
)

/**
 * 虚拟机注册表门面。
 *
 * 存储已经换成 SQLite（[VmRegistry]），这里只保留调用方需要的形状：
 * 旧代码（VmService / MainActivity / FullscreenActivity）一行都不用改，
 * 但它们写名字的路径从此只有 [VmRegistry.rename] 一条。
 */
class VmDevices(context: Context) {
    private val registry = VmRegistry.get(context)

    fun list(): List<VmDevice> = registry.list()

    fun find(id: String?): VmDevice? = registry.find(id)

    fun rootOf(device: VmDevice): File = registry.rootOf(device)

    fun runtimeOf(device: VmDevice): File = registry.runtimeOf(device)

    fun windowOf(device: VmDevice): File = registry.windowOf(device)

    fun ensure(device: VmDevice) = registry.ensureDirectories(device)

    /** 新建实例；[romPayload] / [romSource] 是「用的是哪一份 ROM」的身份（见 [VmDevice]）。 */
    fun create(romId: String, romPayload: String = "", romSource: String = ""): VmDevice =
        registry.create(romId, romPayload, romSource)

    fun delete(id: String): VmDevice? = registry.delete(id)

    fun rename(id: String, name: String): RenameResult = registry.rename(id, name)

    /** 写显示模式（分辨率 / DPI）；与重命名共用同一份注册表连接与读回自证。 */
    fun setDisplayMode(id: String, mode: String): Boolean = registry.setDisplayMode(id, mode)

    /** 注册表版本号；写入成功就 +1。 */
    fun revision() = registry.revision.value
}
