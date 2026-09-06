package moe.nepnep.hduhelper.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import moe.nepnep.hduhelper.ui.CampusCodeStatus
import moe.nepnep.hduhelper.ui.CampusCodeUiState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CampusCodeScreen(state: CampusCodeUiState, onRefresh: () -> Unit, onLogin: () -> Unit, onVerify: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.code?.profile?.let { profile ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${profile.name} · ${profile.identity}", style = MiuixTheme.textStyles.title2, modifier = Modifier.testTag("campus_identity"))
                        if (profile.college.isNotBlank()) Text(profile.college, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                val image = state.image
                if (image != null) {
                    val bitmap = remember(image) { Bitmap.createBitmap(image.pixels, image.size, image.size, Bitmap.Config.ARGB_8888).asImageBitmap() }
                    Image(bitmap, contentDescription = "一码通二维码", modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f)
                        .background(Color.White).testTag("campus_qr"), filterQuality = FilterQuality.None)
                    state.code?.let { code ->
                        val time = remember(code.updatedAt) { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(code.updatedAt)) }
                        Text("更新于 $time", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.2f), contentAlignment = Alignment.Center) {
                        Text(state.message ?: when (state.status) {
                            CampusCodeStatus.SIGNED_OUT -> "登录后查看一码通"
                            CampusCodeStatus.AUTHORIZING -> "正在授权并获取二维码…"
                            else -> "暂时无法显示二维码"
                        }, modifier = Modifier.testTag("campus_message"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (state.nextRefreshSeconds != null && state.status == CampusCodeStatus.READY) {
                    Text("${state.nextRefreshSeconds} 秒后自动刷新", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.testTag("campus_countdown"))
                }
                when (state.status) {
                    CampusCodeStatus.SIGNED_OUT, CampusCodeStatus.LOGIN_REQUIRED -> TextButton("登录", onClick = onLogin, modifier = Modifier.fillMaxWidth().testTag("campus_login"))
                    CampusCodeStatus.VERIFICATION_REQUIRED -> TextButton("完成官方验证", onClick = onVerify, modifier = Modifier.fillMaxWidth())
                    else -> TextButton(if (state.refreshing) "正在刷新…" else "刷新", onClick = onRefresh,
                        enabled = !state.refreshing, modifier = Modifier.fillMaxWidth().testTag("campus_refresh"))
                }
            }
        }
    }
}
