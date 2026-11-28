package io.a10vm.runtime

/** 重命名结果。每一种失败都有明确类型，UI 不猜、不靠 toast 里的字符串判断。 */
sealed interface RenameResult {
    data class Renamed(val device: VmDevice, val correctionMessage: String? = null) : RenameResult
    data object EmptyName : RenameResult
    data object TooLong : RenameResult
    data object Duplicate : RenameResult
    data object NotFound : RenameResult
    data class Failed(val cause: Throwable) : RenameResult
    data object VerifyFailed : RenameResult
}
