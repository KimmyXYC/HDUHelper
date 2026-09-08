package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.TopDownloads
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ApplicationsScreen(modifier: Modifier = Modifier, onExams: () -> Unit = {}, onGrades: () -> Unit = {}) {
    LazyVerticalGrid(GridCells.Fixed(2), modifier.testTag("applications_grid"),
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "exams") {
            Card(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onExams).testTag("application_exams"),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().heightIn(min = 128.dp).padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Icon(MiuixIcons.Edit, null, Modifier.size(32.dp), tint = MiuixTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(24.dp))
                    Text("考试安排", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item(key = "grades") {
            Card(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onGrades).testTag("application_grades"),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().heightIn(min = 128.dp).padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Icon(MiuixIcons.TopDownloads, null, Modifier.size(32.dp), tint = MiuixTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(24.dp))
                    Text("考试成绩", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}
