package moe.nepnep.hduhelper

import android.app.Application
import moe.nepnep.hduhelper.data.auth.AuthRepository
import moe.nepnep.hduhelper.data.auth.HduAuthApi
import moe.nepnep.hduhelper.data.auth.androidSessionStore
import moe.nepnep.hduhelper.data.settings.SettingsRepository

class HDUHelperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        android.webkit.WebView.setDataDirectorySuffix("hdu_auth")
        container.scheduleReminders.reschedule()
        container.courseReminders.reschedule()
    }

    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val updates = moe.nepnep.hduhelper.data.update.UpdateRepository()
    val updateCheckStore = moe.nepnep.hduhelper.data.update.AndroidUpdateCheckStore(application)
    val network = moe.nepnep.hduhelper.data.network.NetworkMonitor(application)
    val settings = SettingsRepository(application)
    val auth = AuthRepository(androidSessionStore(application), settings, HduAuthApi())
    val campusCodes = moe.nepnep.hduhelper.data.campuscode.CampusCodeRepository(auth)
    val timetables = moe.nepnep.hduhelper.data.timetable.TimetableRepository(auth, moe.nepnep.hduhelper.data.timetable.androidTimetableStore(application))
    val grades = moe.nepnep.hduhelper.data.grades.GradeRepository(auth, moe.nepnep.hduhelper.data.grades.androidGradeStore(application))
    val schedules = moe.nepnep.hduhelper.data.schedule.ScheduleRepository(moe.nepnep.hduhelper.data.schedule.androidScheduleStore(application))
    val scheduleReminders = moe.nepnep.hduhelper.data.schedule.AndroidScheduleReminders(application, schedules)
    val island = moe.nepnep.hduhelper.data.island.IslandAccess(application)
    val background = moe.nepnep.hduhelper.data.background.BackgroundAccess(application, settings)
    val courseReminders = moe.nepnep.hduhelper.data.notifications.AndroidCourseReminders(application, this)
    init {
        timetables.onChanged = courseReminders::reschedule
        schedules.onChanged = scheduleReminders::reschedule
        auth.onSessionInvalidated(campusCodes::clear)
        auth.onSessionInvalidated(timetables::clear)
        auth.onSessionInvalidated(grades::clear)
    }
}
