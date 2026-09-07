package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.nepnep.hduhelper.data.timetable.TimetableSettings
import moe.nepnep.hduhelper.ui.TimetableUiState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun TimetableSettingsScreen(state: TimetableUiState, onChange: (TimetableSettings) -> Unit, onCampus: (String?) -> Unit, modifier: Modifier = Modifier) {
    val s = state.settings
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            SwitchPreference(s.showOtherWeeks, { onChange(s.copy(showOtherWeeks = it)) }, "显示非本周课程", modifier = Modifier.testTag("setting_other_weeks"))
            SwitchPreference(s.showFinished, { onChange(s.copy(showFinished = it)) }, "显示已结课课程", modifier = Modifier.testTag("setting_finished"))
            SwitchPreference(s.showWeekend, { onChange(s.copy(showWeekend = it)) }, "显示周末", modifier = Modifier.testTag("setting_weekend"))
            SwitchPreference(s.showTeacher, { onChange(s.copy(showTeacher = it)) }, "显示任课老师", modifier = Modifier.testTag("setting_teacher"))
            SwitchPreference(s.showLocation, { onChange(s.copy(showLocation = it)) }, "显示上课地点", modifier = Modifier.testTag("setting_location"))
        }
        Text("作息校区", style = MiuixTheme.textStyles.title4)
        if (state.data == null) Text("先打开课表以读取校区选项", style = MiuixTheme.textStyles.body2)
        else {
            Text(state.data.term.label, style = MiuixTheme.textStyles.footnote1)
            Card(Modifier.fillMaxWidth()) {
                RadioButtonPreference(selected = state.selectedCampus == null, onClick = { onCampus(null) }, title = "自动选择", modifier = Modifier.testTag("campus_auto"))
                for (campus in state.data.clocks) RadioButtonPreference(selected = state.selectedCampus == campus.id, onClick = { onCampus(campus.id) }, title = campus.name, modifier = Modifier.testTag("campus_${campus.id}"))
            }
        }
    }
}
