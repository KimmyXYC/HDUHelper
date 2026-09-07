package moe.nepnep.hduhelper.data.background

enum class PermissionState { ALLOWED, RESTRICTED, UNKNOWN, UNSUPPORTED }

enum class BackgroundHookState(val label: String) {
    INACTIVE("Xposed 模块未激活"), SCOPE_REQUIRED("请在 LSPosed 中勾选系统框架"),
    RESTART_REQUIRED("请重启设备以加载系统框架作用域"), UNSUPPORTED("当前系统不支持提醒增强"),
    READY("已就绪"), ACTIVE("已生效"), SYNC_FAILED("配置同步失败，请重试"),
}

data class BackgroundStatus(
    val autostart: PermissionState = PermissionState.UNKNOWN,
    val batteryExemption: PermissionState = PermissionState.UNKNOWN,
    val vendorBattery: PermissionState = PermissionState.UNKNOWN,
    val hook: BackgroundHookState = BackgroundHookState.INACTIVE,
) {
    val canEnable: Boolean get() = hook == BackgroundHookState.READY || hook == BackgroundHookState.ACTIVE
}

fun autostartState(mode: Int?): PermissionState = when (mode) {
    0 -> PermissionState.ALLOWED
    1, 2 -> PermissionState.RESTRICTED
    else -> PermissionState.UNKNOWN
}

fun vendorBatteryState(value: String?): PermissionState = when (value) {
    "noRestrict", "no_restrict" -> PermissionState.ALLOWED
    "miuiAuto", "miui_auto", "restrictBg", "restrict_bg", "noBg", "no_bg" -> PermissionState.RESTRICTED
    else -> PermissionState.UNKNOWN
}

/** A package name alone is insufficient: PendingIntent creator identity must agree. */
fun matchesReminder(packageName: String?, uid: Int, expectedUid: Int?, action: String?): Boolean =
    packageName == BackgroundWire.APP && expectedUid != null && uid == expectedUid &&
        action in setOf("${BackgroundWire.APP}.COURSE_REMINDER", "${BackgroundWire.APP}.SCHEDULE_REMINDER")
