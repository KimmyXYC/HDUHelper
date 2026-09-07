package moe.nepnep.hduhelper.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.launch
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
import moe.nepnep.hduhelper.data.background.*
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
    onTest: suspend () -> Boolean,
    background: BackgroundStatus = BackgroundStatus(), backgroundEnhancement: Boolean = false,
    onBackgroundEnhancement: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefresh() }
    fun open(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }
        catch (_: SecurityException) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }
    }
    fun requestNotifications() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, AndroidCourseReminders.CHANNEL))
    }
    LifecycleResumeEffect(Unit) { onRefresh(); onPauseOrDispose {} }
    LaunchedEffect(Unit) {
        if ((settings.beforeClass || settings.afterClass || settings.beforeExam) && claimPrompt() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    NotificationSettingsScreen(settings, status, { next ->
        onChange(next)
        if ((!settings.beforeClass && next.beforeClass || !settings.afterClass && next.afterClass || !settings.beforeExam && next.beforeExam) && !status.notifications) requestNotifications()
    }, onNotifications = {
        // The explicit permission row always offers system settings, including permanent denial.
        open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, AndroidCourseReminders.CHANNEL))
    }, onExact = { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())) },
        modifier = modifier,
        background = background, backgroundEnhancement = backgroundEnhancement, onBackgroundEnhancement = onBackgroundEnhancement,
        onAutostart = { open(Intent("miui.intent.action.OP_AUTO_START").setPackage("com.miui.securitycenter")) },
        onBattery = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        onVendorBattery = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) },
        testEnabled = !testing, onTest = {
            if (!status.notifications) requestNotifications()
            else if (!testing) scope.launch {
                testing = true
                try {
                    if (!onTest()) Toast.makeText(context, "测试通知发送失败，请检查通知权限后重试", Toast.LENGTH_SHORT).show()
                } finally { testing = false }
            }
        })
}

@Composable
fun NotificationSettingsScreen(settings: NotificationSettings, status: CourseNotificationStatus,
    onChange: (NotificationSettings) -> Unit, onNotifications: () -> Unit, onExact: () -> Unit,
    modifier: Modifier = Modifier,
    background: BackgroundStatus = BackgroundStatus(), backgroundEnhancement: Boolean = false,
    onBackgroundEnhancement: (Boolean) -> Unit = {},
    onAutostart: () -> Unit = {}, onBattery: () -> Unit = {}, onVendorBattery: () -> Unit = {},
    onTest: () -> Unit = {},
    testEnabled: Boolean = true,
) {
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    var batteryDialog by rememberSaveable { mutableStateOf(false) }
    var minutesInput by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val minutes = minutesInput.toIntOrNull()?.takeIf { it in reminderMinutes }
    val vendorBatterySupported = background.vendorBattery != PermissionState.UNSUPPORTED
    fun openTime(kind: String, value: Int) { minutesInput = value.toString(); choosing = kind }
    fun closeTime() { focus.clearFocus(); keyboard?.hide(); choosing = null }
    fun saveTime() {
        val value = minutes ?: return
        when (choosing) {
            "start" -> onChange(settings.copy(beforeMinutes = value))
            "end" -> onChange(settings.copy(afterMinutes = value))
            "exam" -> onChange(settings.copy(examMinutes = value))
            else -> return
        }
        closeTime()
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp).testTag("notification_settings"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (background.hook !in setOf(BackgroundHookState.INACTIVE, BackgroundHookState.SCOPE_REQUIRED, BackgroundHookState.UNSUPPORTED)) Card(Modifier.fillMaxWidth()) {
            SwitchPreference(backgroundEnhancement, onBackgroundEnhancement, "Xposed 后台提醒增强",
                summary = background.hook.label, enabled = background.canEnable || backgroundEnhancement,
                modifier = Modifier.testTag("notify_background_enhancement"))
        }
        if (status.island.visible) Card(Modifier.fillMaxWidth()) {
            SwitchPreference(settings.island, { onChange(settings.copy(island = it)) }, "开启课程表超级岛",
                summary = if (status.island.ready) null else "请在 LSPosed 中重启系统界面作用域",
                enabled = status.island.ready, modifier = Modifier.testTag("notify_island"))
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
        Card(Modifier.fillMaxWidth()) {
            SwitchPreference(settings.beforeExam, { onChange(settings.copy(beforeExam = it)) }, "考试开始提醒", modifier = Modifier.testTag("notify_exam"))
            if (settings.beforeExam) ArrowPreference("提前时间", summary = reminderTimeLabel(settings.examMinutes),
                onClick = { openTime("exam", settings.examMinutes) }, modifier = Modifier.testTag("notify_exam_time"))
        }
        TextButton("测试通知", onTest, Modifier.fillMaxWidth().testTag("notify_test"), enabled = testEnabled,
            colors = ButtonDefaults.textButtonColors(color = MiuixTheme.colorScheme.primary, textColor = MiuixTheme.colorScheme.onPrimary))
        Text("提醒权限", style = MiuixTheme.textStyles.title4, color = MiuixTheme.colorScheme.onSurface)
        Card(Modifier.fillMaxWidth()) {
            ArrowPreference("通知权限", summary = if (status.notifications) "已开启" else "未开启，课程与考试提醒不可用", onClick = onNotifications, modifier = Modifier.testTag("notify_permission"))
            ArrowPreference("精确提醒", summary = if (status.exact) "已开启" else "未开启，提醒可能延迟", onClick = onExact)
            ArrowPreference("自启动", summary = permissionLabel(background.autostart, "已开启", "未开启"),
                onClick = onAutostart, modifier = Modifier.testTag("notify_autostart"))
            ArrowPreference("电池优化", summary = buildString {
                append("Android：${permissionLabel(background.batteryExemption, "已豁免", "未豁免")}")
                if (vendorBatterySupported) append(" · 小米：${permissionLabel(background.vendorBattery, "无限制", "有限制")}")
            },
                onClick = { batteryDialog = true }, modifier = Modifier.testTag("notify_battery"))
        }

    }
    WindowDialog(show = batteryDialog, title = "电池优化", onDismissRequest = { batteryDialog = false },
        modifier = Modifier.testTag("notify_battery_dialog")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ArrowPreference("Android 电池优化", summary = permissionLabel(background.batteryExemption, "已豁免", "未豁免"),
                onClick = onBattery, modifier = Modifier.testTag("notify_battery_android"))
            if (vendorBatterySupported) ArrowPreference("小米省电策略", summary = permissionLabel(background.vendorBattery, "无限制", "有限制"),
                onClick = onVendorBattery, modifier = Modifier.testTag("notify_battery_vendor"))
            TextButton("关闭", { batteryDialog = false }, Modifier.fillMaxWidth().testTag("notify_battery_close"))
        }
    }
    WindowDialog(show = choosing != null, title = if (choosing == "start") "上课提醒时间" else if (choosing == "exam") "考试提醒时间" else "下课提醒时间",
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

private fun permissionLabel(state: PermissionState, allowed: String, restricted: String) = when (state) {
    PermissionState.ALLOWED -> allowed
    PermissionState.RESTRICTED -> restricted
    PermissionState.UNKNOWN -> "无法检测"
    PermissionState.UNSUPPORTED -> "当前设备不支持"
}
