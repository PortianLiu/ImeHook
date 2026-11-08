package lpt.imehook

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 界面状态 + 业务动作。UI 只读这些 state，改配置都走这里，
 * 保证 Compose 重组和目标进程实际生效的内容是同一份。
 */
class SettingsUiState(private val ctx: Context, private val store: SchemeStore) {

    private val saved = ModuleConfig.load(ctx)

    var mode by mutableStateOf(saved.mode)
        private set
    var slot by mutableStateOf(saved.slot)
        private set
    /** 详细诊断日志开关（按键级记录），默认关闭。 */
    var diag by mutableStateOf(saved.diag)
        private set
    /** 二级开关：详细日志开启时，额外记录展开结果的拼音内容。 */
    var diagContent by mutableStateOf(saved.diagContent)
        private set

    var schemes by mutableStateOf(store.names())
        private set
    var selected by mutableStateOf(
        store.active().ifEmpty { schemes.firstOrNull().orEmpty() }
    )
        private set

    var testInput by mutableStateOf("")
    var testResult by mutableStateOf("")
        private set
    var status by mutableStateOf("")
        private set

    var tab by mutableStateOf(0)
        private set

    var editing by mutableStateOf<Draft?>(null)
        private set
    var deleting by mutableStateOf<String?>(null)
        private set
    var patches by mutableStateOf<List<String>?>(null)
        private set
    var iniEditing by mutableStateOf<String?>(null)
        private set

    /** 新建询问：是否加载自然码模板。 */
    var newPrompt by mutableStateOf(false)
        private set
    /** 新建过程中点击其他方案时的待切换目标。 */
    var pendingSelect by mutableStateOf<String?>(null)
        private set
    /** 必填项缺失、等待用户确认是否执意保存。 */
    var requiredMissing by mutableStateOf<List<String>?>(null)
        private set

    /** 编辑区处于「方案编辑」还是「方案新建」。 */
    var formMode by mutableStateOf(FormMode.EDIT)
        private set

    /** 中间结果展示区的展开状态（跨启动记忆）。 */
    var midExpanded by mutableStateOf(store.midExpanded())
        private set

    /** Tab2 中正在编辑的方案结构表。 */
    var editModel by mutableStateOf<SchemeModel?>(null)
        private set

    /** 当前选中方案的内存模型（试打 / 中间结果 / 注入共用）。 */
    var parsed by mutableStateOf<SchemeParser.ParseResult?>(null)
        private set

    /** 必填项不完整的方案（按显示名判断尾缀用）。 */
    private var missingFlags by mutableStateOf<Map<String, Boolean>>(emptyMap())

    data class Draft(val name: String, val content: String, val original: String?)

    enum class FormMode { EDIT, NEW }

    data class SchemeModel(
        val name: String,
        val sheng: MutableMap<String, String>,
        val yun: MutableMap<String, String>,
        val yinjie: MutableMap<String, String>,
        var newName: String,
    )

    init {
        reloadScheme()
        recomputeMissing()
        updateStatus()
    }

    // ---------------- 基本操作 ----------------

    fun chooseMode(m: Int) { mode = m }
    fun chooseSlot(s: Int) { slot = s }
    fun chooseTab(t: Int) { tab = t }

    // 编辑区与方案选中项的联动由界面层 LaunchedEffect(ui.selected) 驱动，
    // 这里只改选中态，不手动触发编辑区刷新。
    fun select(name: String) {
        if (name == selected) return
        formMode = FormMode.EDIT
        selected = name
        if (name.isNotEmpty()) store.setActive(name)
        reloadScheme()
        runTest()
        updateStatus()
    }

    /** 界面点选方案：新建过程中需要先确认是否丢弃当前填写。 */
    fun requestSelect(name: String) {
        if (formMode == FormMode.NEW) {
            val m = editModel
            if (m != null && schemeHasContent(m)) { pendingSelect = name; return }
            formMode = FormMode.EDIT
            select(name)
            loadEditModel()
            return
        }
        select(name)
    }

    fun continueNew() { pendingSelect = null }

    fun discardNewAndSelect() {
        val target = pendingSelect
        pendingSelect = null
        formMode = FormMode.EDIT
        if (target != null) {
            select(target)
            loadEditModel()
        }
    }

    fun refreshSchemes() {
        schemes = store.names()
        if (schemes.isEmpty()) {
            selected = ""
            parsed = null
        } else if (schemes.none { it == selected }) {
            selected = store.active().takeIf { it in schemes } ?: schemes.first()
            store.setActive(selected)
        }
        reloadScheme()
        recomputeMissing()
        runTest()
        updateStatus()
    }

