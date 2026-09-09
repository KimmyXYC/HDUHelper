package moe.nepnep.hduhelper.data.background

import org.junit.Assert.*
import org.junit.Test

class BackgroundPolicyTest {
    private val identity = BackgroundIdentity(appUid = 10421, moduleUid = 10420)
    private val app = BackgroundWire.APP

    @Test fun providerBootstrapRequiresTheVerifiedPairAndExactProvider() {
        fun allowed(own: BackgroundIdentity? = identity, caller: String? = app, callerUid: Int = identity.appUid,
            target: String? = ModuleWire.PACKAGE, targetUid: Int = identity.moduleUid,
            component: String? = BackgroundPolicy.MODULE_PROVIDER, user: Int = 0) =
            BackgroundPolicy.moduleConnection(own, caller, callerUid, target, targetUid, component, user)
        assertTrue(allowed())
        assertFalse(allowed(own = null)) // Includes missing APK and signature mismatch.
        assertFalse(allowed(caller = "other.app"))
        assertFalse(allowed(callerUid = 1000))
        assertFalse(allowed(callerUid = 110421))
        assertFalse(allowed(target = app))
        assertFalse(allowed(targetUid = 10419))
        assertFalse(allowed(component = "${ModuleWire.PACKAGE}.XposedService"))
        assertFalse(allowed(user = 1))
    }

    @Test fun alarmMustTargetTheReceiverForItsOwnAction() {
        val targets = mapOf("$app.COURSE_REMINDER" to BackgroundPolicy.COURSE_RECEIVER,
            "$app.SCHEDULE_REMINDER" to BackgroundPolicy.SCHEDULE_RECEIVER)
        for ((action, component) in targets) {
            assertTrue(BackgroundPolicy.reminder(identity, app, identity.appUid, action, app, component))
            assertFalse(BackgroundPolicy.reminder(null, app, identity.appUid, action, app, component))
            assertFalse(BackgroundPolicy.reminder(identity, "other.app", identity.appUid, action, app, component))
            assertFalse(BackgroundPolicy.reminder(identity, app, 1000, action, app, component))
            assertFalse(BackgroundPolicy.reminder(identity, app, identity.appUid, action, "other.app", component))
            assertFalse(BackgroundPolicy.reminder(identity, app, identity.appUid, action, app, BackgroundPolicy.RESTORE_RECEIVER))
            assertFalse(BackgroundPolicy.reminder(identity, app, identity.appUid, "$app.OTHER", app, component))
            assertFalse(BackgroundPolicy.reminder(identity, app, identity.appUid, action, app, null))
        }
    }

    @Test fun coldReminderStartRequiresAnAlarmAndEnabledEnhancement() {
        fun allowed(enabled: Boolean = true, caller: String? = app, callerUid: Int = identity.appUid,
            alarm: Boolean = true, targetUid: Int = identity.appUid, user: Int = 0) =
            BackgroundPolicy.broadcast(identity, enabled, caller, callerUid, alarm, "$app.COURSE_REMINDER",
                app, targetUid, BackgroundPolicy.COURSE_RECEIVER, user)
        assertTrue(allowed())
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(alarm = false))
        assertFalse(allowed(caller = "android", callerUid = 1000))
        assertFalse(allowed(callerUid = 10422))
        assertFalse(allowed(targetUid = 10422))
        assertFalse(allowed(user = 1))
    }

    @Test fun restorationAllowsOnlySystemLifecycleBroadcastsToTheRestoreReceiver() {
        fun allowed(action: String, enabled: Boolean = true, caller: String? = "android", uid: Int = 1000,
            component: String = BackgroundPolicy.RESTORE_RECEIVER) =
            BackgroundPolicy.broadcast(identity, enabled, caller, uid, false, action, app,
                identity.appUid, component, 0)
        for (action in listOf("BOOT_COMPLETED", "USER_UNLOCKED", "TIME_SET", "TIMEZONE_CHANGED", "MY_PACKAGE_REPLACED")) {
            val fullAction = "android.intent.action.$action"
            assertTrue(allowed(fullAction))
            assertTrue(allowed(fullAction, caller = null))
            assertFalse(allowed(fullAction, enabled = false))
            assertFalse(allowed(fullAction, caller = app, uid = identity.appUid))
            assertFalse(allowed(fullAction, component = BackgroundPolicy.COURSE_RECEIVER))
        }
        assertFalse(allowed("android.intent.action.PACKAGE_ADDED"))
        assertFalse(allowed("$app.SCHEDULE_REMINDER"))
    }
}
