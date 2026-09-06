package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.nepnep.hduhelper.data.settings.ThemeMode
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppearanceScreen(theme: ThemeMode, onThemeChange: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("主题", modifier = Modifier.padding(start = 8.dp, bottom = 12.dp), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Card(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEach { mode ->
                RadioButtonPreference(
                    title = mode.label,
                    selected = theme == mode,
                    onClick = { onThemeChange(mode) },
                    modifier = Modifier.testTag("theme_${mode.name}"),
                )
            }
        }
    }
}
