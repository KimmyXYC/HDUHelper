package moe.nepnep.hduhelper.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.IconButton

/** Shared touch target and icon dimensions for every page's top bar. */
@Composable
fun AppTopBarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (Modifier) -> Unit,
) {
    IconButton(onClick, modifier.size(48.dp), enabled = enabled) {
        icon(Modifier.size(26.dp))
    }
}
