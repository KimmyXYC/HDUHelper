package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import moe.nepnep.hduhelper.AppContainer
import moe.nepnep.hduhelper.data.notifications.NotificationSettings

class NotificationSettingsViewModel(private val container: AppContainer) : ViewModel() {
    val status = container.courseReminders.status
    fun foreground() = container.courseReminders.foreground()
    fun update(value: NotificationSettings) = container.settings.setNotifications(value)
    fun claimPermissionPrompt() = container.settings.claimNotificationPermissionPrompt()

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NotificationSettingsViewModel(container) as T
        }
    }
}
