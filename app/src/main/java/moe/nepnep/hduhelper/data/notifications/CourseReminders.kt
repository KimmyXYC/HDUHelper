package moe.nepnep.hduhelper.data.notifications

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.nepnep.hduhelper.AppContainer
import moe.nepnep.hduhelper.HDUHelperApplication
import moe.nepnep.hduhelper.MainActivity
import moe.nepnep.hduhelper.R
import moe.nepnep.hduhelper.data.timetable.campusZone

data class CourseNotificationStatus(
    val notifications: Boolean = false,
    val exact: Boolean = false,
    val promoted: Boolean = false,
    val promotionSupported: Boolean = Build.VERSION.SDK_INT >= 36,
    val message: String? = null,
)

class AndroidCourseReminders(private val context: Context, private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val store = CourseReminderJournalStore(File(context.noBackupFilesDir, "course-reminders.json"))
    private var journal: ReminderJournal? = null
    private var serviceTick: Job? = null
    private val mutableStatus = MutableStateFlow(CourseNotificationStatus())
    val status = mutableStatus.asStateFlow()

    init {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "课程提醒", NotificationManager.IMPORTANCE_HIGH))
        scope.launch { container.settings.state.map { it.notifications }.distinctUntilChanged().collect { reconcileSafely() } }
        scope.launch { container.auth.sessionGeneration.collect { reconcileSafely() } }
    }

    fun foreground() { reschedule() }
    fun reschedule() { scope.launch { reconcileSafely() } }

    fun permissionStatus(message: String? = status.value.message): CourseNotificationStatus = CourseNotificationStatus(
        notifications = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE,
        exact = alarms.canScheduleExactAlarms(),
        promoted = Build.VERSION.SDK_INT >= 36 && notifications.canPostPromotedNotifications(),
        message = message,
    )

    private fun read(): ReminderJournal {
        journal?.let { return it }
        // Do not discard corrupt records and accidentally replay old reminders.
        return store.read().also { journal = it }
    }

    private fun save(value: ReminderJournal) { store.save(value); journal = value }

    private fun pending(token: String = "", at: Long = 0) = PendingIntent.getBroadcast(context, 0,
        Intent(context, CourseReminderReceiver::class.java).setAction(ACTION).putExtra("token", token).putExtra("at", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    suspend fun reconcileSafely(token: String? = null, alarmAt: Long = 0) {
        try { reconcile(token, alarmAt) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableStatus.value = permissionStatus("课程提醒调度失败，请重新打开应用重试") }
    }

    private suspend fun reconcile(token: String?, alarmAt: Long) = mutex.withLock {
        if (!context.getSystemService(android.os.UserManager::class.java).isUserUnlocked) return@withLock
        var book = read()
        if (token != null && !book.accepts(token, alarmAt)) return@withLock
        container.auth.initialize() // Local encrypted session only; never starts a network login.
        val identity = container.auth.serviceIdentity()
        val latest = identity?.let { container.timetables.cached(it.account) }
        val data = latest?.let { if (it.term.key == it.catalog.current.key) it else container.timetables.cached(it.account, it.catalog.current) }
        if (identity != null && !container.auth.isCurrent(identity)) { reschedule(); return@withLock }
        val now = System.currentTimeMillis()
        // Application startup can reconcile before a cold-start alarm receiver gets CPU time.
        // Claim an overdue persisted alarm here as well; its later receiver will be stale.
        val dueAt = book.dueAlarmAt(now)
        val prefs = container.settings.state.value.notifications
        val permissions = permissionStatus(null)
        mutableStatus.value = permissions.copy(message = when {
            identity == null -> "登录并读取当前学期课表后，即可接收课程提醒"
            data == null -> "尚无当前学期课表，请打开日程或课表刷新"
            else -> null
        })
        val all = if (data != null && permissions.notifications) CourseReminderRules.reminders(data, prefs, now) else emptyList()
        val validKeys = all.map { it.key }.toSet()
        val owner = identity?.let { CourseReminderRules.hash(it.account) }
        if (book.owner != owner) book = book.copy(records = emptyMap())
        val active = if (prefs.live) CourseReminderDelivery.active(all, book.records, now) else emptyList()
        val ordinary = if (!prefs.live && dueAt != null) CourseReminderDelivery.ordinaryDue(all, book.records, now, dueAt) else emptyList()
        val updated = book.reconcile(owner, validKeys, active.map { it.key }.toSet(), prefs.live, now)
        for ((key, id) in book.posted) if (key !in updated.posted) notifications.cancel(id)
        book = updated
        val liveNotifications = mutableListOf<Pair<Int, Notification>>()
        for (reminder in active + ordinary) {
            val id = book.posted[reminder.key] ?: book.nextId
            val record = book.records[reminder.key]
            // Only the alarm starting a previously undelivered window may make a sound.
            val alert = dueAt != null && reminder.begins >= dueAt && record?.delivered != true
            val notification = buildNotification(reminder, id, prefs.live, !alert, now)
            book = book.copy(
                records = book.records + (reminder.key to CourseReminderRecord(reminder.expires, delivered = true)),
                posted = book.posted + (reminder.key to id),
                nextId = if (id == book.nextId) id + 1 else book.nextId,
            )
            // Commit the claim before publishing, so receiver retries cannot alert twice.
            save(book)
            notifications.notify(id, notification)
            if (prefs.live) liveNotifications += id to notification
        }
        if (identity != null && !container.auth.isCurrent(identity)) {
            book.posted.values.forEach(notifications::cancel)
            save(book.copy(posted = emptyMap(), records = emptyMap()))
            stopLiveService()
            reschedule()
            return@withLock
        }
        alarms.cancel(pending())
        serviceTick?.cancel()
        serviceTick = null
        val future = all.filter { book.records[it.key]?.dismissed != true }
        val next = CourseReminderDelivery.nextBoundary(future, now, prefs.live)
        val newToken = UUID.randomUUID().toString()
        book = book.copy(token = newToken, alarmAt = next ?: 0)
        save(book)
        if (next != null) {
            val intent = pending(newToken, next)
            if (permissions.exact) {
                try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent) }
                catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent) }
            } else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
        }
        if (liveNotifications.isEmpty()) stopLiveService()
        else {
            val (id, notification) = liveNotifications.first()
            val serviceIntent = Intent(context, CourseLiveService::class.java).putExtra("id", id).putExtra("notification", notification)
                .putExtra("until", active.maxOf { it.expires })
            try {
                if (CourseLiveService.running) context.startService(serviceIntent)
                else ContextCompat.startForegroundService(context, serviceIntent)
            } catch (_: IllegalStateException) {
                mutableStatus.value = permissionStatus("后台运行受限，实时状态切换可能延迟")
            } catch (_: SecurityException) {
                mutableStatus.value = permissionStatus("后台运行受限，实时状态切换可能延迟")
            }
            if (next != null) serviceTick = scope.launch {
                delay((next - System.currentTimeMillis()).coerceAtLeast(1))
                // The service transition is also a due event when windows overlap.
                reconcileSafely(newToken, next)
            }
        }
    }

    private fun stopLiveService() { context.stopService(Intent(context, CourseLiveService::class.java)) }

    suspend fun dismiss(key: String) = mutex.withLock {
        val book = read()
        val record = book.records[key] ?: return@withLock
        book.posted[key]?.let(notifications::cancel)
        save(book.copy(records = book.records + (key to record.copy(dismissed = true)), posted = book.posted - key))
        reschedule()
    }

    internal fun serviceStopped() {
        scope.launch { mutex.withLock {
            if (!CourseLiveService.running) { serviceTick?.cancel(); serviceTick = null }
        } }
    }

    internal fun buildNotification(reminder: CourseReminder, id: Int, live: Boolean, silent: Boolean, now: Long): Notification {
        val link = Uri.Builder().scheme("hduhelper").authority("course").appendPath(reminder.accountKey)
            .appendPath(reminder.termKey).appendPath(reminder.meeting.id).appendPath(reminder.date.toString()).build()
        val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(link)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, id, Intent(context, CourseReminderReceiver::class.java)
            .setAction(DISMISS).setData("hduhelper://dismiss/${reminder.key}".toUri()), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val elapsed = reminder.phase(now) == CourseReminderPhase.ELAPSED
        val label = if (elapsed) "已${reminder.kind.label}" else "距${reminder.kind.label}"
        val time = Instant.ofEpochMilli(reminder.target).atZone(campusZone).toLocalTime().toString()
        val description = listOf(reminder.meeting.location, "$time ${reminder.kind.label}").filter { it.isNotBlank() }.joinToString(" · ")
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_schedule_notification).setContentTitle("${reminder.meeting.name} · ${if (live) label else reminder.kind.label + "提醒"}")
            .setContentText(description).setContentIntent(content).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_EVENT).setOnlyAlertOnce(true).setAutoCancel(!live)
        if (silent) builder.setSilent(true)
        if (live) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).setOngoing(true).setWhen(reminder.target).setShowWhen(true).setUsesChronometer(true)
                .setChronometerCountDown(!elapsed).setTimeoutAfter((reminder.expires - now).coerceAtLeast(1))
                .setDeleteIntent(dismiss).addAction(0, "关闭本次提醒", dismiss)
            if (Build.VERSION.SDK_INT >= 36) builder.setRequestPromotedOngoing(true)
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL = "course_reminders"
        const val ACTION = "moe.nepnep.hduhelper.COURSE_REMINDER"
        const val DISMISS = "moe.nepnep.hduhelper.COURSE_REMINDER_DISMISS"
    }
}

class CourseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(AndroidCourseReminders.ACTION, AndroidCourseReminders.DISMISS)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reminders = (context.applicationContext as HDUHelperApplication).container.courseReminders
                if (intent.action == AndroidCourseReminders.DISMISS) intent.data?.lastPathSegment?.let { reminders.dismiss(it) }
                else intent.getStringExtra("token")?.let { reminders.reconcileSafely(it, intent.getLongExtra("at", 0)) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep persisted claims; retry on foreground without replaying alerts. */ }
            finally { pending.finish() }
        }
    }
}
