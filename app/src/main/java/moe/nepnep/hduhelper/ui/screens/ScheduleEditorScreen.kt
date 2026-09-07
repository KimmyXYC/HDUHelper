package moe.nepnep.hduhelper.ui.screens

import moe.nepnep.hduhelper.ui.components.AppTopBarIconButton
import moe.nepnep.hduhelper.ui.components.AppDatePicker
import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import moe.nepnep.hduhelper.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.time.LocalDate
import java.time.LocalTime
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.ui.ScheduleEditorState
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val shortWeekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private fun dateTimeLabel(value: LocalDateTime, allDay: Boolean): String =
    value.format(DateTimeFormatter.ofPattern("yyyy年M月d日")) + shortWeekdays[value.dayOfWeek.value - 1] +
        if (allDay) "" else " " + value.format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
fun ScheduleDateTimePicker(show: Boolean, title: String, value: LocalDateTime, allDay: Boolean,
    onDismiss: () -> Unit, onSelect: (LocalDateTime) -> Unit) {
    if (allDay) {
        AppDatePicker(show, value.toLocalDate(), onDismiss, { onSelect(it.atStartOfDay()) }, title)
        return
    }
    val firstDay = LocalDate.of(1900, 1, 1).toEpochDay()
    val lastDay = LocalDate.of(2200, 12, 31).toEpochDay()
    var day by remember(show, value) { mutableIntStateOf((value.toLocalDate().toEpochDay() - firstDay).toInt()) }
    var hour by remember(show, value) { mutableIntStateOf(value.hour) }
    var minute by remember(show, value) { mutableIntStateOf(value.minute) }
    var calendar by remember(show) { mutableStateOf(false) }
    val date = LocalDate.ofEpochDay(firstDay + day)
    val selected = date.atTime(hour, minute)
    val colors = NumberPickerDefaults.colors(selectedTextColor = MiuixTheme.colorScheme.primary)
    val stacked = LocalDensity.current.fontScale > 1.2f
    WindowDialog(show = show && !calendar, title = title, onDismissRequest = onDismiss, modifier = Modifier.testTag("schedule_datetime_picker")) {
        Text(dateTimeLabel(selected, false), Modifier.fillMaxWidth().clickable { calendar = true }.testTag("schedule_picker_date_summary").padding(bottom = 12.dp),
            textAlign = TextAlign.Center, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberPicker(day, { day = it }, Modifier.weight(if (stacked) 1f else 2.2f).testTag("schedule_picker_day"), range = 0..(lastDay - firstDay).toInt(),
                label = { LocalDate.ofEpochDay(firstDay + it).let { d -> "${d.monthValue}月${d.dayOfMonth}日 ${shortWeekdays[d.dayOfWeek.value - 1]}" } },
                colors = colors, visibleItemCount = 3, textStyle = MiuixTheme.textStyles.main.copy(fontSize = 18.sp), itemHeight = 48.dp)
            if (!stacked) ScheduleTimeWheels(hour, minute, { hour = it }, { minute = it }, Modifier.weight(2f))
        }
        if (stacked) ScheduleTimeWheels(hour, minute, { hour = it }, { minute = it }, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("取消", onDismiss, Modifier.weight(1f).testTag("schedule_datetime_cancel"))
            TextButton("确定", { onSelect(selected) }, Modifier.weight(1f).testTag("schedule_datetime_confirm"),
                colors = ButtonDefaults.textButtonColors(color = MiuixTheme.colorScheme.primary, textColor = MiuixTheme.colorScheme.onPrimary))
        }
    }
    AppDatePicker(show && calendar, date, { calendar = false }, { day = (it.toEpochDay() - firstDay).toInt(); calendar = false })
}

@Composable
private fun ScheduleTimeWheels(hour: Int, minute: Int, onHour: (Int) -> Unit, onMinute: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = NumberPickerDefaults.colors(selectedTextColor = MiuixTheme.colorScheme.primary)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NumberPicker(hour, onHour, Modifier.weight(1f).testTag("schedule_picker_hour"), range = 0..23, label = { "$it 时" },
            colors = colors, visibleItemCount = 3, textStyle = MiuixTheme.textStyles.main.copy(fontSize = 18.sp), itemHeight = 48.dp)
        NumberPicker(minute, onMinute, Modifier.weight(1f).testTag("schedule_picker_minute"), range = 0..59, label = { "$it 分" },
            colors = colors, visibleItemCount = 3, textStyle = MiuixTheme.textStyles.main.copy(fontSize = 18.sp), itemHeight = 48.dp)
    }
}

