package moe.nepnep.hduhelper.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
data class AppSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val autoLogin: Boolean = true)

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
        ),
    )
    val state: StateFlow<AppSettings> = mutableState.asStateFlow()
    override val autoLogin: Boolean get() = mutableState.value.autoLogin

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
