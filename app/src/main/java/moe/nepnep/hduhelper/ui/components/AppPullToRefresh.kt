package moe.nepnep.hduhelper.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.PullToRefresh

/** Both calendars use the same threshold resistance, applied only to unconsumed downward drag. */
@Composable
fun AppPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val resistance = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, available.y * 0.5f) else Offset.Zero
        }
    }
    Box(modifier) {
        if (enabled) PullToRefresh(isRefreshing, onRefresh, Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().nestedScroll(resistance)) { content() }
        } else content()
    }
}
