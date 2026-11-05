package lpt.imehook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.input.KeyboardCapitalization
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
fun SettingsScreen(ui: SettingsUiState, onImport: () -> Unit, onExport: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("双拼方案扩展", fontWeight = FontWeight.SemiBold) },
                actions = { RestartMenu(ui) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inset ->
        Column(Modifier.padding(inset).fillMaxSize()) {
            TabRow(selectedTabIndex = ui.tab) {
                Tab(
                    selected = ui.tab == 0,
                    onClick = { ui.chooseTab(0) },
                    text = { Text("设置") },
                )
                Tab(
                    selected = ui.tab == 1,
                    onClick = { ui.chooseTab(1) },
                    text = { Text("管理自定义双拼") },
                )
            }
            when (ui.tab) {
                0 -> Tab1(ui, onImport)
                else -> Tab2(ui, onImport, onExport)
            }
        }
    }

    ui.editing?.let { draft -> SchemeDialog(draft, ui) { ui.dismissDialog() } }
    ui.renaming?.let { old -> RenameDialog(old, ui) { ui.dismissDialog() } }
    ui.deleting?.let { name -> DeleteDialog(name, ui) { ui.dismissDialog() } }
    ui.patches?.takeIf { it.isNotEmpty() }?.let { list ->
        PatchesDialog(list) { ui.dismissDialog() }
    }
}

// ---------------- 顶栏重启菜单 ----------------

@Composable
private fun RestartMenu(ui: SettingsUiState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Text("重启", fontWeight = FontWeight.Medium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("重启豆包输入法") },
                onClick = {
                    expanded = false
                    ui.restartTarget()
                },
            )
        }
    }
}

// ---------------- Tab1：设置 ----------------

@Composable
private fun Tab1(ui: SettingsUiState, onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeCard(ui)
        SchemePickerCard(ui, onImport)
        SlotCard(ui)
        TestCard(ui)
        ActionBar(ui)
        StatusCard(ui)
        AboutCard()
        Spacer(Modifier.height(16.dp))
    }
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchemePickerCard(ui: SettingsUiState, onImport: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard("当前方案") {
        if (ui.schemes.isEmpty()) {
            Text(
                "还没有方案，去「管理自定义双拼」新建或导入。",
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
                label = { Text("方案") },
                placeholder = { Text("（无）") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded && ui.schemes.isNotEmpty(), onDismissRequest = { expanded = false }) {
                ui.schemes.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { ui.select(name); expanded = false },
                    )
                }
            }
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
            ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
                OutlinedTextField(
                    value = SLOT_LABELS[ui.slot - 1],
                    onValueChange = { },
                    readOnly = true,
                    enabled = enabled,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                    SLOT_LABELS.forEachIndexed { index, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { ui.chooseSlot(index + 1); expanded = false })
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
            SectionTitle("试用")
            Text(
                "换完方案可以在这里当场敲两键试试解出的拼音，与是否注入无关。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = ui.testInput,
                onValueChange = { ui.testInput = it; ui.runTest() },
                singleLine = true,
                label = { Text("测试输入") },
                placeholder = { Text("例如 we、sf、ni") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (ui.testInput.isNotEmpty()) {
                TextButton(
                    onClick = { ui.testInput = ""; ui.runTest() },
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = ui::apply, modifier = Modifier.weight(1f)) {
            Text("应用设置", textAlign = TextAlign.Center)
        }
        OutlinedButton(onClick = ui::restore, modifier = Modifier.weight(1f)) {
            Text("还原原生", textAlign = TextAlign.Center)
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

// ---------------- Tab2：管理自定义双拼 ----------------

@Composable
private fun Tab2(ui: SettingsUiState, onImport: () -> Unit, onExport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 方案列表（紧凑行）
        SchemeListCard(ui, onImport, onExport)
        // 零声母设置 + 编辑表（仅当选中有方案时）
        ui.editModel?.let { m ->
            ZeroInitialCard(ui, m)
            EditTableCard(ui, m)
            Button(onClick = ui::saveEditModel, modifier = Modifier.fillMaxWidth()) {
                Text("保存 ${m.name}")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SchemeListCard(ui: SettingsUiState, onImport: () -> Unit, onExport: () -> Unit) {
    SectionCard("方案列表") {
        if (ui.schemes.isEmpty()) {
            Text(
                "还没有方案。可「导入」现成 ini，或「导出模板」后编辑再导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ui.schemes.forEach { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { ui.select(name) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = name == ui.selected, onClick = { ui.select(name) })
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ui::newScheme, modifier = Modifier.weight(1f)) { Text("新建") }
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("导入") }
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("导出模板") }
        }
    }
}

@Composable
private fun ZeroInitialCard(ui: SettingsUiState, m: SettingsUiState.SchemeModel) {
    SectionCard("零声母方案（可改）") {
        listOf(
            SchemeParser.ZI_FIXED,
            SchemeParser.ZI_FIRST_LETTER,
            SchemeParser.ZI_FIRST_LETTER_FULL,
            SchemeParser.ZI_OTHER,
        ).forEach { mode ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { ui.setZiMode(mode) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = m.ziMode == mode, onClick = { ui.setZiMode(mode) })
                Spacer(Modifier.width(4.dp))
                Text(SettingsUiState.ziModeLabel(mode), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (m.ziMode == SchemeParser.ZI_FIXED) {
            OutlinedTextField(
                value = m.ziKey,
                onValueChange = { ui.setZiKey(it) },
                singleLine = true,
                label = { Text("固定键（单个字母）") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = m.ziPriority, onCheckedChange = { ui.setZiPriority(it) })
            Text("此方案优先于 [yinjie] 音节栏", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EditTableCard(ui: SettingsUiState, m: SettingsUiState.SchemeModel) {
    SectionCard("映射表（左键不可改，右键可改）") {
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            item { SubHeader("声母") }
            items(SettingsUiState.INITIALS) { init ->
                MapRow(left = init, right = m.sheng[init].orEmpty()) { ui.setSheng(init, it) }
            }
            item { SubHeader("韵母") }
            items(SettingsUiState.FINALS) { fin ->
                MapRow(left = fin, right = m.yun[fin].orEmpty()) { ui.setYun(fin, it) }
            }
            item { SubHeader("零声音节") }
            items(SettingsUiState.ZERO_SYLS) { syl ->
                val code = m.yinjie.firstOrNull { it.first == syl }?.second.orEmpty()
                MapRow(left = syl, right = code) { ui.setYinjie(syl, it) }
            }
        }
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun MapRow(left: String, right: String, onValue: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            left,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(72.dp),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = right,
            onValueChange = onValue,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(),
        )
    }
}

// ---------------- 通用 ----------------

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("方案名") },
                    placeholder = { Text("例如 自然码") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("码表内容（sp26.ini 格式）") },
                    placeholder = { Text("[sheng]\nzh=v\n\n[yun]\nai=l\n\n[yinjie]\na=aa") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 8,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { ui.saveDraft(SettingsUiState.Draft(name, content, draft.original)) }) { Text("保存") } },
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
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("新名称") }, modifier = Modifier.fillMaxWidth())
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

@Composable
private fun PatchesDialog(patches: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入时已自动补全") },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                patches.forEach { p ->
                    Text("• $p", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
