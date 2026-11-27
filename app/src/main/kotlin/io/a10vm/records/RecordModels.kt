package io.a10vm.records

/** 记录的事件类型。新增类型只许往后加，顺序是筛选器里的展示顺序。 */
enum class RecordKind(val label: String) {
    LAUNCH("启动"),
    STOP("停止"),
    INSTALL_APK("安装 APK"),
    IMPORT_APPS("导入应用"),
    IMPORT_FILES("导入文件"),
    INSPECT("检查运行环境"),
    CHECK_DESKTOP("测试桌面"),
    DELETE("删除"),
    RENAME("重命名"),

    /** 运行中客体自己崩了（不是用户停止，也不是启动失败）。必须留记录，否则无从调试。 */
    CRASH("崩溃"),

    /** 创建一台虚拟机（含用的是哪个 ROM）。没有客体日志，只写索引行。 */
    CREATE_VM("创建虚拟机"),

    /** 导入一个 ROM 包（数据层动作，不属于某台虚拟机的运行过程）。 */
    ROM_IMPORT("导入 ROM"),

    /** 删除本地 ROM 包 / 清理内置 ROM 的解包缓存。 */
    ROM_DELETE("删除 ROM"),

    /**
     * 手动检查一次 ROM 的解包内容（docs/rom-package/12/04 §2）。
     *
     * 它不是导入、也不是删除，所以单列一条；同样只写索引行、不归档（没有客体日志）。
     */
    ROM_CHECK("检查 ROM"),

    /**
     * 改这台虚拟机的显示设置（分辨率 / DPI）。
     *
     * 没有客体日志可归档（改动不产生运行日志），所以只写索引行；
     * 审计事件同时在 audit.log 里留一条 `vm_display_changed`（docs/display-settings/06）。
     */
    DISPLAY("显示设置"),

    /**
     * 切换这台虚拟机使用的 ROM 版本（docs/rom-package/14-版本管理/01 §3）。
     *
     * 只写索引行、不归档：改版本不产生客体日志（重启后的那次运行会另有一条 LAUNCH）。
     * 审计事件同时在 audit.log 里留一条 `vm_rom_version_changed`。
     */
    ROM_VERSION("切换 ROM 版本"),

    ROM_EXTRACT("解包 ROM"),
    ROM_REPAIR("自动修复 ROM"),
    ROM_REEXTRACT("重新解包 ROM"),
    ROM_CLEAR_CACHE("清理解包体"),

    BACKUP_VM("备份虚拟机"),
    RESTORE_VM("恢复虚拟机"),
    VM_BACKUP_MANAGE("管理虚拟机备份"),
}

enum class RecordStatus(val label: String) {
    RUNNING("进行中"),
    SUCCESS("成功"),
    FAILED("失败"),
    CANCELLED("已取消"),
}

/**
 * 一条运行记录。
 *
 * 这是**索引**，不是日志原文：原文在 [logDir] 指向的归档目录里（由 [RecordCollector] 落盘）。
 * UI 只读这个对象，不碰文件；导出/预览由 [RecordExporter] 负责。
 */
data class VmRecord(
    val id: Long,
    val vmId: String,
    val vmName: String,
    val romId: String,
    val romName: String?,
    val androidVersion: String?,
    val apiLevel: Int,
    val kind: RecordKind,
    val status: RecordStatus,
    val failure: String?,
    val summary: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val durationMs: Long,
    val appVersion: String,
    val hostRelease: String,
    val hostModel: String,
    val logDir: String?,
    val eventCount: Int,
) {
    val durationText: String
        get() = when {
            durationMs <= 0 -> "—"
            durationMs < 1000 -> "${durationMs} 毫秒"
            durationMs < 60_000 -> String.format("%.1f 秒", durationMs / 1000.0)
            else -> "${durationMs / 60_000} 分 ${(durationMs % 60_000) / 1000} 秒"
        }

    /** 一句话结论：成功说结果，失败说原因。列表只显示这一行。 */
    val headline: String
        get() = when (status) {
            RecordStatus.FAILED -> failure?.takeIf { it.isNotBlank() } ?: "未给出原因"
            RecordStatus.RUNNING -> "正在${kind.label}…"
            RecordStatus.CANCELLED -> "已取消"
            RecordStatus.SUCCESS -> summary?.takeIf { it.isNotBlank() } ?: "${kind.label}完成"
        }
}
