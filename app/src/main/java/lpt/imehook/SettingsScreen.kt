package lpt.imehook

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 槽位身份：2/6 有字符串实测，其余为键位指纹推断。 */
private val SLOT_LABELS = listOf(
    "槽 1 · 智能ABC双拼（推断）",
    "槽 2 · 自然码",
    "槽 3 · 搜狗双拼（推断）",
    "槽 4 · 微软双拼（推断）",
    "槽 5 · 拼音加加双拼（推断）",
    "槽 6 · 小鹤",
    "槽 7 · 紫光双拼（推断）",
)

/** 缺项尾缀：柔和警告红（非强警示）。 */
private val MissingAccent = Color(0xFFC46A6A)

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
    if (ui.newPrompt) NewPromptDialog(ui)
    if (ui.pendingSelect != null) PendingSelectDialog(ui)
    ui.requiredMissing?.let { list -> RequiredDialog(list, ui) }
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
        // 当前方案只服务于自定义模式；槽位模式不应出现该栏目
        if (ui.mode == ModuleConfig.MODE_CUSTOM) {
            SchemePickerCard(ui)
        }
        if (ui.mode == ModuleConfig.MODE_SLOT) {
            SlotCard(ui)
        }
        TestCard()
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
            ModeRow(ui.mode == ModuleConfig.MODE_OFF, "原生", "不干预") {
                ui.chooseMode(ModuleConfig.MODE_OFF)
            }
            ModeRow(ui.mode == ModuleConfig.MODE_SLOT, "切换内置槽位", "固定为所选槽的原生展开") {
                ui.chooseMode(ModuleConfig.MODE_SLOT)
            }
            ModeRow(ui.mode == ModuleConfig.MODE_CUSTOM, "自定义方案", "未覆盖的键返回空，不回退原生") {
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
                "暂无方案，请到「管理自定义双拼」新建。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ExposedDropdownMenuBox(
            expanded = expanded && ui.schemes.isNotEmpty(),
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = schemeLabel(ui, ui.selected),
                onValueChange = { },
                readOnly = true,
                singleLine = true,
                enabled = ui.schemes.isNotEmpty(),
                label = { Text("方案") },
                placeholder = { Text("（无）") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (ui.isMissing(ui.selected)) MissingAccent
                    else MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded && ui.schemes.isNotEmpty(), onDismissRequest = { expanded = false }) {
                ui.schemes.forEach { name ->
                    DropdownMenuItem(
                        text = { SchemeNameText(ui, name) },
                        onClick = { ui.requestSelect(name); expanded = false },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotCard(ui: SettingsUiState) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("作用槽位")
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = SLOT_LABELS[ui.slot - 1],
                    onValueChange = { },
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    SLOT_LABELS.forEachIndexed { index, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { ui.chooseSlot(index + 1); expanded = false })
                    }
                }
            }
        }
    }
}