    fun displayName(name: String): String =
        if (missingFlags[name] == true) "$name（缺必填）" else name

    /** 逐个方案检查必填项是否齐全（只决定显示尾缀，不改存储名）。 */
    private fun recomputeMissing() {
        val flags = mutableMapOf<String, Boolean>()
        for (n in schemes) {
            val ini = store.iniOf(n) ?: continue
            val (sheng, yun, yinjie) = parseIni(ini)
            flags[n] = missingRequired(sheng, yun, yinjie).isNotEmpty()
        }
        missingFlags = flags
    }

    fun toggleMid() {
        midExpanded = !midExpanded
        store.setMidExpanded(midExpanded)
    }

    fun isInitialRequired(initial: String): Boolean = initial in REQUIRED_INITIALS

    fun isFinalRequired(fin: String): Boolean =
        isRequiredFinal(fin, editModel?.yun.orEmpty())

    private fun schemeHasContent(m: SchemeModel): Boolean =
        m.sheng.values.any { hasValue(it) } ||
            m.yun.values.any { hasValue(it) } ||
            m.yinjie.values.any { hasValue(it) }

    private fun reloadScheme() {
        parsed = effectiveScheme(selected)
    }

    /** 读入 ini 原文并当场展开成内存模型（试打 / 注入共用）。 */
    private fun effectiveScheme(name: String): SchemeParser.ParseResult? {
        if (name.isEmpty()) return null
        val ini = store.iniOf(name) ?: return null
        return try { SchemeParser.parseWithPatches(ini) } catch (e: Exception) { null }
    }

    // ---------------- 试打 ----------------

    fun runTest() {
        val raw = testInput.trim().lowercase()
        if (raw.isEmpty()) { testResult = ""; return }
        val s = parsed
        if (s == null) { testResult = "当前方案未解析成功"; return }
        testResult = buildString {
            var i = 0
            while (i < raw.length) {
                val k = raw.substring(i, minOf(i + 2, raw.length))
                append(k).append(" → ")
                val v = s.scheme.table[k]
                if (v.isNullOrEmpty()) append("（无候选）")
                else append(v.joinToString(", "))
                append('\n')
                i += 2
            }
        }
    }

    // ---------------- 方案库 ----------------

    fun requestNew() { newPrompt = true }

    fun dismissNewPrompt() { newPrompt = false }

    /** 开始新建：可选加载自然码模板，直接复用下方表单承担新建。 */
    fun startNew(withTemplate: Boolean) {
        newPrompt = false
        formMode = FormMode.NEW
        val (sheng, yun, yinjie) = if (withTemplate) {
            parseIni(templateContent())
        } else {
            Triple(
                LinkedHashMap<String, String>(),
                LinkedHashMap<String, String>(),
                LinkedHashMap<String, String>(),
            )
        }
        editModel = SchemeModel(
            name = "",
            sheng = sheng,
            yun = yun,
            yinjie = yinjie,
            newName = defaultSchemeName(),
        )
    }

