package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import moe.nepnep.hduhelper.ui.LoginFormState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LoginScreen(
    form: LoginFormState,
    onAccountChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAutoLoginChange: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPassword by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val submit = { focus.clearFocus(); onLogin() }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("登录数字杭电", style = MiuixTheme.textStyles.title2)
            Text("使用学校统一身份认证账号", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextField(
                    value = form.account, onValueChange = onAccountChange, label = "数字杭电账号",
                    modifier = Modifier.fillMaxWidth().testTag("login_account"), singleLine = true, enabled = !form.loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                )
                TextField(
                    value = form.password, onValueChange = onPasswordChange, label = "密码",
                    modifier = Modifier.fillMaxWidth().testTag("login_password"), singleLine = true, enabled = !form.loading,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }, enabled = !form.loading) {
                            Icon(
                                imageVector = if (showPassword) MiuixIcons.Hide else MiuixIcons.Show,
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    },
                )
            }
            form.error?.let { StatusMessage(it) }
        }
        Card(Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = "自动登录", summary = "在本机加密保存密码，用于登录失效时自动恢复",
                checked = form.autoLogin, onCheckedChange = onAutoLoginChange, enabled = !form.loading,
                modifier = Modifier.testTag("login_auto_login"),
            )
        }
        TextButton(
            text = if (form.loading) "正在登录…" else "登录",
            onClick = submit, enabled = !form.loading,
            modifier = Modifier.fillMaxWidth().testTag("submit_login"),
        )
        TextButton("使用官方页面登录", onClick = onVerify, enabled = !form.loading, modifier = Modifier.fillMaxWidth())
        Text("账号信息仅用于连接学校服务", modifier = Modifier.padding(horizontal = 4.dp), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