@Composable
private fun TestCard() {
    // 仅供系统输入法当场打字验证注入效果；模块侧推导检索在「中间结果」
    var draft by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("试用")
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                label = { Text("输入框") },
                placeholder = { Text("用当前输入法打字验证") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.isNotEmpty()) {
                TextButton(
                    onClick = { draft = "" },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("清空") }
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
                        "详细日志记录按键级事件，默认关，仅本机保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = ui.diag, onCheckedChange = { ui.chooseDiag(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "同时记录展开出的拼音",
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
                "注入只在豆包输入法进程内生效，不改 APK。「应用设置」后即时同步；模块被杀仍可用目标侧缓存。" +
                    "推断槽位身份未经验证。自定义表未覆盖的键返回空。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            // ISC 要求保留版权声明；作者署名为 Lucide Icons and Contributors
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "应用图标：Lucide Icons「keyboard」\n" +
                        "https://lucide.dev/icons/keyboard\n" +
                        "ISC License · Copyright (c) Lucide Icons and Contributors",
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
    // 改变了 selected，这里都会自动重建编辑模型；新建草稿不随选中项重建。
    LaunchedEffect(ui.selected) {
        if (ui.formMode == SettingsUiState.FormMode.EDIT) ui.loadEditModel()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SchemeListCard(ui, onExport)
        val m = ui.editModel
        if (m == null) {
            Text(
                "上方选中方案后，在此编辑映射表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            EditorCard(ui, m, onImport)
        }
        if (ui.formMode == SettingsUiState.FormMode.EDIT && ui.parsed != null) {
            MidResultCard(ui)
        }
        // 底部留几行空位的距离
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun SchemeListCard(ui: SettingsUiState, onExport: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("方案")
            if (ui.schemes.isEmpty()) {
                Text(
                    "点「新建」，或用编辑区导入图标从文件导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    ui.schemes.forEach { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { ui.requestSelect(name) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = name == ui.selected, onClick = { ui.requestSelect(name) })
                            SchemeNameText(
                                ui,
                                name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { ui.requestDelete(name) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ui::requestNew, modifier = Modifier.weight(1f)) { Text("新建") }
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("模板导出") }
            }
        }
    }
}

// ---------------- 方案编辑 ----------------

@Composable
private fun EditorCard(ui: SettingsUiState, m: SettingsUiState.SchemeModel, onImport: () -> Unit) {
    var section by remember(m.name, ui.formMode) { mutableStateOf(0) }
    val isNew = ui.formMode == SettingsUiState.FormMode.NEW
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isNew) "方案新建" else "方案编辑·${m.name}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // 导入图标（下载箭头）/ 编辑图标（铅笔）/ 保存 —— 勿再对调
                ImportIcon(onImport)
                IconButton(onClick = ui::openIniEditor) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑 ini 原文", modifier = Modifier.size(20.dp))
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

            if (isNew) {
                Text(
                    "零声母写在音节栏；等号左可重复；声母默认映到自身字母。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 分段标签：左对齐小矩形（选中加深），下方一条贯穿整行的长横线
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTab("声母", section == 0) { section = 0 }
                    SectionTab("韵母", section == 1) { section = 1 }
                    SectionTab("音节", section == 2) { section = 2 }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }

            Text(
                "右侧逗号分隔多值；空项不写入；* 为必填。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 表体不做内部滚动，跟随整页一起生长
            Column(modifier = Modifier.fillMaxWidth()) {
                when (section) {
                    0 -> SettingsUiState.INITIALS.forEach { init ->
                        MapRow(init, m.sheng[init].orEmpty(), ui.isInitialRequired(init)) {
                            ui.setSheng(init, it)
                        }
                    }
                    1 -> SettingsUiState.FINALS.forEach { fin ->
                        MapRow(fin, m.yun[fin].orEmpty(), ui.isFinalRequired(fin)) {
                            ui.setYun(fin, it)
                        }
                    }
                    else -> SettingsUiState.ZERO_SYLS.forEach { syl ->
                        MapRow(syl, m.yinjie[syl].orEmpty(), required = true) {
                            ui.setYinjie(syl, it)
                        }
                    }
                }
            }
        }
    }
}

/** 从文件导入：向下箭头 + 托盘。 */
@Composable
private fun ImportIcon(onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "从文件导入" },
    ) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = 1.8.dp.toPx()
            val w = size.width
            val h = size.height
            val cx = w / 2f
            // 向下箭头
            drawLine(color, Offset(cx, h * 0.12f), Offset(cx, h * 0.60f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(cx - w * 0.16f, h * 0.42f), Offset(cx, h * 0.62f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(cx + w * 0.16f, h * 0.42f), Offset(cx, h * 0.62f), strokeWidth = stroke, cap = StrokeCap.Round)
            // 放平的方括号 / 托盘
            val bx = w * 0.16f
            drawLine(color, Offset(bx, h * 0.74f), Offset(bx, h * 0.94f), strokeWidth = stroke)
            drawLine(color, Offset(bx, h * 0.94f), Offset(w - bx, h * 0.94f), strokeWidth = stroke)
            drawLine(color, Offset(w - bx, h * 0.74f), Offset(w - bx, h * 0.94f), strokeWidth = stroke)
        }
    }
}