    /** 自定义方案N：取 N 的最小排除值。 */
    fun defaultSchemeName(): String {
        val used = store.names().mapNotNull { name ->
            Regex("^自定义方案(\\d+)$").find(name)?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()
        var i = 1
        while (used.contains(i)) i++
        return "自定义方案$i"
    }

    fun editSelected() {
        if (selected.isEmpty()) { toast("先选择一个方案"); return }
        formMode = FormMode.EDIT
        tab = 1
        loadEditModel()
    }

    fun requestDelete(name: String? = null) {
        val target = name ?: selected
        if (target.isEmpty()) { toast("先选择一个方案"); return }
        deleting = target
    }

    fun dismissDialog() { editing = null; deleting = null; patches = null; iniEditing = null }

    fun dismissRequired() { requiredMissing = null }

    /** 打开 ini 编辑：新建时实时由表单生成，空项不形成一行。 */
    fun openIniEditor() {
        if (formMode == FormMode.NEW) {
            iniEditing = editModel?.let { buildIni(it) } ?: ""
            return
        }
        if (selected.isEmpty()) { toast("先选择一个方案"); return }
        iniEditing = store.iniOf(selected) ?: ""
    }

    /** 保存 ini（可同时改名）；新建时直接落盘为新方案并切换到编辑态。 */
    fun saveIni(newName: String, text: String) {
        val target = newName.trim()
        if (target.isEmpty()) { toast("名称不能为空"); return }
        val isNew = formMode == FormMode.NEW
        if (store.exists(target) && (isNew || target != selected)) { toast("名称已存在"); return }
        val pr = try { SchemeParser.parseWithPatches(text) }
        catch (e: IllegalArgumentException) { toast("解析失败：" + e.message); return }
        if (pr.scheme.table.isEmpty()) { toast("解析结果为空表"); return }
        if (!isNew && target != selected) store.rename(selected, target)
        store.put(target, pr.ini)
        store.setActive(target)
        selected = target
        formMode = FormMode.EDIT
        iniEditing = null
        refreshSchemes()
        loadEditModel()
        if (pr.patches.isNotEmpty()) patches = pr.patches else toast("已保存：$target")
    }

    fun saveDraft(draft: Draft) {
        val name = draft.name.trim()
        if (name.isEmpty()) { toast("名称不能为空"); return }
        if (draft.original == null && store.exists(name)) { toast("名称已存在"); return }
        val pr = try { SchemeParser.parseWithPatches(draft.content) }
        catch (e: IllegalArgumentException) { toast("解析失败：" + e.message); return }
        if (pr.scheme.table.isEmpty()) { toast("解析结果为空表"); return }
        if (draft.original != null && draft.original != name) store.rename(draft.original, name)
        store.put(name, pr.ini)
        store.setActive(name)
        selected = name
        formMode = FormMode.EDIT
        editing = null
        refreshSchemes()
        loadEditModel()
        if (pr.patches.isNotEmpty()) patches = pr.patches
        else toast("已保存：$name（${pr.scheme.table.size} 个键有候选）")
    }

    fun confirmDelete() {
        val name = deleting ?: return
        store.remove(name)
        deleting = null
        if (selected == name) selected = ""
        refreshSchemes()
    }

    fun importFrom(uri: Uri) {
        val text = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }.orEmpty()
        } catch (e: Exception) { toast("读取失败：" + e.message); return }
        if (text.isBlank()) { toast("文件为空"); return }
        editing = Draft(defaultSchemeName(), text, null)
    }

    // ---------------- 导出模板 ----------------

    fun exportTemplateName(): String = "sp26_ziranma.ini"

    fun templateContent(): String {
        return try {
            ctx.resources.openRawResource(R.raw.template_ziranma).bufferedReader().readText()
        } catch (e: Exception) {
            "# 模板加载失败\n"
        }
    }

    // ---------------- Tab2 结构编辑 ----------------

    fun loadEditModel() {
        if (formMode == FormMode.NEW) return
        if (selected.isEmpty()) { editModel = null; return }
        val ini = store.iniOf(selected) ?: run { editModel = null; return }
        val (sheng, yun, yinjie) = parseIni(ini)
        editModel = SchemeModel(
            name = selected,
            sheng = sheng,
            yun = yun,
            yinjie = yinjie,
            newName = selected,
        )
    }

    // 注意：map 必须换成新实例再 copy。若原地改 MutableMap 后 copy()，
    // 新旧实例逐字段相等（共享同一 map 引用），Compose 判为无变化不重组，输入框弹回旧值。
    fun setSheng(initial: String, key: String) {
        val m = editModel ?: return
        editModel = m.copy(sheng = LinkedHashMap(m.sheng).apply { put(initial, key) })
    }

    fun setYun(fin: String, key: String) {
        val m = editModel ?: return
        editModel = m.copy(yun = LinkedHashMap(m.yun).apply { put(fin, key) })
    }

    fun setYinjie(syl: String, code: String) {
        val m = editModel ?: return
        editModel = m.copy(yinjie = LinkedHashMap(m.yinjie).apply { put(syl, code) })
    }

    fun setSchemeName(name: String) {
        val m = editModel ?: return
        editModel = m.copy(newName = name)
    }

    /** 保存表单：先校验必填项，缺项时先弹确认。 */
    fun saveEditModel() {
        val m = editModel ?: return
        val target = m.newName.trim()
        if (target.isEmpty()) { toast("名称不能为空"); return }
        val editingExisting = formMode == FormMode.EDIT && m.name.isNotEmpty()
        if (store.exists(target) && (!editingExisting || target != m.name)) {
            toast("名称已存在"); return
        }
        val missing = missingRequired(m.sheng, m.yun, m.yinjie)
        if (missing.isNotEmpty()) { requiredMissing = missing; return }
        persistModel(m, target)
    }

    fun forceSaveEditModel() {
        val m = editModel ?: return
        requiredMissing = null
        persistModel(m, m.newName.trim())
    }

    private fun persistModel(m: SchemeModel, target: String) {
        if (target.isEmpty()) { toast("名称不能为空"); return }
        val ini = buildIni(m)
        val pr = try { SchemeParser.parseWithPatches(ini) }
        catch (e: IllegalArgumentException) { toast("解析失败：" + e.message); return }
        if (pr.scheme.table.isEmpty()) { toast("解析结果为空表"); return }
        if (formMode == FormMode.EDIT && m.name.isNotEmpty() && target != m.name) {
            store.rename(m.name, target)
        }
        store.put(target, pr.ini)
        store.setActive(target)
        selected = target
        formMode = FormMode.EDIT
        refreshSchemes()
        loadEditModel()
        toast("已保存：$target")
    }

    /** 把结构表还原成 ini 文本：一格里多个值用英文逗号分隔，空项不形成一行。 */
    private fun buildIni(m: SchemeModel): String {
        val sb = StringBuilder()
        fun appendSection(title: String, rows: List<String>) {
            if (rows.isEmpty()) return
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append('[').append(title).append("]\n")
            rows.forEach { sb.append(it).append('\n') }
        }
        appendSection("sheng", m.sheng.flatMap { (k, v) -> tokens(v, 1).map { "$k=$it" } })
        appendSection("yun", m.yun.flatMap { (k, v) -> tokens(v, 1).map { "$k=$it" } })
        appendSection("yinjie", m.yinjie.flatMap { (k, v) -> tokens(v, 2).map { "$k=$it" } })
        return sb.toString().trimEnd()
    }

    // ---------------- 生效 / 还原 / 重启 ----------------

    fun apply() {
        val cfg = ModuleConfig.load(ctx)
        cfg.mode = mode
        cfg.slot = slot
        cfg.table = ""

        if (mode == ModuleConfig.MODE_CUSTOM) {
            val pr = effectiveScheme(selected)
                ?: run { toast("请先新建或导入一个自定义方案"); return }
            if (pr.scheme.table.isEmpty()) { toast("解析结果为空表"); return }
            cfg.table = pr.scheme.flat
            store.setActive(selected)
            store.setInjectedNote("方案 $selected（全槽生效；未命中的键返回空，不回退原生）")
        } else if (mode == ModuleConfig.MODE_SLOT) {
            store.setInjectedNote("所有调用固定到内置槽 $slot（未替换表）")
        } else {
            store.setInjectedNote("")
        }
        cfg.publish(ctx)
        updateStatus()
        toast("已应用，目标进程在运行时立即生效")
    }

    fun restore() {
        val cfg = ModuleConfig.load(ctx)
        cfg.mode = ModuleConfig.MODE_OFF
        cfg.table = ""
        cfg.publish(ctx)
        store.setInjectedNote("已还原原生（注入为运行时行为，APK 从未被修改）")
        mode = ModuleConfig.MODE_OFF
        updateStatus()
        toast("已还原为原生行为")
    }

    fun restartTarget() {
        val cfg = ModuleConfig.load(ctx)
        cfg.exit = System.currentTimeMillis().toString()
        cfg.publish(ctx)
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(TARGET_PKG)
        } catch (ignored: Throwable) {}
        toast("已请求豆包输入法退出，再次点击输入框即重新加载")
    }

    // ---------------- 诊断日志 ----------------

    fun chooseDiag(on: Boolean) {
        val cfg = ModuleConfig.load(ctx)
        cfg.diag = on
        if (!on) cfg.diagContent = false  // 总开关关闭时联动收起二级开关
        cfg.publish(ctx)
        diag = on
        if (!on) diagContent = false
        toast(if (on) "已开启详细日志，目标进程 1 秒内生效" else "已关闭详细日志")
    }

    fun chooseDiagContent(on: Boolean) {
        val cfg = ModuleConfig.load(ctx)
        if (on && !cfg.diag) return  // 二级开关不能脱离详细日志单独开启
        cfg.diagContent = on
        cfg.publish(ctx)
        diagContent = on
        toast(if (on) "将记录展开的拼音内容，仅保存本机" else "已停止记录展开内容")
    }

    fun logText(): String = LogSink.tail(ctx, 200 * 1024)

    fun clearLog() {
        LogSink.clear(ctx)
        toast("已清空本机日志")
    }

    // ---------------- 状态文字 ----------------

    fun updateStatus() {
        val cfg = ModuleConfig.load(ctx)
        val sb = StringBuilder()
        sb.append("已保存：").append(
            when (cfg.mode) {
                ModuleConfig.MODE_CUSTOM -> "注入自定义（全槽生效，未命中不回退）"
                ModuleConfig.MODE_SLOT -> "切换内置槽"
                else -> "原生"
            }
        )
        if (cfg.mode == ModuleConfig.MODE_SLOT) sb.append("，槽 ").append(cfg.slot)
        sb.append('\n')

        if (selected.isNotEmpty()) {
            val pr = parsed
            if (pr != null) {
                sb.append("方案 ").append(selected)
                    .append("：").append(pr.scheme.table.size).append(" 键有候选\n")
            } else {
                sb.append("方案 ").append(selected).append("：解析失败\n")
            }
        }
        val note = store.injectedNote()
        if (note.isNotEmpty()) sb.append("上次注入：").append(note).append('\n')
        status = sb.toString().trimEnd()
    }

    private fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val TARGET_PKG = "com.bytedance.android.doubaoime"

        val INITIALS = listOf(
            "b", "c", "ch", "d", "f", "g", "h", "j", "k", "l", "m", "n",
            "p", "q", "r", "s", "sh", "t", "w", "x", "y", "z", "zh"
        )
        val FINALS = listOf(
            "a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "i", "ia", "ian",
            "iang", "iao", "ie", "in", "ing", "iong", "iu", "o", "ong", "ou", "u",
            "ua", "uai", "uan", "uang", "ue", "ui", "un", "uo", "v", "van", "ve", "vn"
        )
        val ZERO_SYLS = listOf(
            "a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "er", "o", "ou"
        )

        /** 单韵母：1.2 规定自映射，可以留空。 */
        val VOWELS = listOf("a", "e", "i", "o", "u", "v")

        /** 声母中没有默认值的三项。 */
        val REQUIRED_INITIALS = listOf("ch", "sh", "zh")

        /** 1.3 的化归/补足三组：同组只填一个时另一个可由规则推导。 */
        val FINAL_GROUPS = listOf(
            listOf("un", "vn"),
            listOf("uan", "van"),
            listOf("ue", "ve"),
        )

        fun hasValue(v: String?): Boolean =
            v?.split(',')?.any { it.trim().isNotEmpty() } == true

        /** 该韵母此时是否必填（同组另一项已填时可由规则推导，可空）。 */
        fun isRequiredFinal(fin: String, yun: Map<String, String>): Boolean {
            if (fin in VOWELS) return false
            for (g in FINAL_GROUPS) {
                if (fin in g) return g.none { it != fin && hasValue(yun[it]) }
            }
            return true
        }

        /** 必填项清单：声母 ch/sh/zh、除可推导者外的韵母、全部音节。 */
        fun missingRequired(
            sheng: Map<String, String>,
            yun: Map<String, String>,
            yinjie: Map<String, String>,
        ): List<String> {
            val out = mutableListOf<String>()
            REQUIRED_INITIALS.forEach { if (!hasValue(sheng[it])) out += "声母 $it" }
            FINALS.forEach { if (isRequiredFinal(it, yun) && !hasValue(yun[it])) out += "韵母 $it" }
            ZERO_SYLS.forEach { if (!hasValue(yinjie[it])) out += "音节 $it" }
            return out
        }

        private fun tokens(v: String, maxLen: Int): List<String> =
            v.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map { it.take(maxLen) }

        /** 把 ini 文本解析成 sheng / yun / yinjie 三张表（供结构编辑）。 */
        fun parseIni(ini: String): Triple<MutableMap<String, String>, MutableMap<String, String>, MutableMap<String, String>> {
            val sheng = LinkedHashMap<String, String>()
            val yun = LinkedHashMap<String, String>()
            val yinjie = LinkedHashMap<String, String>()
            fun put(map: MutableMap<String, String>, k: String, v: String) {
                map[k] = if (map.containsKey(k)) map[k] + "," + v else v
            }
            var section = ""
            for (raw in ini.split("\r?\n".toRegex())) {
                var line = raw
                val sc = line.indexOf(';')
                if (sc >= 0) line = line.substring(0, sc)
                line = line.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length - 1).trim().lowercase()
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val left = line.substring(0, eq).trim().lowercase()
                val right = line.substring(eq + 1).trim().lowercase()
                if (left.isEmpty() || right.isEmpty()) continue
                when (section) {
                    "sheng" -> put(sheng, left, right.substring(0, 1))
                    "yun" -> put(yun, left, right.substring(0, 1))
                    "yinjie" -> put(yinjie, left, right.take(2))
                }
            }
            return Triple(sheng, yun, yinjie)
        }
    }
}
