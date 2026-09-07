package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import moe.nepnep.hduhelper.R
import moe.nepnep.hduhelper.data.auth.AuthState
import moe.nepnep.hduhelper.data.auth.AuthStatus
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun ProfileScreen(
    auth: AuthState,
    onLogin: () -> Unit,
    onAppearance: () -> Unit,
    onLogout: () -> Unit,
    onAbout: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    actionError: String? = null,
    onTimetableSettings: () -> Unit = {},
    onNotificationSettings: () -> Unit = {},
) {
    val busy = auth.status in listOf(AuthStatus.LOADING, AuthStatus.SIGNING_IN, AuthStatus.REFRESHING)
    var showLogoutConfirmation by remember(auth.profile?.account, auth.status) { mutableStateOf(false) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().then(
                if (auth.profile == null) Modifier.clickable(enabled = !busy, role = Role.Button, onClick = onLogin).testTag("open_login")
                else Modifier,
            ),
            insideMargin = PaddingValues(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(
                    Modifier.size(64.dp).background(MiuixTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MiuixIcons.Contacts, contentDescription = null, modifier = Modifier.size(30.dp), tint = MiuixTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(auth.profile?.name ?: "未登录", style = MiuixTheme.textStyles.title2)
                    Text(
                        auth.profile?.account ?: "连接你的数字杭电账号",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Text(
                text = when (auth.status) {
                    AuthStatus.LOADING -> "正在读取登录信息…"
                    AuthStatus.SIGNING_IN -> "正在登录…"
                    AuthStatus.REFRESHING -> "正在检查并恢复登录…"
                    AuthStatus.AUTHENTICATED -> "已登录数字杭电"
                    AuthStatus.UNAVAILABLE -> "已保留登录信息，联网后自动确认"
                    AuthStatus.VERIFICATION_REQUIRED -> "需要完成身份验证"
                    AuthStatus.SIGNED_OUT -> "登录后开启校园服务"
                },
                modifier = Modifier.padding(top = 20.dp).testTag("auth_status"),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            auth.message?.let { StatusMessage(it) }
            if (auth.status == AuthStatus.VERIFICATION_REQUIRED) {
                TextButton("完成官方验证", onClick = onVerify, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("设置", modifier = Modifier.padding(start = 8.dp), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Card(modifier = Modifier.fillMaxWidth()) {
                ArrowPreference(title = "外观设置", onClick = onAppearance, modifier = Modifier.testTag("open_appearance"))
                ArrowPreference(title = "课表设置", onClick = onTimetableSettings, modifier = Modifier.testTag("open_timetable_settings"))
                ArrowPreference(title = "通知设置", onClick = onNotificationSettings, modifier = Modifier.testTag("open_notification_settings"))
                ArrowPreference(title = "关于应用", onClick = onAbout)
            }
        }
        actionError?.let { StatusMessage(it) }
        if (auth.profile != null) {
            TextButton(
                stringResource(R.string.logout_title),
                onClick = { showLogoutConfirmation = true },
                modifier = Modifier.fillMaxWidth().testTag("logout"),
            )
        }
    }
    WindowDialog(
        show = showLogoutConfirmation && auth.profile != null,
        title = stringResource(R.string.logout_title),
        summary = stringResource(R.string.logout_message),
        onDismissRequest = { showLogoutConfirmation = false },
        modifier = Modifier.testTag("logout_confirmation"),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                stringResource(R.string.logout_cancel),
                onClick = { showLogoutConfirmation = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                stringResource(R.string.logout_confirm),
                onClick = {
                    if (showLogoutConfirmation && auth.profile != null) {
                        showLogoutConfirmation = false
                        onLogout()
                    }
                },
                modifier = Modifier.weight(1f).testTag("confirm_logout"),
            )
        }
    }
}

@Composable
internal fun StatusMessage(message: String) {
    Text(
        text = message,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
