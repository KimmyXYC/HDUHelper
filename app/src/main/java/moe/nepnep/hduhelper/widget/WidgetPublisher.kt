package moe.nepnep.hduhelper.widget

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import moe.nepnep.hduhelper.AppContainer
import moe.nepnep.hduhelper.data.schedule.ScheduleBook

/** Main-process writer. The widget receiver and renderer do not depend on the application graph. */
class WidgetPublisher(private val context: Context, private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val snapshotStore = WidgetSnapshotStore(context)
    init {
        scope.launch {
            for (ignored in requests) {
                try { publish() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The next application change retries without logging personal data. */ }
            }
        }
    }
    fun start() {
        val app = container
        context.deleteSharedPreferences("desktop_widgets") // Removed overview pagination state.
        scope.launch {
            combine(app.auth.state, app.auth.sessionGeneration, app.settings.state) { _, _, _ -> Unit }
                .collect { request() }
        }
    }
    fun request() { requests.trySend(Unit) }
    fun clearSnapshot() { snapshotStore.clear(); request() }

    private suspend fun publish() {
        val app = container
        app.auth.initialize()
        val identity = app.auth.serviceIdentity()
        var error: String? = null
        val book = try { app.schedules.load(); app.schedules.state.value.book }
            catch (_: Exception) { error = "本地日程读取失败，请打开应用重试"; ScheduleBook() }
        val cached = try { identity?.let { app.timetables.cachedTerms(it.account) }.orEmpty() }
            catch (_: Exception) { error = listOfNotNull(error, "课表读取失败，请打开应用重试").joinToString(" · "); emptyList() }
        if (app.auth.serviceIdentity() != identity) { request(); return }
        snapshotStore.save(WidgetSnapshot(account = identity?.account, book = book, terms = cached,
            theme = app.settings.state.value.theme, error = error))
        if (app.auth.serviceIdentity() != identity) { snapshotStore.clear(); request(); return }
        context.sendBroadcast(Intent(context, DesktopWidgetProvider::class.java).setAction("moe.nepnep.hduhelper.WIDGET_REFRESH"))
    }

}
