package lpt.imehook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 槽位身份：2/6 有字符串实测，其余为键位指纹推断（不保证）。 */
private val SLOT_LABELS = listOf(
    "槽 1 · 智能ABC双拼（推断，不保证）",
    "槽 2 · 自然码（原生可选）",
    "槽 3 · 搜狗双拼（推断，不保证）",
    "槽 4 · 微软双拼（推断，不保证）",
    "槽 5 · 拼音加加双拼（推断，不保证）",
    "槽 6 · 小鹤（原生可选）",
    "槽 7 · 紫光双拼（推断，不保证）",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(ui: SettingsUiState, onImport: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("双拼方案扩展", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inset)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModeCard(ui)
            SchemeCard(ui, onImport)
            SlotCard(ui)
            TestCard(ui)
            ActionBar(ui)
            StatusCard(ui)
            AboutCard()
            Spacer(Modifier.height(16.dp))
        }
    }

    ui.editing?.let { draft -> SchemeDialog(draft, ui) { ui.dismissDialog() } }
    ui.renaming?.let { old -> RenameDialog(old, ui) { ui.dismissDialog() } }
    ui.deleting?.let { name -> DeleteDialog(name, ui) { ui.dismissDialog() } }
}

// ---------------- 各区块 ----------------

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ModeCard(ui: SettingsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("模式")
            ModeRow(ui.mode == ModuleConfig.MODE_OFF, "原生", "不干预，跟随豆包自身的方案设置") {
                ui.chooseMode(ModuleConfig.MODE_OFF)
            }
            ModeRow(ui.mode == ModuleConfig.MODE_SLOT, "切换内置槽位", "只改写方案 id，不替换表") {
                ui.chooseMode(ModuleConfig.MODE_SLOT)
            }
            ModeRow(ui.mode == ModuleConfig.MODE_CUSTOM, "注入自定义方案", "改写 id 并替换该槽的表") {
                ui.chooseMode(ModuleConfig.MODE_CUSTOM)
            }
        }
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchemeCard(ui: SettingsUiState, onImport: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    SectionCard("自定义方案库") {
        if (ui.schemes.isEmpty()) {
            Text(
                "还没有方案，先「新建」或「导入」一份 sp26 格式的 ini。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ExposedDropdownMenuBox(
            expanded = expanded && ui.schemes.isNotEmpty(),
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = ui.selected,
                onValueChange = { },
                readOnly = true,
                singleLine = true,
                enabled = ui.schemes.isNotEmpty(),
                label = { Text("当前方案") },
                placeholder = { Text("（无）") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = expanded && ui.schemes.isNotEmpty(),
                onDismissRequest = { expanded = false },
            ) {
                ui.schemes.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            ui.select(name)
                            expanded = false
                        },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ui::newScheme, modifier = Modifier.weight(1f)) { Text("新建") }
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("导入") }
            OutlinedButton(onClick = ui::editSelected, modifier = Modifier.weight(1f)) { Text("编辑") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ui::requestRename, modifier = Modifier.weight(1f)) { Text("重命名") }
            OutlinedButton(onClick = ui::requestDelete, modifier = Modifier.weight(1f)) { Text("删除") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotCard(ui: SettingsUiState) {
    val enabled = ui.mode != ModuleConfig.MODE_OFF
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(
                when (ui.mode) {
                    ModuleConfig.MODE_CUSTOM -> "注入目标槽位（表会覆盖这个槽）"
                    ModuleConfig.MODE_SLOT -> "切换目标槽位（使用该槽的原生表）"
                    else -> "目标槽位（原生模式下不使用）"
                }
            )
            ExposedDropdownMenuBox(
                expanded = expanded && enabled,
                onExpandedChange = { if (enabled) expanded = it },
            ) {
                OutlinedTextField(
                    value = SLOT_LABELS[ui.slot - 1],
                    onValueChange = { },
                    readOnly = true,
                    enabled = enabled,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = expanded && enabled,
                    onDismissRequest = { expanded = false },
                ) {
                    SLOT_LABELS.forEachIndexed { index, label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                ui.chooseSlot(index + 1)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TestCard(ui: SettingsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("试打")
            Text(
                "输入双键当场查看解出的拼音，与注入内容无关",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = ui.testInput,
                onValueChange = {
                    ui.testInput = it
                    ui.runTest()
                },
                singleLine = true,
                label = { Text("键串") },
                placeholder = { Text("例如 we、sf、ni、zh") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (ui.testInput.isNotEmpty()) {
                TextButton(
                    onClick = {
                        ui.testInput = ""
                        ui.runTest()
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("清空") }
            }
            if (ui.testResult.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        ui.testResult.trimEnd(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionBar(ui: SettingsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ui::apply, modifier = Modifier.weight(1f)) {
                Text("应用设置", textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = ui::restore, modifier = Modifier.weight(1f)) {
                Text("还原原生", textAlign = TextAlign.Center)
            }
        }
        OutlinedButton(onClick = ui::restartTarget, modifier = Modifier.fillMaxWidth()) {
            Text("重启豆包输入法")
        }
    }
}

@Composable
private fun StatusCard(ui: SettingsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                ui.status.ifEmpty { "（尚未读取）" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun AboutCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "1. 注入是运行时行为：只在豆包输入法进程内 hook，不修改 APK、不写目标数据；" +
                    "强制停止/重启后自动恢复原生。\n" +
                    "2. 点「应用设置」即生效（目标进程每秒同步一次配置），不需要重启。\n" +
                    "3. 槽 1/3/4/5/7 的方案身份由键位指纹推断，完整性未验证，行为异常属预期。\n" +
                    "4. 自定义模式下，表内未覆盖的键会回退到目标槽的原生展开结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------- 弹窗 ----------------

@Composable
private fun SchemeDialog(draft: SettingsUiState.Draft, ui: SettingsUiState, onDismiss: () -> Unit) {
    var name by remember(draft) { mutableStateOf(draft.name) }
    var content by remember(draft) { mutableStateOf(draft.content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.original == null) "新建方案" else "编辑 ${draft.original}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("方案名") },
                    placeholder = { Text("例如 小鹤改进版") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("码表内容（sp26.ini 格式）") },
                    placeholder = { Text("[sheng]\nzh=v\n\n[yun]\niai=d\n\n[yinjie]\na=aa") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 8,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ui.saveDraft(SettingsUiState.Draft(name, content, draft.original))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RenameDialog(old: String, ui: SettingsUiState, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(old) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("新名称") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { ui.renameTo(name) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeleteDialog(name: String, ui: SettingsUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除 $name？") },
        text = { Text("该方案会从本模块移除，已注入的配置不受影响。") },
        confirmButton = { TextButton(onClick = ui::confirmDelete) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
