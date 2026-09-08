package moe.nepnep.hduhelper.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.theme.TextStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LocalFullSizeTextStyles = staticCompositionLocalOf<TextStyles?> { null }

/** Restore the surrounding app typography for shared controls and detail dialogs. */
@Composable
fun FullSizeComponentTheme(content: @Composable () -> Unit) {
    MiuixTheme(textStyles = LocalFullSizeTextStyles.current ?: MiuixTheme.textStyles, content = content)
}

/** Scope compact typography to exam pages, except shared semester controls and detail dialogs. */
@Composable
fun ExamPageTheme(content: @Composable () -> Unit) {
    val text = MiuixTheme.textStyles
    CompositionLocalProvider(LocalFullSizeTextStyles provides (LocalFullSizeTextStyles.current ?: text)) {
        MiuixTheme(textStyles = text.copy(
            main = text.main.compact(),
            paragraph = text.paragraph.compact(),
            body1 = text.body1.compact(),
            body2 = text.body2.compact(),
            button = text.button.compact(),
            footnote1 = text.footnote1.compact(),
            footnote2 = text.footnote2.compact(),
            headline1 = text.headline1.compact(),
            headline2 = text.headline2.compact(),
            subtitle = text.subtitle.compact(),
            title1 = text.title1.compact(),
            title2 = text.title2.compact(),
            title3 = text.title3.compact(),
            title4 = text.title4.compact(),
        ), content = content)
    }
}

private fun TextStyle.compact(): TextStyle = copy(fontSize = (fontSize.value - 2f).coerceAtLeast(12f).sp)
