package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.nepnep.hduhelper.data.electric.*
import moe.nepnep.hduhelper.ui.*
import moe.nepnep.hduhelper.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ElectricScreen(state: ElectricUiState, onRefresh: () -> Unit, onEdit: () -> Unit, onClose: () -> Unit,
    onBuilding: (ElectricOption) -> Unit, onFloor: (ElectricOption) -> Unit, onRoom: (ElectricOption) -> Unit,
    onBind: () -> Unit, onUnbind: () -> Unit, onLogin: () -> Unit, onVerify: () -> Unit, modifier: Modifier = Modifier) {
    CompositionLocalProvider(top.yukonga.miuix.kmp.theme.LocalContentColor provides MiuixTheme.colorScheme.onSurface) {
        var picker by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        val accessible = state.status !in listOf(TimetableStatus.SIGNED_OUT, TimetableStatus.LOGIN_REQUIRED, TimetableStatus.VERIFICATION_REQUIRED)
        LaunchedEffect(accessible, state.editing) { picker = ""; confirmation = "" }
        AppPullToRefresh(state.loading, onRefresh, modifier.background(MiuixTheme.colorScheme.surface).testTag("electric_screen"), enabled = accessible && !state.editing) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = AcademicPageLayout.contentPadding,
                verticalArrangement = AcademicPageLayout.itemSpacing) {
                if (!accessible) item {
                    val verify = state.status == TimetableStatus.VERIFICATION_REQUIRED
                    AcademicLoginPrompt("电费查询", verify, if (verify) onVerify else onLogin,
                        actionModifier = Modifier.testTag("electric_login"))
                }
                if (accessible) {
                    state.message?.let { item { AcademicPageNotice(it) } }
                    if (state.loading) item { AcademicPageMessage("正在读取电费数据…") }
                    if (!state.bindingKnown && !state.loading) item {
                        TextButton("重新查询", onRefresh, Modifier.testTag("electric_retry"))
                    }
                    if (state.bindingKnown && !state.editing) {
                        if (state.binding == null) item {
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("尚未绑定宿舍电表")
                                TextButton("绑定宿舍", onEdit, enabled = !state.loading)
                            } }
                        } else {
                            item {
                                Card(Modifier.fillMaxWidth().testTag("electric_balance")) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(state.binding.label)
                                        Text(state.balance?.amount?.let { "¥ $it" } ?: "余额暂不可用", style = MiuixTheme.textStyles.title2)
                                        Text("余额（元）", style = MiuixTheme.textStyles.footnote1)
                                        state.balance?.time?.let { time ->
                                            val formatted = runCatching { Instant.ofEpochSecond(time).atZone(ZoneId.of("Asia/Shanghai"))
                                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrDefault("未知")
                                            Text("数据更新时间：$formatted", style = MiuixTheme.textStyles.footnote1)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton("更换宿舍", onEdit, enabled = !state.loading)
                                            TextButton("解绑", { confirmation = "unbind" }, enabled = !state.loading)
                                        }
                                    }
                                }
                            }
                            item { AcademicSectionTitle("近 14 天记录") }
                            val history = state.history
                            if (history == null) item { AcademicPageMessage("历史记录暂不可用") }
                            else {
                                item { Text("日均消费：${history.average ?: "—"} 元") }
                                if (history.items.isEmpty()) item { AcademicPageMessage("暂无历史记录") }
                                items(history.items) { record ->
                                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(record.date)
                                        Text("余额 ${record.fee} 元 · 变化 ${record.change}")
                                    } }
                                }
                            }
                        }
                    }
                    if (state.editing) item {
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AcademicSectionTitle("选择宿舍")
                            TextButton(state.building?.name ?: "选择楼栋", { picker = "building" }, enabled = !state.loading)
                            TextButton(state.floor?.name ?: "选择楼层", { picker = "floor" }, enabled = state.building != null && !state.loading)
                            TextButton(state.room?.name ?: "选择房间", { picker = "room" }, enabled = state.floor != null && !state.loading)
                            if (state.buildings.isEmpty() && !state.loading) TextButton("重新加载楼栋", onEdit)
                            TextButton("确认绑定", { confirmation = "bind" }, enabled = state.room != null && !state.loading,
                                modifier = Modifier.testTag("electric_bind"))
                            TextButton("取消", onClose, enabled = !state.loading)
                        } }
                    }
                }
            }
        }
        val options = when (picker) { "building" -> state.buildings; "floor" -> state.floors; else -> state.rooms }
        val pickerTitle = when (picker) { "building" -> "选择楼栋"; "floor" -> "选择楼层"; else -> "选择房间" }
        val selectedOption = when (picker) { "building" -> state.building; "floor" -> state.floor; else -> state.room }
        val listHeight = (LocalConfiguration.current.screenHeightDp * .42f).dp.coerceAtMost(360.dp)
        WindowDialog(show = picker.isNotEmpty() && accessible && state.editing, title = pickerTitle,
            onDismissRequest = { picker = "" }) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (options.isEmpty()) Text("暂无可选项，可重新选择上级后重试")
                else {
                    Text("共 ${options.size} 项，可上下滑动选择", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    key(picker) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight).testTag("electric_options"),
                            contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(options, key = { it.id }) { option ->
                                val selected = selectedOption?.id == option.id
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent)
                                    .selectable(selected, role = Role.RadioButton, onClick = {
                                        when (picker) { "building" -> onBuilding(option); "floor" -> onFloor(option); else -> onRoom(option) }
                                        picker = ""
                                    }).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp)
                                    .testTag("electric_option_${option.id}"), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(option.name, Modifier.weight(1f), color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
                                    RadioButton(selected, onClick = null)
                                }
                            }
                        }
                    }
                }
                TextButton("取消", { picker = "" }, Modifier.fillMaxWidth().testTag("electric_picker_cancel"))
            }
        }
        WindowDialog(show = confirmation.isNotEmpty() && accessible, title = if (confirmation == "bind") "确认绑定宿舍" else "解绑宿舍",
            summary = if (confirmation == "bind") state.selectionLabel else "解绑后将无法查询当前宿舍的电费。",
            onDismissRequest = { confirmation = "" }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("取消", { confirmation = "" }, Modifier.weight(1f))
                TextButton("确认", { val bind = confirmation == "bind"; confirmation = ""; if (bind) onBind() else onUnbind() },
                    Modifier.weight(1f).testTag("electric_confirm"), enabled = !state.loading)
            }
        }
    }
}
