package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.nepnep.hduhelper.data.timetable.ExamArrangement
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun ExamDetails(exam: ExamArrangement?, onDismiss: () -> Unit) {
    WindowDialog(show = exam != null, title = exam?.name, onDismissRequest = onDismiss, modifier = Modifier.testTag("exam_details")) {
        exam?.let {
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DetailLine("类型", listOf("考试", it.examName).filter { value -> value.isNotBlank() }.joinToString(" · "))
                DetailLine("时间", it.rawTime.ifBlank { "待确认" })
                DetailLine("校区", it.campus)
                DetailLine("地点", it.location)
                DetailLine("座号", it.seat)
                if (it.notes.isNotBlank()) DetailLine("备注", it.notes)
                TextButton("关闭", onDismiss, Modifier.fillMaxWidth())
            }
        }
    }
}
