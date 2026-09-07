package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.nepnep.hduhelper.data.background.BackgroundHookState
import moe.nepnep.hduhelper.AppContainer
import moe.nepnep.hduhelper.data.notifications.NotificationSettings

class NotificationSettingsViewModel(private val container: AppContainer) : ViewModel() {
    val status = container.courseReminders.status
    val background = container.background.state
    private var policyUpdate: Job? = null
    suspend fun testNotification() = container.courseReminders.sendTestNotification()
    fun foreground() { container.courseReminders.foreground(); container.background.refresh() }
    fun updateBackground(enabled: Boolean) {
        container.settings.setBackgroundEnhancement(enabled)
        policyUpdate?.cancel()
        policyUpdate = viewModelScope.launch {
            container.background.refreshNow()
            val expected = if (enabled) BackgroundHookState.ACTIVE else BackgroundHookState.READY
            val applied = withTimeoutOrNull(5_000) { background.first { it.hook == expected } }
            if (applied != null) {
                // Recreate existing alarms so an earlier vendor delay does not survive enabling the hook.
                container.courseReminders.reschedule()
                container.scheduleReminders.reschedule()
            }
        }
    }
    fun update(value: NotificationSettings) = container.settings.setNotifications(value)
    fun claimPermissionPrompt() = container.settings.claimNotificationPermissionPrompt()

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NotificationSettingsViewModel(container) as T
        }
    }
}
