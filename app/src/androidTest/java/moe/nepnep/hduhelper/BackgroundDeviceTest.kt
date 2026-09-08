package moe.nepnep.hduhelper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import moe.nepnep.hduhelper.data.background.*
import moe.nepnep.hduhelper.data.notifications.CourseReminderReceiver
import moe.nepnep.hduhelper.data.schedule.ScheduleAlarmReceiver
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class BackgroundDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as HDUHelperApplication).container

    @Test fun appCannotRegisterAnImpostorSystemServerHost() {
        assertTrue(runCatching {
            context.contentResolver.call("content://${BackgroundWire.AUTHORITY}".toUri(), "register", null,
                Bundle().apply { putInt("version", BackgroundWire.VERSION); putBinder("host", Binder()) })
        }.isFailure)
    }

    @Test fun reportReadOnlyBackgroundDetection(): Unit = runBlocking {
        container.background.refreshNow()
        delay(1500)
        val status = container.background.refreshNow()
        instrumentation.sendStatus(0, Bundle().apply {
            putString("background_autostart", status.autostart.name)
            putString("background_vendor_battery", status.vendorBattery.name)
            putString("background_android_battery", status.batteryExemption.name)
            putString("background_hook", status.hook.name)
            putBoolean("background_requested", container.settings.state.value.backgroundEnhancement)
            BackgroundHostConnection.binder.value?.let { host ->
                runCatching { BackgroundWire.status(host) }.getOrNull()?.let {
                    putBoolean("background_host_ready", it.getBoolean("ready"))
                    putBoolean("background_host_enabled", it.getBoolean("enabled"))
                    putBoolean("background_host_current", it.getString("modulePath") == XposedFramework.service.value?.modulePath)
                }
            }
            XposedFramework.service.value?.let { service ->
                putInt("background_framework_api", service.apiVersion)
                putBoolean("background_system_scoped", service.scope.contains("system"))
                putBoolean("background_remote_supported", service.remote)
            }
        })
        assertNotEquals(PermissionState.UNKNOWN, status.batteryExemption)
    }

    @Test fun updatedModuleApkRequiresSystemServerRestart(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backgroundUpdated") == "true")
        container.background.refreshNow()
        delay(1500)
        val status = container.background.refreshNow()
        assertNotNull(BackgroundHostConnection.binder.value)
        assertEquals(BackgroundHookState.RESTART_REQUIRED, status.hook)
        assertFalse(status.canEnable)
    }

    @Test fun loadedHostReportsRealPermissionsAndSwitchControlsReminderAlarmExemptions(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backgroundEnabled") == "true")
        val original = container.settings.state.value.backgroundEnhancement
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = listOf(
            PendingIntent.getBroadcast(context, 9041, Intent(context, CourseReminderReceiver::class.java)
                .setAction("${BackgroundWire.APP}.COURSE_REMINDER").putExtra("token", "invalid-background-probe"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            PendingIntent.getBroadcast(context, 9042, Intent(context, ScheduleAlarmReceiver::class.java)
                .setAction("${BackgroundWire.APP}.SCHEDULE_REMINDER").putExtra("revision", -1L), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            PendingIntent.getBroadcast(context, 9043, Intent(context, CourseReminderReceiver::class.java)
                .setAction("${BackgroundWire.APP}.UNRELATED"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        suspend fun switch(value: Boolean): BackgroundStatus {
            container.settings.setBackgroundEnhancement(value)
            return withTimeout(20_000) {
                // Production must observe the acknowledgement itself; polling refresh here used
                // to hide missed callbacks and stale snapshots after a preference write.
                container.background.state.first { it.hook == if (value) BackgroundHookState.ACTIVE else BackgroundHookState.READY }
            }
        }
        fun exemptions() = BackgroundWire.status(requireNotNull(BackgroundHostConnection.binder.value)).getLong("exemptions")
        fun schedule(intent: PendingIntent) = alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2_000, intent)
        try {
            val disabled = switch(false)
            assertNotEquals(PermissionState.UNKNOWN, disabled.autostart)
            val before = exemptions()
            pending.forEach(::schedule)
            delay(3000)
            assertEquals(before, exemptions())
            pending.forEach(alarms::cancel)
            val active = switch(true)
            assertEquals(disabled.autostart, active.autostart)
            assertEquals(disabled.vendorBattery, active.vendorBattery)
            assertEquals(disabled.batteryExemption, active.batteryExemption)
            schedule(pending.last())
            // Scheduling any alarm can rebatch existing real reminders, so the global counter
            // cannot prove that this unrelated PendingIntent was ignored. Creator/action filtering
            // is covered independently by BackgroundStatusTest without touching the user's alarms.
            val beforeOwn = exemptions()
            pending.take(2).forEach(::schedule)
            // Some ROM policy hooks run at delivery, not during scheduling.
            withTimeout(10_000) { while (exemptions() <= beforeOwn) delay(100) }
            pending.forEach(alarms::cancel)
            switch(false)
            val off = exemptions()
            pending.forEach(::schedule)
            delay(3000)
            assertEquals(off, exemptions())
        } finally {
            pending.forEach { alarms.cancel(it); it.cancel() }
            container.settings.setBackgroundEnhancement(original)
            container.background.refreshNow()
        }
    }
}
