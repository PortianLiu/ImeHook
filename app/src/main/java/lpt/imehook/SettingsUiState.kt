package lpt.imehook

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import lpt.imehook.SchemeParser.Guess

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
    var renaming by mutableStateOf<String?>(null)
        private set
    var deleting by mutableStateOf<String?>(null)
        private set
    var patches by mutableStateOf<List<String>?>(null)
        private set

    /** Tab2 中正在编辑的方案结构表。 */
    var editModel by mutableStateOf<SchemeModel?>(null)
        private set

    private var parsed: SchemeParser.Scheme? = null

    data class Draft(val name: String, val content: String, val original: String?)

    data class SchemeModel(
        val name: String,
        val sheng: MutableMap<String, String>,
        val yun: MutableMap<String, String>,
        val yinjie: MutableList<Pair<String, String>>,
        var ziMode: Int,
        var ziKey: String,
        var ziPriority: Boolean,
    )

    init {
        reloadScheme()
        updateStatus()
    }

    // ---------------- 基本操作 ----------------

    fun chooseMode(m: Int) { mode = m }
    fun chooseSlot(s: Int) { slot = s }
    fun chooseTab(t: Int) {
        tab = t
        if (t == 1) loadEditModel()
    }

    fun select(name: String) {
        if (name == selected) return
        selected = name
        if (name.isNotEmpty()) store.setActive(name)
        reloadScheme()
        if (tab == 1) loadEditModel()
        runTest()
        updateStatus()
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
        runTest()
        updateStatus()
    }

    private fun reloadScheme() {
        parsed = effectiveScheme(selected)
    }

    /** 解析 + 应用零声母方案，得到实际用于试打 / 注入的表。 */
    private fun effectiveScheme(name: String): SchemeParser.Scheme? {
        if (name.isEmpty()) return null
        val ini = store.iniOf(name) ?: return null
        val s = try { SchemeParser.parse(ini) } catch (e: Exception) { return null }
        val mode = store.zeroInitialMode(name)
        val key = store.zeroInitialKey(name)
        val pri = store.zeroInitialPriority(name)
        return SchemeParser.applyZeroInitial(s, mode, key, pri)
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
                val v = s.table[k]
                if (v.isNullOrEmpty()) append("（无候选）")
                else append(v.joinToString(", "))
                append('\n')
                i += 2
            }
        }
    }

    // ---------------- 方案库 ----------------

    fun newScheme() { editing = Draft("", "", null) }

    fun editSelected() {
        if (selected.isEmpty()) { toast("先选择一个方案"); return }
        tab = 1
        loadEditModel()
    }

    fun requestRename() {
        if (selected.isEmpty()) { toast("先选择一个方案"); return }
        renaming = selected
    }

    fun requestDelete() {
        if (selected.isEmpty()) { toast("先选择一个方案"); return }
        deleting = selected
    }

    fun dismissDialog() { editing = null; renaming = null; deleting = null; patches = null }

    fun saveDraft(draft: Draft) {
        val name = draft.name.trim()
        if (name.isEmpty()) { toast("名称不能为空"); return }
        if (draft.original == null && store.exists(name)) { toast("名称已存在"); return }
        val pr = try { SchemeParser.parseWithPatches(draft.content) }
        catch (e: IllegalArgumentException) { toast("解析失败：" + e.message); return }
        if (pr.scheme.table.isEmpty()) { toast("解析结果为空表"); return }
        if (draft.original != null && draft.original != name) store.rename(draft.original, name)
        store.put(name, draft.content, pr.scheme.normalizedJson(),
            pr.guess.mode, pr.guess.key, false)
        store.setActive(name)
        selected = name
        editing = null
        refreshSchemes()
        if (pr.patches.isNotEmpty()) patches = pr.patches
        else toast("已保存：$name（${pr.scheme.table.size} 个键有候选）")
    }

    fun renameTo(newName: String) {
        val old = renaming ?: return
        val name = newName.trim()
        if (name.isEmpty()) { toast("名称不能为空"); return }
        if (!store.rename(old, name)) { toast("名称已存在或未变化"); return }
        if (selected == old) selected = name
        renaming = null
        refreshSchemes()
    }

    fun confirmDelete() {
        val name = deleting ?: return
        store.remove(name)
        deleting = null
        if (selected == name) selected = ""
        editModel = null
        refreshSchemes()
    }

    fun importFrom(uri: Uri) {
        val text = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }.orEmpty()
        } catch (e: Exception) { toast("读取失败：" + e.message); return }
        if (text.isBlank()) { toast("文件为空"); return }
        editing = Draft(deriveName(uri), text, null)
    }

    private fun deriveName(uri: Uri): String {
        var name = uri.lastPathSegment ?: return "导入方案"
        name = name.substringAfterLast('/')
        if (name.lastIndexOf('.') > 0) name = name.substring(0, name.lastIndexOf('.'))
        return name.ifBlank { "导入方案" }
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
        if (selected.isEmpty()) { editModel = null; return }
        val ini = store.iniOf(selected) ?: run { editModel = null; return }
        val (sheng, yun, yinjie) = parseIni(ini)
        editModel = SchemeModel(
            name = selected,
            sheng = sheng,
            yun = yun,
            yinjie = yinjie.toMutableList(),
            ziMode = store.zeroInitialMode(selected),
            ziKey = store.zeroInitialKey(selected),
            ziPriority = store.zeroInitialPriority(selected),
        )
    }

    fun setSheng(initial: String, key: String) {
        val m = editModel ?: return
        m.sheng[initial] = key.take(1)
        editModel = m.copy()
    }

    fun setYun(final: String, key: String) {
        val m = editModel ?: return
        m.yun[final] = key.take(1)
        editModel = m.copy()
    }

    fun setYinjie(syl: String, code: String) {
        val m = editModel ?: return
        val idx = m.yinjie.indexOfFirst { it.first == syl }
        if (idx >= 0) m.yinjie[idx] = syl to code
        else m.yinjie.add(syl to code)
        editModel = m.copy()
    }

    fun setZiMode(mode: Int) {
        val m = editModel ?: return
        editModel = m.copy(ziMode = mode)
    }

    fun setZiKey(key: String) {
        val m = editModel ?: return
        editModel = m.copy(ziKey = key.take(1))
    }

    fun setZiPriority(pri: Boolean) {
        val m = editModel ?: return
        editModel = m.copy(ziPriority = pri)
    }

    fun saveEditModel() {
        val m = editModel ?: return
        val ini = buildIni(m)
        val pr = try { SchemeParser.parseWithPatches(ini) }
        catch (e: IllegalArgumentException) { toast("解析失败：" + e.message); return }
        if (pr.scheme.table.isEmpty()) { toast("解析结果为空表"); return }
        store.put(m.name, ini, pr.scheme.normalizedJson(), m.ziMode, m.ziKey, m.ziPriority)
        reloadScheme()
        runTest()
        updateStatus()
        toast("已保存：${m.name}")
    }

    /** 把结构表还原成 ini 文本。 */
    private fun buildIni(m: SchemeModel): String {
        val sb = StringBuilder()
        sb.append("[sheng]\n")
        for ((k, v) in m.sheng) {
            if (v.isNotEmpty()) sb.append("$k=$v\n")
        }
        sb.append("\n[yun]\n")
        for ((k, v) in m.yun) {
            if (v.isNotEmpty()) sb.append("$k=$v\n")
        }
        sb.append("\n[yinjie]\n")
        for ((syl, code) in m.yinjie) {
            if (code.isNotEmpty()) sb.append("$syl=$code\n")
        }
        return sb.toString()
    }

    // ---------------- 生效 / 还原 / 重启 ----------------

    fun apply() {
        val cfg = ModuleConfig.load(ctx)
        cfg.mode = mode
        cfg.slot = slot
        cfg.table = ""

        if (mode == ModuleConfig.MODE_CUSTOM) {
            val s = effectiveScheme(selected)
            if (s == null) { toast("请先新建或导入一个自定义方案"); return }
            if (s.table.isEmpty()) { toast("解析结果为空表"); return }
            cfg.table = s.flat
            store.setActive(selected)
            store.setInjectedNote("方案 $selected → 槽 $slot（运行时注入，原件未修改，还原=关闭注入）")
        } else if (mode == ModuleConfig.MODE_SLOT) {
            store.setInjectedNote("内置槽 $slot（未替换表）")
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

    // ---------------- 状态文字 ----------------

    fun updateStatus() {
        val cfg = ModuleConfig.load(ctx)
        val sb = StringBuilder()
        sb.append("已保存：").append(
            when (cfg.mode) {
                ModuleConfig.MODE_CUSTOM -> "注入自定义"
                ModuleConfig.MODE_SLOT -> "切换内置槽"
                else -> "原生"
            }
        )
        if (cfg.mode != ModuleConfig.MODE_OFF) sb.append("，槽 ").append(cfg.slot)
        sb.append('\n')

        if (selected.isNotEmpty()) {
            val s = parsed
            if (s != null) {
                sb.append("方案 ").append(selected)
                    .append("：").append(s.table.size).append(" 键有候选")
                    .append("，占位键 ").append(s.placeholders).append('\n')
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
            "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
            "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s", "y", "w"
        )
        val FINALS = listOf(
            "a", "o", "e", "i", "u", "v", "ai", "ei", "ui", "ao", "ou", "iu",
            "ie", "ve", "er", "an", "en", "in", "un", "ang", "eng", "ing", "ong",
            "ia", "ua", "uo", "uai", "iao", "ian", "uan", "iang", "uang", "iong"
        )
        val ZERO_SYLS = listOf(
            "a", "o", "e", "i", "u", "v", "ai", "ei", "ao", "ou",
            "an", "en", "ang", "eng", "er", "ong"
        )

        fun ziModeLabel(mode: Int): String = when (mode) {
            SchemeParser.ZI_FIXED -> "指定固定键"
            SchemeParser.ZI_FIRST_LETTER -> "纯韵母首字母"
            SchemeParser.ZI_FIRST_LETTER_FULL -> "纯韵母首字母（双字母全拼）"
            else -> "其它"
        }

        fun Guess.toLabel(): String =
            ziModeLabel(mode) + (if (mode == SchemeParser.ZI_FIXED && key.isNotEmpty()) " · 键 $key" else "")

        /** 把 ini 文本解析成 sheng / yun / yinjie 三张表（供结构编辑）。 */
        fun parseIni(ini: String): Triple<MutableMap<String, String>, MutableMap<String, String>, MutableList<Pair<String, String>>> {
            val sheng = LinkedHashMap<String, String>()
            val yun = LinkedHashMap<String, String>()
            val yinjie = mutableListOf<Pair<String, String>>()
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
                    "sheng" -> sheng[left] = right.substring(0, 1)
                    "yun" -> yun[left] = right.substring(0, 1)
                    "yinjie" -> yinjie.add(left to right)
                }
            }
            return Triple(sheng, yun, yinjie)
        }
    }
}
