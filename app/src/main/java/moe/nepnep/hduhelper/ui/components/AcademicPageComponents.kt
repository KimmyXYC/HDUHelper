package moe.nepnep.hduhelper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.nepnep.hduhelper.data.timetable.AcademicTerm
import moe.nepnep.hduhelper.ui.theme.FullSizeComponentTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared rhythm and state presentation for academic result pages. */
object AcademicPageLayout {
    val contentPadding = PaddingValues(20.dp)
    val itemSpacing = Arrangement.spacedBy(16.dp)
}

@Composable
fun SemesterSelectButton(term: AcademicTerm, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FullSizeComponentTheme {
        TextButton("${term.label}  ▾", onClick, modifier.fillMaxWidth())
    }
}

/** Loading and empty states use identical alignment, typography, color and spacing. */
@Composable
fun AcademicPageMessage(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(vertical = 32.dp),
        style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Start)
}

/** Update failures, offline data and partial results share a quiet inline notice. */
@Composable
fun AcademicPageNotice(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth(), style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Start)
}

@Composable
fun AcademicLoginPrompt(subject: String, verificationRequired: Boolean, onAction: () -> Unit,
    modifier: Modifier = Modifier, actionModifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.Start,
        verticalArrangement = AcademicPageLayout.itemSpacing) {
        Text(if (verificationRequired) "请完成官方验证后查看$subject" else "登录后查看$subject",
            style = MiuixTheme.textStyles.body1, textAlign = TextAlign.Start)
        TextButton(if (verificationRequired) "完成官方验证" else "登录", onAction, actionModifier)
    }
}

@Composable
fun AcademicSectionTitle(text: String, modifier: Modifier = Modifier, subdued: Boolean = false) {
    Text(text, modifier.fillMaxWidth(), style = MiuixTheme.textStyles.title4,
        color = if (subdued) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
        textAlign = TextAlign.Start)
}
