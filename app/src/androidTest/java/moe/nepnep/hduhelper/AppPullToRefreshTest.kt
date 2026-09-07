package moe.nepnep.hduhelper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import moe.nepnep.hduhelper.ui.components.AppPullToRefresh
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import top.yukonga.miuix.kmp.basic.Text

class AppPullToRefreshTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(90)
    @get:Rule val compose = createComposeRule()

    @Test fun onlyLongDownwardOverscrollAtTopRefreshes() {
        var refreshing by mutableStateOf(false)
        var refreshes = 0
        compose.setContent { HDUHelperTheme(darkTheme = true) {
            AppPullToRefresh(refreshing, { refreshes++; refreshing = true }, Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag("refresh_list")) {
                    items(60) { Text("条目 $it", Modifier.fillMaxWidth().height(56.dp)) }
                }
            }
        } }
        val list = compose.onNodeWithTag("refresh_list")
        list.performTouchInput { swipe(center, Offset(center.x, center.y + 40f), durationMillis = 500) }
        compose.runOnIdle { assertEquals(0, refreshes) }
        list.performScrollToIndex(20)
        list.performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(0, refreshes) }
        list.performScrollToIndex(0)
        list.performTouchInput { swipe(Offset(center.x, top + 20f), Offset(center.x, bottom - 20f), durationMillis = 1000) }
        compose.runOnIdle { assertEquals(1, refreshes); refreshing = false }
    }
}
