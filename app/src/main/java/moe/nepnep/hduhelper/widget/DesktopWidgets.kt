package moe.nepnep.hduhelper.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.nepnep.hduhelper.HDUHelperApplication
import moe.nepnep.hduhelper.MainActivity
import moe.nepnep.hduhelper.R
import moe.nepnep.hduhelper.data.notifications.CourseReminderRules
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.data.settings.ThemeMode
import moe.nepnep.hduhelper.data.timetable.*

open class DesktopWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        val allowed = setOf(AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_DELETED,
            AppWidgetManager.ACTION_APPWIDGET_ENABLED, AppWidgetManager.ACTION_APPWIDGET_DISABLED,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED, AppWidgetManager.ACTION_APPWIDGET_RESTORED,
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED, "miui.appwidget.action.APPWIDGET_UPDATE", "moe.nepnep.hduhelper.WIDGET_REFRESH", "moe.nepnep.hduhelper.WIDGET_BOUNDARY")
        if (intent.action !in allowed) return
        super.onReceive(context, intent)
        val pending = goAsync()
        val widgets = (context.applicationContext as HDUHelperApplication).desktopWidgets
        widgets.scope.launch {
            try { widgets.refresh() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The next provider update retries; do not log personal arrangements. */ }
            finally { pending.finish() }
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        (context.applicationContext as HDUHelperApplication).desktopWidgets.request()
    }

}
class QuickWidgetProvider : DesktopWidgetProvider()
class DoubleWidgetProvider : DesktopWidgetProvider()
class DayWidgetProvider : DesktopWidgetProvider()

// Standard providers stay available without Xiaomi metadata, root or a loaded hook.
class AndroidQuickWidgetProvider : DesktopWidgetProvider()
class AndroidDoubleWidgetProvider : DesktopWidgetProvider()
class AndroidDayWidgetProvider : DesktopWidgetProvider()

