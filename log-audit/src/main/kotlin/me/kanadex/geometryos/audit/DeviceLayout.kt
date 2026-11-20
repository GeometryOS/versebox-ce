package me.kanadex.geometryos.audit

/**
 * 一台虚拟机的路径布局（由 :app 适配层给出，本模块不拼 `filesDir`）。
 *
 * - [runtimeDir]：`files/devices/<vmId>/vm`（运行时目录，旧版 `application-*.log` 就在这里）
 * - [sessionsDir]：`files/devices/<vmId>/vm/sessions`（会话组，见 docs/log-audit/04 §6）
 * - [windowLogPath]：`files/devices/<vmId>/native-window/gpu-host.log`
 */
data class DeviceLayout(
    val vmId: String,
    val runtimeDir: String,
    val sessionsDir: String,
    val windowLogPath: String,
)