@Composable
fun ScheduleEditorScreen(
    state: ScheduleEditorState,
    onChange: (ScheduleEvent) -> Unit,
    onSave: (Boolean) -> Unit,
    needsExceptionReset: () -> Boolean,
    onBack: () -> Unit,
    reminderStatus: String?,
    onPermissionsChanged: () -> Unit,
    onChooseRepeat: () -> Unit,
    onChooseReminder: () -> Unit,
    active: Boolean = true,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberScrollState()
    LaunchedEffect(state.error) { if (state.error != null) scroll.animateScrollTo(0) }
    val draft = state.draft
    val start = draft.startTime
    val end = draft.endTime
    val endDate = if (draft.allDay) end.toLocalDate().minusDays(1) else end.toLocalDate()
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var exactPrompt by remember { mutableStateOf(false) }
    var previousStartTime by rememberSaveable { mutableStateOf(start.toLocalTime().toString()) }
    var previousEndTime by rememberSaveable { mutableStateOf(end.toLocalTime().toString()) }
    val context = LocalContext.current
    val alarms = remember(context) { context.getSystemService(AlarmManager::class.java) }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionsChanged()
        if (granted && !alarms.canScheduleExactAlarms()) exactPrompt = true
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onPermissionsChanged() }
    val requestExact = {
        runCatching { settingsLauncher.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())) }
        Unit
    }
    val requestReminderPermissions = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(AndroidScheduleReminders.CHANNEL)?.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
            runCatching { settingsLauncher.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        } else if (!alarms.canScheduleExactAlarms()) exactPrompt = true
        Unit
    }
    var lastReminder by rememberSaveable { mutableStateOf(state.editor.initial.reminderMinutes) }
    LaunchedEffect(draft.reminderMinutes, active) {
        if (active) {
            if (lastReminder == null && draft.reminderMinutes != null) requestReminderPermissions()
            lastReminder = draft.reminderMinutes
        }
    }
    val leaveEditor = { focus.clearFocus(); keyboard?.hide(); onBack() }
    val back = { if (!state.saving) { if (draft != state.editor.initial) discard = true else leaveEditor() }; Unit }
    BackHandler(enabled = active, onBack = back)
    Scaffold(
        modifier = Modifier.imePadding().testTag("schedule_editor"),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppTopBarIconButton(back, enabled = !state.saving) { Icon(MiuixIcons.Close, "关闭", it) }
                Text(if (state.editor.seriesId == null) "创建日程" else "编辑日程", Modifier.weight(1f), textAlign = TextAlign.Center,
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                AppTopBarIconButton({ focus.clearFocus(); keyboard?.hide(); if (needsExceptionReset()) reset = true else onSave(false) },
                    Modifier.testTag("schedule_save"), enabled = !state.saving) {
                    Icon(painterResource(R.drawable.ic_check), if (state.saving) "保存中" else "保存", it)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.error != null) Text(state.error, color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp).testTag("schedule_form_error"))
            ScheduleInput(draft.title, { onChange(draft.copy(title = it)) }, "请输入日程标题", "schedule_title", !state.saving, singleLine = true)
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(draft.allDay, { checked ->
                    if (checked) {
                        previousStartTime = start.toLocalTime().toString(); previousEndTime = end.toLocalTime().toString()
                        val last = if (end.toLocalTime() == LocalTime.MIDNIGHT && end.toLocalDate() > start.toLocalDate()) end.toLocalDate().minusDays(1) else end.toLocalDate()
                        onChange(draft.copy(allDay = true, start = start.toLocalDate().atStartOfDay().toString(), end = maxOf(last, start.toLocalDate()).plusDays(1).atStartOfDay().toString()))
                    } else {
                        val timedStart = start.toLocalDate().atTime(LocalTime.parse(previousStartTime))
                        val timedEnd = endDate.atTime(LocalTime.parse(previousEndTime))
                        onChange(draft.copy(allDay = false, start = timedStart.toString(), end = (if (timedEnd > timedStart) timedEnd else timedEnd.plusDays(1)).toString()))
                    }
                }, "全天事件", modifier = Modifier.testTag("schedule_all_day"))
                ScheduleSettingRow("开始时间", dateTimeLabel(start, draft.allDay), { picker = "start" }, "schedule_start", !state.saving)
                ScheduleSettingRow("结束时间", dateTimeLabel(if (draft.allDay) endDate.atStartOfDay() else end, draft.allDay), { picker = "end" }, "schedule_end", !state.saving)
            }
            if (state.editor.originalDate == null) Card(Modifier.fillMaxWidth()) {
                ScheduleSettingRow("重复", draft.repeat.label, { focus.clearFocus(); keyboard?.hide(); onChooseRepeat() }, "schedule_repeat", !state.saving)
            } else Text("仅修改本次日程", Modifier.padding(horizontal = 16.dp), style = MiuixTheme.textStyles.footnote1)
            Card(Modifier.fillMaxWidth()) {
                ScheduleSettingRow("提醒", ScheduleReminder.entries.first { it.minutes == draft.reminderMinutes }.label,
                    { focus.clearFocus(); keyboard?.hide(); onChooseReminder() }, "schedule_reminder", !state.saving)
                if (draft.reminderMinutes != null && reminderStatus != null) {
                    Text(reminderStatus, Modifier.padding(horizontal = 16.dp), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    ScheduleSettingRow("提醒权限", "设置", requestReminderPermissions, "schedule_reminder_permissions", !state.saving)
                }
            }
            ScheduleInput(draft.location, { onChange(draft.copy(location = it)) }, "位置（选填）", "schedule_location", !state.saving)
            ScheduleInput(draft.notes, { onChange(draft.copy(notes = it)) }, "请输入备注", "schedule_notes", !state.saving)
        }
    }
    ScheduleDateTimePicker(picker != null, if (picker == "end") "结束时间" else "开始时间",
        if (picker == "end") { if (draft.allDay) endDate.atStartOfDay() else end } else start, draft.allDay, { picker = null }) { value ->
        if (picker == "start") {
            onChange(draft.copy(start = value.toString(), end = end.plus(java.time.Duration.between(start, value)).toString()))
        } else onChange(draft.copy(end = (if (draft.allDay) value.toLocalDate().plusDays(1).atStartOfDay() else value).toString()))
        picker = null
    }
    WindowDialog(show = discard, title = "放弃修改？", summary = "尚未保存的内容将丢失。", onDismissRequest = { discard = false }, modifier = Modifier.testTag("schedule_discard_dialog")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("继续编辑", { discard = false }, Modifier.fillMaxWidth().testTag("schedule_continue_editing"))
            TextButton("放弃修改", { discard = false; leaveEditor() }, Modifier.fillMaxWidth().testTag("schedule_discard"))
        }
    }
    WindowDialog(show = reset, title = "重新生成重复日程？", summary = "修改开始日期或重复规则后，将清除已有的单次修改和单次删除记录。", onDismissRequest = { reset = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("取消", { reset = false }, Modifier.fillMaxWidth())
            TextButton("确认并保存", { reset = false; onSave(true) }, Modifier.fillMaxWidth())
        }
    }
    WindowDialog(show = exactPrompt, title = "开启精确提醒", summary = "允许应用设置闹钟和提醒，可更准时地发送日程通知。未开启时仍会提醒，但可能延迟。", onDismissRequest = { exactPrompt = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("暂不开启", { exactPrompt = false }, Modifier.fillMaxWidth())
            TextButton("前往设置", { exactPrompt = false; requestExact() }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ScheduleInput(value: String, onChange: (String) -> Unit, placeholder: String, tag: String, enabled: Boolean, singleLine: Boolean = false) {
    Card(Modifier.fillMaxWidth()) {
        TextField(value, onChange, Modifier.fillMaxWidth().testTag(tag), label = placeholder, useLabelAsPlaceholder = true,
            singleLine = singleLine, enabled = enabled, maxLines = if (singleLine) 1 else 6,
            colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent, borderColor = Color.Transparent,
                labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary), cursorBrush = SolidColor(MiuixTheme.colorScheme.primary))
    }
}

@Composable
private fun ScheduleSettingRow(label: String, value: String, onClick: () -> Unit, tag: String, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 17.sp)
        Text(value, Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text("›", fontSize = 22.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
fun ScheduleRepeatScreen(selected: ScheduleRepeat, onSelect: (ScheduleRepeat) -> Unit, onBack: () -> Unit) {
    ScheduleChoicesScreen("重复", ScheduleRepeat.entries.map { it.label }, selected.ordinal, { onSelect(ScheduleRepeat.entries[it]) },
        onBack, ScheduleRepeat.entries.map { "repeat_${it.name}" })
}

@Composable
fun ScheduleReminderScreen(minutes: Int?, onSelect: (Int?) -> Unit, onBack: () -> Unit) {
    ScheduleChoicesScreen("提醒", ScheduleReminder.entries.map { it.label }, ScheduleReminder.entries.indexOfFirst { it.minutes == minutes },
        { onSelect(ScheduleReminder.entries[it].minutes) }, onBack, ScheduleReminder.entries.map { "reminder_${it.name}" })
}

@Composable
private fun ScheduleChoicesScreen(title: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit, onBack: () -> Unit, tags: List<String>) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title, navigationIcon = { AppTopBarIconButton(onBack) { Icon(MiuixIcons.Back, "返回", it) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index, label ->
                    val isSelected = index == selected
                    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onSelect(index) }.semantics { this.selected = isSelected }
                        .testTag(tags[index]).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
                        if (isSelected) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
