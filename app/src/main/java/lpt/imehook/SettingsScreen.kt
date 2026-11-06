package lpt.imehook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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

/** 导入弹窗里的内容示例。 */
private const val EXAMPLE_INI = """[sheng]
zh=v
ch=i
sh=u

[yun]
a=a
ai=l
an=j
ang=h

[yinjie]
a=aa
ai=al"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    ui: SettingsUiState,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onExportLog: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("双拼扩展", fontWeight = FontWeight.SemiBold) },
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
                0 -> Tab1(ui, onExportLog)
                else -> Tab2(ui, onImport, onExport)
            }
        }
    }

    ui.editing?.let { draft -> SchemeDialog(draft, ui, onImport) { ui.dismissDialog() } }
    ui.iniEditing?.let { ini -> IniDialog(ini, ui) { ui.dismissDialog() } }
    ui.deleting?.let { name -> DeleteDialog(name, ui) { ui.dismissDialog() } }
    ui.patches?.takeIf { it.isNotEmpty() }?.let { list ->
        PatchesDialog(list) { ui.dismissDialog() }
    }
}

// ---------------- 顶栏重启 ----------------

@Composable
private fun RestartMenu(ui: SettingsUiState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Refresh, contentDescription = "重启豆包输入法")
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
private fun Tab1(ui: SettingsUiState, onExportLog: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeCard(ui)
        SchemePickerCard(ui)
        SlotCard(ui)
        TestCard(ui)
        ActionBar(ui)
        StatusCard(ui)
        DiagCard(ui, onExportLog)
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
private fun SchemePickerCard(ui: SettingsUiState) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard("当前方案") {
        if (ui.schemes.isEmpty()) {
            Text(
                "还没有方案，去「管理自定义双拼」导入。",
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
private fun DiagCard(ui: SettingsUiState, onExportLog: () -> Unit) {
    var showLog by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("诊断日志")
                    Text(
                        "常驻部分只统计调用/命中次数和异常，开销可以忽略；详细日志会记录每次按键的键位、候选数与耗时，" +
                            "默认关闭，且只保存在本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = ui.diag, onCheckedChange = { ui.chooseDiag(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "记录展开结果（拼音内容，用于排查特定音节失效）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.diag) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = ui.diagContent,
                    onCheckedChange = { ui.chooseDiagContent(it) },
                    enabled = ui.diag,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showLog = true }) { Text("查看") }
                OutlinedButton(onClick = onExportLog) { Text("导出") }
                TextButton(onClick = { ui.clearLog() }) { Text("清空") }
            }
        }
    }
    if (showLog) {
        val text = remember { ui.logText() }
        AlertDialog(
            onDismissRequest = { showLog = false },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("关闭") } },
            title = { Text("诊断日志") },
            text = {
                if (text.isEmpty()) {
                    Text("暂无日志（目标进程启动或开关生效后才会写入）", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            },
        )
    }
}

@Composable
private fun AboutCard() {
    var showLicense by remember { mutableStateOf(false) }
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
            HorizontalDivider()
            // 图标来源声明：Lucide 为 ISC 许可，要求随副本保留版权与许可声明
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "应用图标取自 Lucide（keyboard，ISC 许可）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showLicense = true }) { Text("许可全文") }
            }
        }
    }
    if (showLicense) LicenseDialog { showLicense = false }
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember {
        runCatching {
            ctx.assets.open("licenses/lucide-keyboard.txt").bufferedReader().use { it.readText() }
        }.getOrElse { "许可文件缺失" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("第三方许可") },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
    )
}

// ---------------- Tab2：管理自定义双拼 ----------------

@Composable
private fun Tab2(ui: SettingsUiState, onImport: () -> Unit, onExport: () -> Unit) {
    // 编辑区与方案栏选中项双向联动：任何途径（列表点选、删除回退、导入完成）
    // 改变了 selected，这里都会自动重建编辑模型。
    LaunchedEffect(ui.selected) { ui.loadEditModel() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SchemeListCard(ui, onImport, onExport)
        val m = ui.editModel
        if (m == null) {
            Text(
                "先在上方选一个方案，这里会出现它的映射表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            EditorCard(ui, m)
        }
        // 底部留几行空位的距离
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun SchemeListCard(ui: SettingsUiState, onImport: () -> Unit, onExport: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("方案")
            if (ui.schemes.isEmpty()) {
                Text(
                    "还没有方案。点「导入」粘贴 ini 或从文件读取，也可先「模板导出」拿到模板。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    ui.schemes.forEach { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { ui.select(name) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = name == ui.selected, onClick = { ui.select(name) })
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { ui.requestDelete(name) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ui::newScheme, modifier = Modifier.weight(1f)) { Text("导入") }
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("模板导出") }
            }
        }
    }
}

// ---------------- 方案编辑 ----------------

@Composable
private fun EditorCard(ui: SettingsUiState, m: SettingsUiState.SchemeModel) {
    var section by remember(m.name) { mutableStateOf(0) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "方案编辑·${m.name}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = ui::openIniEditor) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑 ini", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = ui::saveEditModel) {
                    Icon(Icons.Filled.Save, contentDescription = "保存", modifier = Modifier.size(20.dp))
                }
            }

            OutlinedTextField(
                value = m.newName,
                onValueChange = { ui.setSchemeName(it) },
                singleLine = true,
                label = { Text("方案名") },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            ZeroInitialRow(ui, m)

            // 分段标签：左对齐小矩形（选中加深），下方一条贯穿整行的长横线
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTab("声母", section == 0) { section = 0 }
                    SectionTab("韵母", section == 1) { section = 1 }
                    SectionTab("音节", section == 2) { section = 2 }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }

            // 表体不做内部滚动，跟随整页一起生长
            Column(modifier = Modifier.fillMaxWidth()) {
                when (section) {
                    0 -> SettingsUiState.INITIALS.forEach { init ->
                        MapRow(left = init, right = m.sheng[init].orEmpty()) { ui.setSheng(init, it) }
                    }
                    1 -> SettingsUiState.FINALS.forEach { fin ->
                        MapRow(left = fin, right = m.yun[fin].orEmpty()) { ui.setYun(fin, it) }
                    }
                    else -> SettingsUiState.ZERO_SYLS.forEach { syl ->
                        val code = m.yinjie.firstOrNull { it.first == syl }?.second.orEmpty()
                        MapRow(left = syl, right = code) { ui.setYinjie(syl, it) }
                    }
                }
            }
        }
    }
}

/** 分段标签：普通小矩形，选中时加深底色；非按钮样式。 */
@Composable
private fun SectionTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZeroInitialRow(ui: SettingsUiState, m: SettingsUiState.SchemeModel) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = SettingsUiState.ziModeLabel(m.ziMode),
                    onValueChange = { },
                    readOnly = true,
                    singleLine = true,
                    label = { Text("零声母") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    SettingsUiState.ZI_MODES.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(SettingsUiState.ziModeLabel(mode)) },
                            onClick = { ui.setZiMode(mode); expanded = false },
                        )
                    }
                }
            }
            if (m.ziMode == SchemeParser.ZI_FIXED) {
                CompactField(m.ziKey, { ui.setZiKey(it) }, Modifier.width(72.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = m.ziPriority, onCheckedChange = { ui.setZiPriority(it) })
            Text("高优先级", style = MaterialTheme.typography.bodySmall)
        }
    }
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
            modifier = Modifier.width(64.dp),
        )
        Spacer(Modifier.width(8.dp))
        CompactField(right, onValue, Modifier.weight(1f))
    }
}

/** 紧凑输入格：BasicTextField + 细边框，比 OutlinedTextField 矮一半。 */
@Composable
private fun CompactField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(30.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth(),
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
private fun SchemeDialog(draft: SettingsUiState.Draft, ui: SettingsUiState, onImport: () -> Unit, onDismiss: () -> Unit) {
    var name by remember(draft) { mutableStateOf(draft.name) }
    var content by remember(draft) { mutableStateOf(draft.content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入方案") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("方案名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("码表内容（sp26.ini 格式）") },
                    placeholder = {
                        Text(EXAMPLE_INI, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 10,
                    maxLines = 16,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { ui.saveDraft(SettingsUiState.Draft(name, content, draft.original)) }) { Text("导入") }
        },
        // 左下角：从文件读取（原来的导入键）
        dismissButton = {
            Row {
                TextButton(onClick = onImport) { Text("从文件导入") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun IniDialog(ini: String, ui: SettingsUiState, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(ui.selected) }
    var text by remember { mutableStateOf(ini) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑方案") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("方案名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("码表内容") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 12,
                    maxLines = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { ui.saveIni(name, text) }) { Text("保存") } },
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
