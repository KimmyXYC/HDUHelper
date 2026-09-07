package moe.nepnep.hduhelper.data.schedule

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.nepnep.hduhelper.HDUHelperApplication
import moe.nepnep.hduhelper.MainActivity
import moe.nepnep.hduhelper.R

interface ScheduleReminderScheduler {
    fun reschedule()
    fun status(): String?
}

class AndroidScheduleReminders(private val context: Context, private val repository: ScheduleRepository) : ScheduleReminderScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    init {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "日程提醒", NotificationManager.IMPORTANCE_HIGH))
    }

    fun notificationsAllowed(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    override fun status(): String? = when {
        !notificationsAllowed() -> "通知未开启，提醒不可用"
        !alarms.canScheduleExactAlarms() -> "未开启精确提醒，可能延迟"
        else -> null
    }

    private fun pending(at: Long = 0, revision: Long = 0): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ScheduleAlarmReceiver::class.java).setAction(ACTION).putExtra("at", at).putExtra("revision", revision),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun reschedule() { scope.launch { runCatching { reconcile() } } }

    suspend fun reconcile() = mutex.withLock {
        repository.load()
        alarms.cancel(pending())
        if (!notificationsAllowed()) return@withLock
        val book = repository.state.value.book
        val after = maxOf(System.currentTimeMillis(), book.deliveredThrough)
        val next = book.series.mapNotNull { ScheduleRules.nextReminder(it, after) }.minByOrNull { ScheduleRules.reminderTime(it.event)!! }
            ?: return@withLock
        val at = ScheduleRules.reminderTime(next.event)!!
        val intent = pending(at, book.revision)
        if (alarms.canScheduleExactAlarms()) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }
            catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }
        } else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    suspend fun fire(at: Long, revision: Long) {
        try {
            mutex.withLock {
                if (at <= 0 || at > System.currentTimeMillis() || !notificationsAllowed()) return@withLock
                for (occurrence in repository.claimReminder(revision, at)) {
                    val open = Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.Builder().scheme("hduhelper").authority("schedule").appendPath(occurrence.seriesId)
                            .appendPath(occurrence.originalDate.toString()).appendPath(occurrence.event.startTime.toLocalDate().toString()).build())
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    val content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val event = occurrence.event
                    val time = if (event.allDay) "全天" else event.startTime.toLocalTime().toString()
                    val notification = NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_schedule_notification)
                        .setContentTitle(event.title)
                        .setContentText(listOf(time, event.location).filter { it.isNotBlank() }.joinToString(" · "))
                        .setContentIntent(content).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_EVENT)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                    try { notifications.notify(occurrence.key, 1, notification) } catch (_: SecurityException) { /* Permission changed while delivering. */ }
                }
            }
        } finally { reconcile() }
    }

    companion object {
        const val CHANNEL = "schedule_reminders"
        const val ACTION = "moe.nepnep.hduhelper.SCHEDULE_REMINDER"
    }
}

class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidScheduleReminders.ACTION) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reminders = (context.applicationContext as HDUHelperApplication).container.scheduleReminders
                reminders.fire(intent.getLongExtra("at", 0), intent.getLongExtra("revision", -1))
            } catch (_: Exception) { /* Retain stored schedules; retry scheduling on foreground. */ }
            finally { pending.finish() }
        }
    }
}

class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { (context.applicationContext as HDUHelperApplication).container.scheduleReminders.reconcile() }
            catch (_: Exception) { /* Unavailable Keystore/storage will be retried after unlocking/foreground. */ }
            finally { pending.finish() }
        }
    }
}
