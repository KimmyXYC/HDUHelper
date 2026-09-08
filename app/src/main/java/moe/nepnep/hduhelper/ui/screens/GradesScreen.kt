package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.nepnep.hduhelper.data.grades.*
import moe.nepnep.hduhelper.data.timetable.AcademicTerm
import moe.nepnep.hduhelper.ui.GradesUiState
import moe.nepnep.hduhelper.ui.TimetableStatus
import moe.nepnep.hduhelper.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val GRADE_DISCLAIMER = "本功能的计算结果根据请求到的数据在前端计算而来，由于数据源的限制无法得出精确计算结果，可能包含部分错误（包括但不限于重修、多修导致的问题），仅供参考。一切请以教务系统为准，杭电助手及其成员不为数据准确性和可靠度负责，并且保留随时关闭该功能的权利。查看该数据表明你已经阅读、理解并同意以上声明。"

@Composable
fun GradesScreen(state: GradesUiState, onTerm: (AcademicTerm) -> Unit, onRefresh: () -> Unit,
    onLogin: () -> Unit, onVerify: () -> Unit, modifier: Modifier = Modifier) {
    var chooseTerm by remember { mutableStateOf(false) }
    val accessible = state.status !in listOf(TimetableStatus.SIGNED_OUT, TimetableStatus.LOGIN_REQUIRED, TimetableStatus.VERIFICATION_REQUIRED)
    val grades = state.grades.takeIf { accessible }
    val summary = remember(grades?.items) { GradeRules.summary(grades?.items.orEmpty()) }
    AppPullToRefresh(state.refreshing, onRefresh, modifier.testTag("grades_screen"),
        enabled = accessible && state.status != TimetableStatus.LOADING) {
        LazyColumn(Modifier.fillMaxSize().testTag("grades_list"), contentPadding = AcademicPageLayout.contentPadding,
            verticalArrangement = AcademicPageLayout.itemSpacing) {
            if (state.catalog != null && accessible) item(key = "term") {
                SemesterSelectButton(state.selectedTerm ?: state.catalog.current, { chooseTerm = true },
                    Modifier.testTag("grades_choose_term"))
            }
            if (!accessible) item(key = "auth") {
                val verification = state.status == TimetableStatus.VERIFICATION_REQUIRED
                AcademicLoginPrompt("考试成绩", verification, if (verification) onVerify else onLogin,
                    actionModifier = Modifier.testTag(if (verification) "grades_verify" else "grades_login"))
            }
            if (accessible) {
                val notice = state.message ?: if (state.offline) "离线显示最近同步的考试成绩" else null
                if (notice != null) item(key = "notice") {
                    AcademicPageNotice(notice)
                }
                if (state.status == TimetableStatus.LOADING && grades == null) item(key = "loading") {
                    AcademicPageMessage("正在读取考试成绩…", Modifier.testTag("grades_loading"))
                }
                if (grades != null) {
                    item(key = "summary") {
                        Card(Modifier.fillMaxWidth().testTag("grades_summary"), colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.primaryContainer, contentColor = MiuixTheme.colorScheme.onPrimaryContainer)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("共 ${grades.items.size} 门", style = MiuixTheme.textStyles.body2)
                                Text("当前学期平均绩点：${summary.average}", style = MiuixTheme.textStyles.title4)
                                Text("不含 C 类课平均绩点：${summary.withoutC}", style = MiuixTheme.textStyles.title4)
                                Text("非教务系统官方数据，详见底部说明", style = MiuixTheme.textStyles.footnote1)
                                if (summary.excluded > 0) Text("${summary.excluded} 门课程因成绩作废或学分、绩点无效，未计入汇总", style = MiuixTheme.textStyles.footnote1)
                                if (summary.unknownNature) Text("部分课程性质缺失，无法计算不含 C 类课绩点", style = MiuixTheme.textStyles.footnote1)
                            }
                        }
                    }
                    if (grades.detailsFailed) item(key = "details_failed") {
                        AcademicPageNotice("部分成绩分项未更新，最终成绩和绩点不受影响")
                    }
                    if (grades.items.isEmpty()) item(key = "empty") {
                        AcademicPageMessage("该学期暂无考试成绩", Modifier.testTag("grades_empty"))
                    }
                    items(grades.items, key = { it.id }) { GradeCard(it) }
                }
            }
            item(key = "explanation") {
                Card(Modifier.fillMaxWidth().testTag("grades_explanation")) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AcademicSectionTitle("说明")
                        Text("查询绩点仅供参考，请以教务处为准。")
                        Text("绩点计算存在误差，仅保留四位小数。")
                        Text("平均学分绩点是所学过的各门课程绩点的加权平均值(按学分加权)。", style = MiuixTheme.textStyles.footnote1)
                        Text("平均学分绩点 = 有效课程学分绩点总和 ÷ 有效课程学分总和", style = MiuixTheme.textStyles.footnote1)
                        Text(GRADE_DISCLAIMER, style = MiuixTheme.textStyles.footnote1)
                    }
                }
            }
        }
    }
    state.catalog?.let { catalog ->
        TermPicker(chooseTerm && accessible, catalog, state.selectedTerm ?: catalog.current, { chooseTerm = false }) {
            chooseTerm = false
            onTerm(it)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GradeCard(grade: CourseGrade) {
    Card(Modifier.fillMaxWidth().testTag("grade_${grade.id}")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(grade.name, Modifier.weight(1f), style = MiuixTheme.textStyles.title4)
                if (grade.credits.isNotBlank()) Text("学分：${grade.credits}", style = MiuixTheme.textStyles.body2)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                grade.components.forEach { Text("${it.name}：${it.score}", style = MiuixTheme.textStyles.body2) }
                if (grade.score.isNotBlank()) Text("最终成绩：${grade.score}", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
                if (grade.gradePoint.isNotBlank()) Text("最终绩点：${grade.gradePoint}", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
            }
            if (grade.invalidated) Text("成绩已作废 · 不计入绩点", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
