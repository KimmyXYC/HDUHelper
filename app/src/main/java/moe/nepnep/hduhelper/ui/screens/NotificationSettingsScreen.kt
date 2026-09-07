package moe.nepnep.hduhelper.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import moe.nepnep.hduhelper.data.notifications.*
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun NotificationSettingsPage(settings: NotificationSettings, status: CourseNotificationStatus,
    onChange: (NotificationSettings) -> Unit, onRefresh: () -> Unit, claimPrompt: () -> Boolean, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefresh() }
    fun open(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }
    }
    fun requestNotifications() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, AndroidCourseReminders.CHANNEL))
    }
    LifecycleResumeEffect(Unit) { onRefresh(); onPauseOrDispose {} }
    LaunchedEffect(Unit) {
        if ((settings.beforeClass || settings.afterClass) && claimPrompt() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    NotificationSettingsScreen(settings, status, { next ->
        onChange(next)
        if ((!settings.beforeClass && next.beforeClass || !settings.afterClass && next.afterClass) && !status.notifications) requestNotifications()
    }, onNotifications = {
        // The explicit permission row always offers system settings, including permanent denial.
        open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, AndroidCourseReminders.CHANNEL))
    }, onExact = { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())) },
        onPromoted = {
            if (Build.VERSION.SDK_INT >= 36) open(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, modifier = modifier,
        onBackground = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) })
}

@Composable
fun NotificationSettingsScreen(settings: NotificationSettings, status: CourseNotificationStatus,
    onChange: (NotificationSettings) -> Unit, onNotifications: () -> Unit, onExact: () -> Unit, onPromoted: () -> Unit,
    modifier: Modifier = Modifier,
    onBackground: () -> Unit = {},
) {
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    var minutesInput by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val minutes = minutesInput.toIntOrNull()?.takeIf { it in reminderMinutes }
    fun openTime(kind: String, value: Int) { minutesInput = value.toString(); choosing = kind }
    fun closeTime() { focus.clearFocus(); keyboard?.hide(); choosing = null }
    fun saveTime() {
        val value = minutes ?: return
        when (choosing) {
            "start" -> onChange(settings.copy(beforeMinutes = value))
            "end" -> onChange(settings.copy(afterMinutes = value))
            else -> return
        }
        closeTime()
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp).testTag("notification_settings"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            SwitchPreference(settings.live, { onChange(settings.copy(live = it)) }, "使用实时通知",
                modifier = Modifier.testTag("notify_live"))
        }
        Card(Modifier.fillMaxWidth()) {
            SwitchPreference(settings.beforeClass, { onChange(settings.copy(beforeClass = it)) }, "上课提醒", modifier = Modifier.testTag("notify_start"))
            if (settings.beforeClass) ArrowPreference("提前时间", summary = reminderTimeLabel(settings.beforeMinutes),
                onClick = { openTime("start", settings.beforeMinutes) }, modifier = Modifier.testTag("notify_start_time"))
        }
        Card(Modifier.fillMaxWidth()) {
            SwitchPreference(settings.afterClass, { onChange(settings.copy(afterClass = it)) }, "下课提醒", modifier = Modifier.testTag("notify_end"))
            if (settings.afterClass) ArrowPreference("提前时间", summary = reminderTimeLabel(settings.afterMinutes),
                onClick = { openTime("end", settings.afterMinutes) }, modifier = Modifier.testTag("notify_end_time"))
        }
        Text("提醒权限", style = MiuixTheme.textStyles.title4, color = MiuixTheme.colorScheme.onSurface)
        Card(Modifier.fillMaxWidth()) {
            ArrowPreference("通知权限", summary = if (status.notifications) "已开启" else "未开启，课程提醒不可用", onClick = onNotifications, modifier = Modifier.testTag("notify_permission"))
            ArrowPreference("精确提醒", summary = if (status.exact) "已开启" else "未开启，提醒可能延迟", onClick = onExact)
            if (status.promotionSupported) ArrowPreference("实时通知权限", summary = if (status.promoted) "已开启" else "未开启，将显示普通持续通知", onClick = onPromoted)
            ArrowPreference("后台运行设置", summary = "关闭电池优化并开启自启动", onClick = onBackground)
        }

    }
    WindowDialog(show = choosing != null, title = if (choosing == "start") "上课提醒时间" else "下课提醒时间",
        onDismissRequest = ::closeTime, modifier = Modifier.testTag("notify_time_picker")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(minutesInput, { value ->
                if (value.isEmpty() || value.length <= 2 && value.all { it in '0'..'9' } && value.toInt() in reminderMinutes) {
                    minutesInput = value
                }
            }, Modifier.fillMaxWidth().testTag("notify_minutes_input"), label = "提前时间（分钟，0–30）", singleLine = true,
                textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveTime() }))
            TextButton("确定", ::saveTime, Modifier.fillMaxWidth().testTag("notify_time_confirm"), enabled = minutes != null,
                colors = ButtonDefaults.textButtonColors(color = MiuixTheme.colorScheme.primary, textColor = MiuixTheme.colorScheme.onPrimary))
            TextButton("取消", ::closeTime, Modifier.fillMaxWidth().testTag("notify_time_cancel"))
        }
    }
}
