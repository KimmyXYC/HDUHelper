package moe.nepnep.hduhelper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.nepnep.hduhelper.ui.UpdateState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun UpdateDialog(state: UpdateState, onDismiss: () -> Unit, onDownload: (String) -> Unit) {
    val release = state.release
    WindowDialog(
        show = release != null,
        title = "发现新版本 ${release?.tag.orEmpty()}",
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("update_dialog"),
    ) {
        if (release != null) {
            Text(release.notes, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()))
            state.message?.let { Text(it, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("稍后", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton("前往下载", onClick = { onDownload(release.url) }, modifier = Modifier.weight(1f))
            }
        }
    }
}
