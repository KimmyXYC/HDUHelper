package moe.nepnep.hduhelper.data.background

import org.junit.Assert.*
import org.junit.Test

class BackgroundStatusTest {
    @Test fun onlyExplicitPermissionValuesAreReportedAsAllowed() {
        assertEquals(PermissionState.ALLOWED, autostartState(0))
        for (mode in listOf(1, 2)) assertEquals(PermissionState.RESTRICTED, autostartState(mode))
        for (mode in listOf(null, 3, 4, -1, 10008)) assertEquals(PermissionState.UNKNOWN, autostartState(mode))
        for (value in listOf("noRestrict", "no_restrict")) assertEquals(PermissionState.ALLOWED, vendorBatteryState(value))
        for (value in listOf("miuiAuto", "restrictBg", "noBg")) assertEquals(PermissionState.RESTRICTED, vendorBatteryState(value))
        for (value in listOf(null, "", "default", "unexpected")) assertEquals(PermissionState.UNKNOWN, vendorBatteryState(value))
    }

    @Test fun reminderExemptionRequiresMatchingCreatorUidPackageAndAction() {
        val app = BackgroundWire.APP
        for (action in listOf("$app.COURSE_REMINDER", "$app.SCHEDULE_REMINDER")) {
            assertTrue(matchesReminder(app, 10234, 10234, action))
            assertFalse(matchesReminder(app, 10234, null, action))
            assertFalse(matchesReminder(app, 110234, 10234, action))
            assertFalse(matchesReminder("other.app", 10234, 10234, action))
            assertFalse(matchesReminder(null, 10234, 10234, action))
        }
        assertFalse(matchesReminder(app, 10234, 10234, "$app.OTHER"))
        assertFalse(matchesReminder(app, 10234, 10234, null))
    }

    @Test fun activationRequiresLoadedSupportedHooks() {
        for (hook in BackgroundHookState.entries) {
            assertEquals(hook in setOf(BackgroundHookState.READY, BackgroundHookState.ACTIVE), BackgroundStatus(hook = hook).canEnable)
        }
    }
}