/** Cache-only rendering. Serialized refreshes prevent older account data from winning a race. */
class DesktopWidgets(private val context: Context) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    private val manager = AppWidgetManager.getInstance(context)
    private val snapshotStore = WidgetSnapshotStore(context)
    private val providers = listOf(QuickWidgetProvider::class.java, DoubleWidgetProvider::class.java,
        DayWidgetProvider::class.java, AndroidQuickWidgetProvider::class.java,
        AndroidDoubleWidgetProvider::class.java, AndroidDayWidgetProvider::class.java)
    private val palette = listOf(0xff2196f3.toInt(), 0xff9575cd.toInt(), 0xffef6c35.toInt(), 0xff37a995.toInt(), 0xffcf5183.toInt())

    init {
        scope.launch {
            for (ignored in requests) {
                try { refresh() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Retry on the next update without logging personal data. */ }
            }
        }
    }
    fun request() { requests.trySend(Unit) }

    suspend fun refresh() = mutex.withLock {
        val instances = providers.flatMapIndexed { providerIndex, provider ->
            manager.getAppWidgetIds(ComponentName(context, provider)).map { it to providerIndex }
        }
        val alarm = context.getSystemService(AlarmManager::class.java)
        val alarmIntent = PendingIntent.getBroadcast(context, 7601,
            Intent(context, DesktopWidgetProvider::class.java).setAction("moe.nepnep.hduhelper.WIDGET_BOUNDARY"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (instances.isEmpty()) { alarm.cancel(alarmIntent); return@withLock }
        val now = LocalDateTime.now(campusZone)
        val date = now.toLocalDate()
        var readError: String? = null
        val snapshot = try { snapshotStore.load() } catch (_: Exception) { readError = "小组件缓存读取失败，请打开应用重试"; null }
        val revision = snapshot?.revision
        val identity = snapshot?.account
        val error = readError ?: snapshot?.error
        val book = snapshot?.book ?: ScheduleBook()
        val cached = snapshot?.terms.orEmpty()
        val data = ScheduleRules.termForDate(cached, date) ?: cached.firstOrNull { it.term.key == it.catalog.current.key }
        val warning = listOfNotNull(error, when {
            identity == null -> "登录后可显示课程与考试"
            data == null -> "课表尚未缓存，请打开应用同步"
            else -> data.exams.message?.replace("下拉", "打开应用")
        }).joinToString(" · ")
        val items = AgendaRules.items(book, data, date)
        val pending = AgendaRules.pending(items, now)
        if (runCatching { snapshotStore.load()?.revision }.getOrNull() != revision) { request(); return@withLock }
        val theme = snapshot?.theme ?: ThemeMode.SYSTEM
        val dark = when (theme) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val colors = Colors(if (dark) 0xff1a1a1a.toInt() else 0xfffafafa.toInt(),
            if (dark) 0xff292929.toInt() else 0xffeeeeee.toInt(),
            if (dark) Color.WHITE else 0xff191919.toInt(), if (dark) 0xffaaaaaa.toInt() else 0xff666666.toInt(), automatic = theme == ThemeMode.SYSTEM)
        for ((id, providerIndex) in instances) {
            val kind = providerIndex % 3
            if (runCatching { snapshotStore.load()?.revision }.getOrNull() != revision) { request(); return@withLock }
            val views = shell(date, data, colors)
            renderAgenda(views, kind, pending, items, now, data, colors, warning,
                manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (kind == 2) 350 else 170),
                isXiaomi = providerIndex < 3)
            manager.updateAppWidget(id, views)
        }
        val next = (items.flatMap { listOfNotNull(it.start, it.end) } + date.plusDays(1).atStartOfDay())
            .filter { it > now }.min()
        alarm.setAndAllowWhileIdle(AlarmManager.RTC, next.atZone(campusZone).toInstant().toEpochMilli(), alarmIntent)
    }

    /** Synthetic device previews exercise the same RemoteViews actions without changing user data. */
    internal fun renderPreview(kind: Int, data: TimetableData?, book: ScheduleBook, now: LocalDateTime, dark: Boolean, heightDp: Int = if (kind == 2) 350 else 170, isXiaomi: Boolean = false): RemoteViews {
        val colors = Colors(if (dark) 0xff1a1a1a.toInt() else 0xfffafafa.toInt(),
            if (dark) 0xff292929.toInt() else 0xffeeeeee.toInt(),
            if (dark) Color.WHITE else 0xff191919.toInt(), if (dark) 0xffaaaaaa.toInt() else 0xff666666.toInt())
        val views = shell(now.toLocalDate(), data, colors)
        val items = AgendaRules.items(book, data, now.toLocalDate())
        renderAgenda(views, kind, AgendaRules.pending(items, now), items, now, data, colors, "", heightDp, isXiaomi)
        return views
    }

    private data class Colors(val background: Int, val tile: Int, val text: Int, val secondary: Int, val automatic: Boolean = false) {
        fun resource(value: Int) = when (value) {
            background -> R.color.widget_background
            tile -> R.color.widget_tile
            text -> R.color.widget_text
            else -> R.color.widget_secondary
        }
    }
    private fun color(key: String) = palette[Math.floorMod(key.hashCode(), palette.size)]
    private fun RemoteViews.tint(id: Int, color: Int, colors: Colors) {
        if (colors.automatic) setColorStateList(id, "setBackgroundTintList", colors.resource(color))
        else setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(color))
    }
    private fun RemoteViews.textColor(id: Int, color: Int, colors: Colors) {
        if (colors.automatic) setColor(id, "setTextColor", colors.resource(color))
        else setTextColor(id, color)
    }
    private fun link(host: String, vararg parts: String): Uri = Uri.Builder().scheme("hduhelper").authority(host)
        .apply { parts.forEach { appendPath(it) } }.build()
    private fun open(uri: Uri): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).setData(uri).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun itemLink(item: AgendaItem, date: LocalDate, data: TimetableData?): Uri {
        item.occurrence?.let { return link("schedule", it.seriesId, it.originalDate.toString(), date.toString()) }
        if (data != null) return link(if (item.exam != null) "exam" else "course", CourseReminderRules.hash(data.account),
            data.term.key, item.exam?.id ?: item.course!!.id, date.toString())
        return link("agenda", date.toString())
    }
    private fun shell(date: LocalDate, data: TimetableData?, colors: Colors) = RemoteViews(context.packageName, R.layout.widget_shell).apply {
        tint(android.R.id.background, colors.background, colors)
        setTextViewText(R.id.widget_title, "今天 / ${listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")[date.dayOfWeek.value - 1]}")
        val week = data?.let { ExamRules.weeks(it).firstOrNull { range -> date in range.startDate..range.endDate } }
        setTextViewText(R.id.widget_week, week?.let { if (data.weeks.any { w -> w.week == it.week }) "第${it.week}周" else "考试周" }.orEmpty())
        listOf(R.id.widget_title, R.id.widget_week, R.id.widget_footer).forEach { textColor(it, colors.secondary, colors) }
        setOnClickPendingIntent(android.R.id.background, open(link("agenda", date.toString())))
        removeAllViews(R.id.widget_content)
    }
    private fun renderAgenda(views: RemoteViews, kind: Int, pending: List<AgendaItem>, all: List<AgendaItem>, now: LocalDateTime,
                             data: TimetableData?, colors: Colors, warning: String, heightDp: Int, isXiaomi: Boolean) {
        val limit = if (kind == 0) 1 else if (kind == 1) 2 else 4
        val shown = pending.take(limit)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val compact = heightDp in 1..155 && kind != 2
        if (compact) {
            views.setViewPadding(android.R.id.background, dp(8), dp(4), dp(8), dp(4))
            views.setViewLayoutHeight(R.id.widget_header, 22f, android.util.TypedValue.COMPLEX_UNIT_DIP)
            views.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            views.setViewLayoutHeight(R.id.widget_icon, 16f, android.util.TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutWidth(R.id.widget_icon, 16f, android.util.TypedValue.COMPLEX_UNIT_DIP)
        } else if (kind == 1 || kind == 2) views.setViewPadding(android.R.id.background, dp(12), dp(8), dp(12), dp(8))
        // Xiaomi hosts place these cards close to their rounded edges, even at compact sizes.
        if (isXiaomi) {
            views.setViewPadding(android.R.id.background, dp(16), dp(12), dp(16), dp(12))
        }
        if (shown.isEmpty()) {
            val entry = RemoteViews(context.packageName, R.layout.widget_entry)
            entry.tint(R.id.widget_entry, colors.background, colors)
            entry.setViewVisibility(R.id.widget_time, View.GONE)
            entry.setViewVisibility(R.id.widget_accent, View.GONE)
            entry.setViewVisibility(R.id.widget_status, View.GONE)
            entry.setViewVisibility(R.id.widget_subtitle, View.GONE)
            entry.setInt(R.id.widget_name, "setGravity", android.view.Gravity.CENTER)
            entry.setInt(R.id.widget_name, "setMaxLines", 2)
            entry.setTextViewText(R.id.widget_name, if (warning.isNotBlank()) "暂无可显示的安排" else if (all.isEmpty()) "今日暂无安排" else "今日安排已结束")
            entry.textColor(R.id.widget_name, colors.text, colors)
            views.addView(R.id.widget_content, entry)
        }
        shown.forEach { item ->
            val entry = RemoteViews(context.packageName, R.layout.widget_entry)
            entry.tint(R.id.widget_entry, if (kind == 2) colors.tile else colors.background, colors)
            if (kind == 1 || compact) entry.setViewPadding(R.id.widget_entry, dp(4), 0, dp(4), 0)
            if (kind == 2 && heightDp < 330) entry.setViewPadding(R.id.widget_entry, dp(4), dp(2), dp(4), dp(2))
            if (compact && kind == 1) entry.setTextViewTextSize(R.id.widget_name, android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            entry.setTextViewText(R.id.widget_name, item.title)
            entry.textColor(R.id.widget_name, colors.text, colors)
            entry.setInt(R.id.widget_accent, "setBackgroundColor", color(item.key))
            val time = AgendaRules.time(item, now.toLocalDate())
            entry.setTextViewText(R.id.widget_time, time.replace("–", "\n"))
            entry.textColor(R.id.widget_time, colors.secondary, colors)
            val subtitle = if (kind == 0) listOf(time, item.location).filter { it.isNotBlank() }.joinToString("\n")
                else listOfNotNull(item.course?.let { "第${it.rawSections.removeSuffix("节")}节" },
                    item.location.takeIf { it.isNotBlank() }, item.course?.teacher?.takeIf { it.isNotBlank() }).joinToString(" · ")
            entry.setTextViewText(R.id.widget_subtitle, subtitle)
            entry.textColor(R.id.widget_subtitle, colors.secondary, colors)
            entry.setViewVisibility(R.id.widget_status, if (kind == 2 && item.ongoing(now)) View.VISIBLE else View.GONE)
            val endLabel = item.end?.let { if (it.toLocalDate() == now.toLocalDate()) it.toLocalTime().toString()
                else "${it.monthValue}/${it.dayOfMonth} ${it.toLocalTime()}" }
            entry.setTextViewText(R.id.widget_status, if (item.ongoing(now)) "进行中 · ${endLabel}结束" else "")
            entry.setTextColor(R.id.widget_status, color(item.key))
            if (kind == 0) {
                entry.setViewVisibility(R.id.widget_time, View.GONE)
                entry.setViewVisibility(R.id.widget_accent, View.GONE)
                entry.setInt(R.id.widget_name, "setMaxLines", if (compact) 1 else 2)
                entry.setInt(R.id.widget_subtitle, "setMaxLines", if (compact) 2 else 3)
                entry.setTextViewTextSize(R.id.widget_name, android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 18f else 20f)
            }
            entry.setOnClickPendingIntent(R.id.widget_entry, open(itemLink(item, now.toLocalDate(), data)))
            views.addView(R.id.widget_content, entry)
        }
        val more = (pending.size - shown.size).takeIf { it > 0 }?.let { "其他${it}项安排" }
        val footer = listOfNotNull(more, warning.takeIf { it.isNotBlank() }).joinToString(" · ")
        views.setViewPadding(R.id.widget_footer, 0, if (kind == 2 && footer.isNotBlank()) dp(6) else 0, 0, 0)
        views.setTextViewText(R.id.widget_footer, footer)
    }

}