/** 中间结果：键检索 + 声母 / 韵母 / 音节反转表。 */
@Composable
private fun MidResultCard(ui: SettingsUiState) {
    val pr = ui.parsed ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { ui.toggleMid() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "中间结果",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (ui.midExpanded) "收起" else "展开",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.midExpanded) {
                Text(
                    "下表为读入 ini 后的内存中途表；热路径查合并后的最终表。" +
                        "实打某键异常时，用下方检索核对该键的推导候选，以区分模块展开有误还是豆包输入法自身行为。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 检索：小字之后、声母键表之前
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompactField(
                        value = ui.probeInput,
                        onValueChange = { ui.onProbeInput(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = "键检索，如 we",
                    )
                    if (ui.probeInput.isNotEmpty()) {
                        TextButton(onClick = { ui.onProbeInput("") }) { Text("清空") }
                    }
                }
                if (ui.probeResult.isNotEmpty()) {
                    Text(
                        ui.probeResult.trimEnd(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                MidTable("声母键表", pr.scheme.initials)
                MidTable("韵母键表", pr.scheme.finals)
                MidTable("音节键表", pr.scheme.syllables)
            }
        }
    }
}

@Composable
private fun MidTable(title: String, map: Map<String, List<String>>) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "$title（${map.size} 项）",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        map.entries.sortedBy { it.key }.forEach { (k, v) ->
            Text(
                "$k = [${v.joinToString(", ")}]",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@Composable
private fun MapRow(left: String, right: String, required: Boolean, onValue: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            left,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(56.dp),
        )
        if (required) {
            Text(
                "*",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.width(10.dp),
            )
        } else {
            Spacer(Modifier.width(10.dp))
        }
        Spacer(Modifier.width(8.dp))
        CompactField(right, onValue, Modifier.weight(1f))
    }
}

/** 紧凑输入格：BasicTextField + 细边框，比 OutlinedTextField 矮一半。 */
@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && !placeholder.isNullOrEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }
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

/** 方案名 + 可选「（缺项）」柔和警告色尾缀。 */
@Composable
private fun SchemeNameText(
    ui: SettingsUiState,
    name: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = style)
        if (ui.isMissing(name)) {
            Text("（缺项）", style = style, color = MissingAccent)
        }
    }
}

/** OutlinedTextField 等只吃 String 时的展示名。 */
private fun schemeLabel(ui: SettingsUiState, name: String): String =
    if (name.isEmpty()) "" else if (ui.isMissing(name)) "$name（缺项）" else name

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
    val isNew = ui.formMode == SettingsUiState.FormMode.NEW
    var name by remember {
        mutableStateOf(if (isNew) ui.editModel?.newName.orEmpty() else ui.selected)
    }
    var text by remember { mutableStateOf(ini) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建方案" else "编辑方案") },
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
                    placeholder = {
                        Text(
                            EXAMPLE_INI,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 12,
                    maxLines = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text.isEmpty()) {
                    Text(
                        EXAMPLE_INI,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { ui.saveIni(name, text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NewPromptDialog(ui: SettingsUiState) {
    AlertDialog(
        onDismissRequest = ui::dismissNewPrompt,
        title = { Text("新建方案") },
        text = { Text("是否加载自然码模板作为起点？") },
        confirmButton = { TextButton(onClick = { ui.startNew(true) }) { Text("加载模板") } },
        dismissButton = {
            Row {
                TextButton(onClick = { ui.startNew(false) }) { Text("空白新建") }
                TextButton(onClick = ui::dismissNewPrompt) { Text("取消") }
            }
        },
    )
}

@Composable
private fun PendingSelectDialog(ui: SettingsUiState) {
    AlertDialog(
        onDismissRequest = ui::continueNew,
        title = { Text("编辑内容尚未保存成新方案") },
        text = { Text("继续新建会保留当前填写内容；放弃则会丢弃本次填写。") },
        confirmButton = { TextButton(onClick = ui::continueNew) { Text("继续新建") } },
        dismissButton = { TextButton(onClick = ui::discardNewAndSelect) { Text("放弃并切换") } },
    )
}

@Composable
private fun RequiredDialog(missing: List<String>, ui: SettingsUiState) {
    AlertDialog(
        onDismissRequest = ui::dismissRequired,
        title = { Text("必填项为空") },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("以下必填项尚未填写：", style = MaterialTheme.typography.bodySmall)
                missing.forEach { item ->
                    Text(
                        "• $item",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("仍要保存吗？", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = ui::forceSaveEditModel) { Text("执意保存") } },
        dismissButton = { TextButton(onClick = ui::dismissRequired) { Text("返回填写") } },
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
