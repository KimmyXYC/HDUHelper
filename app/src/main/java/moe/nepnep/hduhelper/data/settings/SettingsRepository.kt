package moe.nepnep.hduhelper.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.nepnep.hduhelper.data.timetable.TimetableSettings
import java.security.MessageDigest

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
data class AppSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val autoLogin: Boolean = true, val timetable: TimetableSettings = TimetableSettings(), val campusRevision: Long = 0)

interface AuthSettings {
    val autoLogin: Boolean
    fun setAutoLogin(enabled: Boolean)
}

class SettingsRepository(context: Context) : AuthSettings {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        AppSettings(
            theme = ThemeMode.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: ThemeMode.SYSTEM,
            autoLogin = preferences.getBoolean("auto_login", true),
            timetable = TimetableSettings(
                preferences.getBoolean("tt_other", true), preferences.getBoolean("tt_finished", false),
                preferences.getBoolean("tt_weekend", false), preferences.getBoolean("tt_teacher", true), preferences.getBoolean("tt_location", true),
            ),
        ),
    )
    val state: StateFlow<AppSettings> = mutableState.asStateFlow()
    override val autoLogin: Boolean get() = mutableState.value.autoLogin

    fun setTimetable(value: TimetableSettings) {
        preferences.edit {
            putBoolean("tt_other", value.showOtherWeeks); putBoolean("tt_finished", value.showFinished)
            putBoolean("tt_weekend", value.showWeekend); putBoolean("tt_teacher", value.showTeacher); putBoolean("tt_location", value.showLocation)
        }
        mutableState.value = mutableState.value.copy(timetable = value)
    }

    private fun campusKey(account: String, term: String): String = "tt_campus_" + MessageDigest.getInstance("SHA-256")
        .digest("$account/$term".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun timetableCampus(account: String, term: String): String? = preferences.getString(campusKey(account, term), null)
    fun setTimetableCampus(account: String, term: String, campus: String?) {
        preferences.edit { if (campus == null) remove(campusKey(account, term)) else putString(campusKey(account, term), campus) }
        mutableState.value = mutableState.value.copy(campusRevision = mutableState.value.campusRevision + 1)
    }

    fun setTheme(theme: ThemeMode) {
        preferences.edit { putString("theme", theme.name) }
        mutableState.value = mutableState.value.copy(theme = theme)
    }

    @android.annotation.SuppressLint("UseKtx") // The Boolean commit result is required for the auth transaction.
    override fun setAutoLogin(enabled: Boolean) {
        // Called from the authentication repository's IO transaction, not the UI thread.
        check(preferences.edit().putBoolean("auto_login", enabled).commit())
        mutableState.value = mutableState.value.copy(autoLogin = enabled)
    }
}
