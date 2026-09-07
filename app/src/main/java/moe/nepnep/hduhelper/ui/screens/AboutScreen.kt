package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.nepnep.hduhelper.BuildConfig
import moe.nepnep.hduhelper.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(28.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.app_name), style = MiuixTheme.textStyles.title1)
                Text("版本 ${BuildConfig.VERSION_NAME}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("汇集日程、课表、一码通与常用服务，让校园生活更便捷。", style = MiuixTheme.textStyles.body1)
                Text("使用 MIUIX 构建", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            ArrowPreference(title = "项目地址", summary = "本项目基于 AGPL-3.0 协议开源",
                onClick = { uriHandler.openUri("https://github.com/KimmyXYC/HDUHelper") },
                modifier = Modifier.testTag("about_project"))
        }
    }
}
