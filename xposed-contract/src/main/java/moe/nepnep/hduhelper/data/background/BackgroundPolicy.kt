package moe.nepnep.hduhelper.data.background

/** Identities are supplied only after PackageManager verifies the two APK signatures. */
data class BackgroundIdentity(val appUid: Int, val moduleUid: Int)

object BackgroundPolicy {
    const val MODULE_PROVIDER = "${ModuleWire.PACKAGE}.ModuleBridgeProvider"
    const val COURSE_RECEIVER = "${BackgroundWire.APP}.data.notifications.CourseReminderReceiver"
    const val SCHEDULE_RECEIVER = "${BackgroundWire.APP}.data.schedule.ScheduleAlarmReceiver"
    const val RESTORE_RECEIVER = "${BackgroundWire.APP}.data.schedule.ScheduleRestoreReceiver"

    fun moduleConnection(identity: BackgroundIdentity?, caller: String?, callerUid: Int,
        target: String?, targetUid: Int, component: String?, userId: Int): Boolean =
        identity != null && userId == 0 && caller == BackgroundWire.APP && callerUid == identity.appUid &&
            target == ModuleWire.PACKAGE && targetUid == identity.moduleUid && component == MODULE_PROVIDER

    fun reminder(identity: BackgroundIdentity?, creator: String?, creatorUid: Int, action: String?,
        target: String?, component: String?): Boolean = identity != null &&
        matchesReminder(creator, creatorUid, identity.appUid, action) && target == BackgroundWire.APP &&
        component == when (action) {
            "${BackgroundWire.APP}.COURSE_REMINDER" -> COURSE_RECEIVER
            "${BackgroundWire.APP}.SCHEDULE_REMINDER" -> SCHEDULE_RECEIVER
            else -> null
        }

    fun broadcast(identity: BackgroundIdentity?, enabled: Boolean, caller: String?, callerUid: Int,
        alarm: Boolean, action: String?, target: String?, targetUid: Int, component: String?, userId: Int): Boolean {
        if (!enabled || identity == null || userId != 0 || targetUid != identity.appUid) return false
        if (alarm && reminder(identity, caller, callerUid, action, target, component)) return true
        // Protected lifecycle broadcasts rebuild persisted alarms after reboot/update/time changes.
        return (caller == null || caller == "android") && callerUid == 1000 && target == BackgroundWire.APP &&
            component == RESTORE_RECEIVER && action in setOf(
                "android.intent.action.BOOT_COMPLETED", "android.intent.action.USER_UNLOCKED",
                "android.intent.action.TIME_SET", "android.intent.action.TIMEZONE_CHANGED",
                "android.intent.action.MY_PACKAGE_REPLACED",
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            )
    }
}
