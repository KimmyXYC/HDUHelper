package moe.nepnep.hduhelper.data.notifications

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.drawable.Icon
import moe.nepnep.hduhelper.data.island.CourseIslandTemplate
import moe.nepnep.hduhelper.data.island.IslandCapability
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
import moe.nepnep.hduhelper.data.timetable.CourseMeeting

data class CourseNotificationStatus(
    val notifications: Boolean = false,
    val exact: Boolean = false,
    val island: IslandCapability = IslandCapability(),
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
    private var testReminder: CourseReminder? = null
    private var testAlerted = false
    private val mutableStatus = MutableStateFlow(CourseNotificationStatus())
    val status = mutableStatus.asStateFlow()

    init {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "课程提醒", NotificationManager.IMPORTANCE_HIGH))
        scope.launch { container.settings.state.map { it.notifications }.distinctUntilChanged().collect { reconcileSafely() } }
        scope.launch { container.island.state.collect { reconcileSafely() } }
        scope.launch { container.auth.sessionGeneration.collect { reconcileSafely() } }
    }

    fun foreground() { container.island.refresh(); reschedule() }
    fun reschedule() { scope.launch { reconcileSafely() } }

    suspend fun sendTestNotification(): Boolean = withContext(Dispatchers.IO) {
        try {
            val capability = container.island.refreshNow()
            val island = mutex.withLock {
                if (!permissionStatus().notifications) return@withContext false
                val now = System.currentTimeMillis()
                val reminder = CourseReminder(
                    "notification-test-${UUID.randomUUID()}", "", "",
                    CourseMeeting("notification-test", "notification-test", "测试课程", location = "测试教室", weekday = 1,
                        sections = listOf(1), weeks = listOf(1), rawWeeks = "1", rawSections = "1"),
                    Instant.ofEpochMilli(now).atZone(campusZone).toLocalDate(), CourseReminderKind.START,
                    target = now + 60_000, begins = now, courseStart = now + 60_000, courseEnd = now + 120_000,
                )
                val island = container.settings.state.value.notifications.island && capability.ready
                testReminder = reminder.takeIf { island }
                testAlerted = false
                notifications.cancel(TEST_ORDINARY_ID)
                if (!island) notifications.notify(TEST_ORDINARY_ID,
                    buildNotification(reminder, TEST_ORDINARY_ID, false, false, now, test = true))
                island
            }
            reconcile(null, 0)
            !island || mutex.withLock { testReminder != null && testAlerted }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    fun permissionStatus(message: String? = status.value.message): CourseNotificationStatus = CourseNotificationStatus(
        notifications = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE,
        exact = alarms.canScheduleExactAlarms(),
        island = container.island.state.value,
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
        if (book.legacyPosted.isNotEmpty()) {
            book.legacyPosted.forEach(notifications::cancel)
            book = book.copy(legacyPosted = emptyList())
            save(book)
        }
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
        container.island.refreshNow()
        val permissions = permissionStatus(null)
        val useIsland = prefs.island && permissions.island.ready
        mutableStatus.value = permissions.copy(message = when {
            identity == null -> "登录并读取当前学期课表后，即可接收课程提醒"
            data == null -> "尚无当前学期课表，请打开日程或课表刷新"
            else -> null
        })
        val all = if (data != null && permissions.notifications) CourseReminderRules.reminders(data, prefs, now) else emptyList()
        val validKeys = all.map { it.key }.toSet()
        val owner = identity?.let { CourseReminderRules.hash(it.account) }
        if (book.owner != owner) book = book.copy(records = emptyMap())
        val active = if (useIsland) CourseReminderDelivery.active(all, book.records, now) else emptyList()
        val fallback = if (!useIsland && book.island) all.filter { it.key in book.posted && it.phase(now) != CourseReminderPhase.EXPIRED } else emptyList()
        val ordinary = ((if (!useIsland && dueAt != null) CourseReminderDelivery.ordinaryDue(all, book.records, now, dueAt) else emptyList()) + fallback).distinctBy { it.key }
        val updated = book.reconcile(owner, validKeys, active.map { it.key }.toSet(), useIsland, now).let {
            it.copy(posted = it.posted + book.posted.filterKeys { key -> fallback.any { f -> f.key == key } })
        }
        for ((key, id) in book.posted) if (key !in updated.posted) notifications.cancel(id)
        book = updated
        val liveNotifications = mutableListOf<Pair<Int, Notification>>()
        for (reminder in active + ordinary) {
            val id = book.posted[reminder.key] ?: book.nextId
            val record = book.records[reminder.key]
            // Only the alarm starting a previously undelivered window may make a sound.
            val alert = dueAt != null && reminder.begins >= dueAt && record?.delivered != true
            val notification = buildNotification(reminder, id, useIsland, !alert, now)
            book = book.copy(
                records = book.records + (reminder.key to CourseReminderRecord(reminder.expires, delivered = true)),
                posted = book.posted + (reminder.key to id),
                nextId = if (id == book.nextId) id + 1 else book.nextId,
            )
            // Commit the claim before publishing, so receiver retries cannot alert twice.
            save(book)
            notifications.notify(id, notification)
            if (useIsland) liveNotifications += id to notification
        }
        val preview = testReminder?.takeIf { permissions.notifications && useIsland && now < it.expires }
        if (preview == null) {
            testReminder?.takeIf { permissions.notifications && !useIsland && now < it.expires }?.let {
                notifications.notify(TEST_ORDINARY_ID, buildNotification(it, TEST_ORDINARY_ID, false, true, now, test = true))
            }
            testReminder = null
            notifications.cancel(TEST_ISLAND_ID)
        } else {
            val notification = buildNotification(preview, TEST_ISLAND_ID, true, testAlerted, now, test = true)
            notifications.notify(TEST_ISLAND_ID, notification)
            testAlerted = true
            liveNotifications += TEST_ISLAND_ID to notification
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
        val next = listOfNotNull(
            CourseReminderDelivery.nextBoundary(future, now, useIsland),
            preview?.let { if (now < it.target) it.target else it.expires },
        ).minOrNull()
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
            val serviceIntent = Intent(context, CourseIslandService::class.java).putExtra("id", id).putExtra("notification", notification)
                .putExtra("until", (active + listOfNotNull(preview)).maxOf { it.expires })
            try {
                if (CourseIslandService.running) context.startService(serviceIntent)
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

    private fun stopLiveService() { context.stopService(Intent(context, CourseIslandService::class.java)) }

    suspend fun dismiss(key: String) = mutex.withLock {
        if (testReminder?.key == key) {
            testReminder = null
            notifications.cancel(TEST_ISLAND_ID)
            reschedule()
            return@withLock
        }
        val book = read()
        val record = book.records[key] ?: return@withLock
        book.posted[key]?.let(notifications::cancel)
        save(book.copy(records = book.records + (key to record.copy(dismissed = true)), posted = book.posted - key))
        reschedule()
    }

    internal fun serviceStopped() {
        scope.launch { mutex.withLock {
            if (!CourseIslandService.running) { serviceTick?.cancel(); serviceTick = null }
        } }
    }

    internal fun buildNotification(reminder: CourseReminder, id: Int, island: Boolean, silent: Boolean, now: Long, test: Boolean = false): Notification {
        val link = Uri.Builder().scheme("hduhelper").authority("course").appendPath(reminder.accountKey)
            .appendPath(reminder.termKey).appendPath(reminder.meeting.id).appendPath(reminder.date.toString()).build()
        val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(link.takeUnless { test })
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, id, Intent(context, CourseReminderReceiver::class.java)
            .setAction(DISMISS).setData("hduhelper://dismiss/${reminder.key}".toUri()), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val elapsed = reminder.phase(now) == CourseReminderPhase.ELAPSED
        val label = if (elapsed) "已${reminder.kind.label}" else "距${reminder.kind.label}"
        val time = Instant.ofEpochMilli(reminder.target).atZone(campusZone).toLocalTime().toString()
        val description = listOf(reminder.meeting.location, "$time ${reminder.kind.label}").filter { it.isNotBlank() }.joinToString(" · ")
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_schedule_notification).setContentTitle("${reminder.meeting.name} · ${if (island) label else reminder.kind.label + "提醒"}")
            .setContentText(description).setContentIntent(content).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_EVENT).setOnlyAlertOnce(true).setAutoCancel(!island)
        if (silent) builder.setSilent(true)
        if (island) {
            val capability = container.island.state.value
            val canMute = now < reminder.courseEnd && capability.muteSupported
            val owned = reminder.key in capability.muteKeys
            val alreadySilent = capability.ringerSilent && !capability.mutedByModule
            val title = when {
                !canMute -> "查看课程"
                alreadySilent -> "已静音"
                owned -> "解除静音"
                else -> "上课静音"
            }
            val mute = PendingIntent.getBroadcast(context, id, Intent(context, CourseReminderReceiver::class.java)
                .setAction(MUTE).setData("hduhelper://mute/${reminder.key}".toUri())
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("end", reminder.courseEnd),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val action = Notification.Action.Builder(null, title, if (canMute && !alreadySilent) mute else content).build()
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true).setShowWhen(false).setTimeoutAfter((reminder.expires - now).coerceAtLeast(1))
                .setDeleteIntent(dismiss).addAction(0, "关闭本次提醒", dismiss)
                .addExtras(Bundle().apply {
                    putString(CourseIslandTemplate.PARAM, CourseIslandTemplate.json(reminder, now))
                    putBundle("miui.focus.pics", Bundle().apply {
                        putParcelable(CourseIslandTemplate.ICON, Icon.createWithResource(context, R.mipmap.ic_launcher))
                    })
                    putBundle("miui.focus.actions", Bundle().apply { putParcelable(CourseIslandTemplate.ACTION, action) })
                })
        }
        return builder.build()
    }

    companion object {
        internal const val TEST_ORDINARY_ID = -2001
        internal const val TEST_ISLAND_ID = -2002
        const val CHANNEL = "course_reminders"
        const val ACTION = "moe.nepnep.hduhelper.COURSE_REMINDER"
        const val MUTE = "moe.nepnep.hduhelper.COURSE_ISLAND_MUTE"
        const val DISMISS = "moe.nepnep.hduhelper.COURSE_REMINDER_DISMISS"
    }
}

class CourseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(AndroidCourseReminders.ACTION, AndroidCourseReminders.DISMISS, AndroidCourseReminders.MUTE)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as HDUHelperApplication).container
                val reminders = container.courseReminders
                if (intent.action == AndroidCourseReminders.MUTE) {
                    intent.data?.lastPathSegment?.let { container.island.toggleMute(it, intent.getLongExtra("end", 0)) }
                    reminders.reschedule()
                } else if (intent.action == AndroidCourseReminders.DISMISS) intent.data?.lastPathSegment?.let { reminders.dismiss(it) }
                else intent.getStringExtra("token")?.let { reminders.reconcileSafely(it, intent.getLongExtra("at", 0)) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep persisted claims; retry on foreground without replaying alerts. */ }
            finally { pending.finish() }
        }
    }
}
