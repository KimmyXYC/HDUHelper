package moe.nepnep.hduhelper

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.data.timetable.*
import moe.nepnep.hduhelper.widget.*
import org.junit.Assert.*
import org.junit.Test

/** Uses synthetic arrangements and an isolated widget host; never overwrites the user's schedule. */
class WidgetDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val widgets get() = (context.applicationContext as HDUHelperApplication).desktopWidgets
    private fun onMain(block: () -> Unit) {
        val task = java.util.concurrent.FutureTask<Unit> { block() }
        android.os.Handler(android.os.Looper.getMainLooper()).post(task)
        try { task.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        catch (e: java.util.concurrent.TimeoutException) {
            throw AssertionError("Widget UI task timed out: " + android.os.Looper.getMainLooper().thread.stackTrace.joinToString("\n"), e)
        }
    }
    private val providers = listOf(QuickWidgetProvider::class.java, DoubleWidgetProvider::class.java,
        DayWidgetProvider::class.java)
    private val term = AcademicTerm("2026", "3")
    private val now = LocalDateTime.parse("2026-09-08T08:30")
    private fun sample(): TimetableData {
        val clocks = listOf(CampusClock("1", "下沙", (1..12).map { section ->
            val minute = 8 * 60 + (section - 1) * 55
            CampusPeriod(section, "%02d:%02d".format(minute / 60, minute % 60),
                "%02d:%02d".format((minute + 45) / 60, (minute + 45) % 60), "")
        }))
        val courses = (1..7).flatMap { day -> (0..4).map { i ->
            CourseMeeting("$day/$i", "c$i", listOf("大学英语", "高等数学", "数字逻辑", "计算机科学导论", "高级语言程序设计")[i],
                teacher = "测试老师", location = "第${day}教学楼 217", campusId = "1", weekday = day,
                sections = listOf(i * 2 + 1, i * 2 + 2), weeks = listOf(1), rawWeeks = "1", rawSections = "${i * 2 + 1}-${i * 2 + 2}")
        } }
        return TimetableData("synthetic", term, TimetableCatalog(listOf("2026"), emptyList(), term), courses,
            emptyList(), listOf(WeekRange(1, "2026-09-07", "2026-09-13")), clocks, 1,
            exams = ExamSnapshot(listOf(ExamArrangement("e", "数学考试", start = "2026-09-09T10:00", end = "2026-09-09T11:30", location = "测试考场")), 1))
    }
    private fun texts(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
        (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())

    @Test fun standardProvidersAreAlwaysAvailableWithoutXiaomiMetadata() {
        val manager = AppWidgetManager.getInstance(context)
        for (provider in listOf(AndroidQuickWidgetProvider::class.java, AndroidDoubleWidgetProvider::class.java, AndroidDayWidgetProvider::class.java)) {
            val component = ComponentName(context, provider)
            assertTrue(manager.installedProviders.any { it.provider == component })
            val receiver = context.packageManager.getReceiverInfo(component, android.content.pm.PackageManager.GET_META_DATA)
            assertTrue(receiver.enabled)
            assertFalse(receiver.metaData.containsKey("miuiWidget"))
        }
    }

    @Test fun allThreeRemoteViewsInflateAtDefaultAndExpandedSizesInBothThemes() {
        val manager = AppWidgetManager.getInstance(context)
        val output = File(context.getExternalFilesDir(null), "widget-tests").apply { mkdirs() }
        for (isXiaomi in listOf(false, true)) for (dark in listOf(false, true)) for (kind in 0..2) for (size in listOf("compact", "default", "expanded")) {
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("widgetCase", "$kind/$dark/$size/xiaomi=$isXiaomi") })
            onMain {
                val info = manager.installedProviders.first { it.provider == ComponentName(context, providers[kind]) }
                val host = AppWidgetHostView(context)
                host.setAppWidget(0, info)
                // Xiaomi cards fill their bounds without the standard Android host padding.
                if (isXiaomi) host.setPadding(0, 0, 0, 0)
                val heightDp = if (kind == 2) when (size) { "compact" -> 300; "expanded" -> 410; else -> 350 }
                    else when (size) { "compact" -> 140; "expanded" -> 230; else -> 170 }
                host.updateAppWidget(widgets.renderPreview(kind, sample(), ScheduleBook(), now, dark, heightDp, isXiaomi))
                val density = context.resources.displayMetrics.density
                val width = (if (kind == 0) when (size) { "compact" -> 150; "expanded" -> 230; else -> 170 }
                    else when (size) { "compact" -> 300; "expanded" -> 410; else -> 350 }) * density
                val height = heightDp * density
                host.measure(View.MeasureSpec.makeMeasureSpec(width.toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height.toInt(), View.MeasureSpec.EXACTLY))
                host.layout(0, 0, width.toInt(), height.toInt())
                assertFalse("RemoteViews host rejected layout: ${texts(host)}", texts(host).any { it.contains("Problem loading") || it.contains("无法加载") })
                assertNotNull(host.findViewById<View>(android.R.id.background))
                assertTrue(texts(host).any { it.contains("大学英语") })
                fun checkEntry(view: View) {
                    if (view.id == R.id.widget_entry && view is ViewGroup) {
                        val time = view.findViewById<TextView>(R.id.widget_time)
                        assertEquals(time.paint.measureText("09:50"), time.paint.measureText("11:30"), 0.01f)
                        assertEquals(android.view.Gravity.END, time.gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
                        val subtitle = view.findViewById<TextView>(R.id.widget_subtitle)
                        if (subtitle.text.isNotBlank()) {
                            val bounds = android.graphics.Rect(0, 0, subtitle.width, subtitle.height)
                            view.offsetDescendantRectToMyCoords(subtitle, bounds)
                            assertTrue("Subtitle clipped for kind $kind: $bounds / ${view.height}", bounds.bottom <= view.height)
                            assertTrue("Subtitle has no room for a full line", subtitle.height >= subtitle.lineHeight)
                        }
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) checkEntry(view.getChildAt(i))
                }
                checkEntry(host)
                if (kind == 2) assertTrue(texts(host).any { it.contains("进行中") })
                val bitmap = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
                host.draw(Canvas(bitmap))
                File(output, "widget-$kind-${if (dark) "dark" else "light"}-$size${if (isXiaomi) "-xiaomi" else ""}.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    @Test fun emptyAndFinishedStatesInflateWithoutStaleRows() {
        onMain {
            val parent = android.widget.FrameLayout(context)
            val empty = widgets.renderPreview(0, null, ScheduleBook(), now, false).apply(context, parent)
            assertTrue(texts(empty).contains("今日暂无安排"))
            empty.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            empty.layout(0, 0, 500, 500)
            val name = empty.findViewById<TextView>(R.id.widget_name)
            val entry = empty.findViewById<ViewGroup>(R.id.widget_entry)
            val bounds = android.graphics.Rect(0, 0, name.width, name.height)
            entry.offsetDescendantRectToMyCoords(name, bounds)
            assertEquals(entry.width / 2f, bounds.exactCenterX(), 1f)
            assertEquals(entry.height / 2f, bounds.exactCenterY(), 1f)
            assertEquals(android.view.Gravity.CENTER, name.gravity)
            assertEquals(View.GONE, empty.findViewById<View>(R.id.widget_subtitle).visibility)
            val book = ScheduleBook(listOf(ScheduleSeries("ended", ScheduleEvent(title = "已结束", start = "2026-09-08T07:00", end = "2026-09-08T08:00"))))
            val finished = widgets.renderPreview(0, null, book, now, false).apply(context, parent)
            assertTrue(texts(finished).contains("今日安排已结束"))
            assertFalse(texts(finished).contains("已结束"))
        }
    }

    @Test fun installedProvidersCanBindAndRefreshInAnIndependentHost() {
        val manager = AppWidgetManager.getInstance(context)
        val updates = java.util.concurrent.atomic.AtomicInteger()
        val host = object : AppWidgetHost(context, 7641) {
            override fun onCreateView(context: android.content.Context, appWidgetId: Int,
                                      info: android.appwidget.AppWidgetProviderInfo): AppWidgetHostView = object : AppWidgetHostView(context) {
                override fun updateAppWidget(views: android.widget.RemoteViews?) {
                    super.updateAppWidget(views)
                    if (views != null) updates.incrementAndGet()
                }
            }
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            onMain { host.startListening() }
            for (provider in providers) {
                val id = host.allocateAppWidgetId()
                assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, provider)))
                assertEquals(provider.name, manager.getAppWidgetInfo(id).provider.className)
                onMain { host.createView(context, id, manager.getAppWidgetInfo(id)) }
            }
            val packageManager = context.packageManager
            val expected = listOf(110 to 110, 300 to 110, 300 to 250)
            providers.forEachIndexed { index, provider ->
                val component = ComponentName(context, provider)
                val info = packageManager.getReceiverInfo(component, android.content.pm.PackageManager.GET_META_DATA)
                assertEquals("${context.packageName}:widgetProvider", info.processName)
                assertTrue(info.metaData.getBoolean("miuiWidget"))
                assertEquals("exposure", info.metaData.getString("miuiWidgetRefresh"))
                val xml = info.loadXmlMetaData(packageManager, "android.appwidget.provider")
                while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { /* seek root */ }
                val ns = "http://schemas.android.com/apk/res/android"
                assertEquals("${expected[index].first}.0dip", xml.getAttributeValue(ns, "minWidth"))
                assertEquals("${expected[index].second}.0dip", xml.getAttributeValue(ns, "minHeight"))
                xml.close()
                context.sendBroadcast(android.content.Intent("miui.appwidget.action.APPWIDGET_UPDATE").setComponent(component))
            }
            assertFalse(manager.installedProviders.any { it.provider.className.endsWith(".GridWidgetProvider") && it.provider.packageName == context.packageName })
            val before = updates.get()
            context.sendBroadcast(android.content.Intent("miui.appwidget.action.APPWIDGET_UPDATE")
                .setComponent(ComponentName(context, QuickWidgetProvider::class.java)))
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (updates.get() <= before && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertTrue("The independent widget process did not deliver an exposure refresh", updates.get() > before)
        } finally {
            host.stopListening()
            host.deleteHost()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            widgets.request()
        }
    }
    @Test fun requestDesktopPinWhenExplicitlySelected() {
        val kind = InstrumentationRegistry.getArguments().getString("pinWidget")?.toIntOrNull()
        org.junit.Assume.assumeTrue("Pinning is opt-in", kind != null && kind in 0..2)
        assertTrue(AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, providers[kind!!]), null, null))
    }

}
