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

    /** 弹窗：新建 / 编辑（original=null 表示新建） */
    var editing by mutableStateOf<Draft?>(null)
        private set
    var renaming by mutableStateOf<String?>(null)
        private set
    var deleting by mutableStateOf<String?>(null)
        private set

    private var parsed: SchemeParser.Scheme? = null

    data class Draft(val name: String, val content: String, val original: String?)

    init {
        reloadScheme()
        updateStatus()
    }

    // ---------------- 基本操作 ----------------

    fun chooseMode(m: Int) {
        mode = m
    }

    fun chooseSlot(s: Int) {
        slot = s
    }

    fun select(name: String) {
        if (name == selected) return
        selected = name
        if (name.isNotEmpty()) store.setActive(name)
        reloadScheme()
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
        parsed = null
        if (selected.isEmpty()) return
        val ini = store.iniOf(selected)
        parsed = try {
            SchemeParser.parse(ini)
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- 试打 ----------------

    fun runTest() {
        val raw = testInput.trim().lowercase()
        if (raw.isEmpty()) {
            testResult = ""
            return
        }
        val s = parsed
        if (s == null) {
            testResult = "当前方案未解析成功"
            return
        }
        testResult = buildString {
            var i = 0
            while (i < raw.length) {
                val k = raw.substring(i, minOf(i + 2, raw.length))
                append(k).append(" → ")
                val v = s.table[k]
                if (v.isNullOrEmpty()) {
                    append("（无候选）")
                } else {
                    append(v.joinToString(", "))
                }
                append('\n')
                i += 2
            }
        }
    }

    // ---------------- 方案库 ----------------

    fun newScheme() {
        editing = Draft("", "", null)
    }

    fun editSelected() {
        if (selected.isEmpty()) {
            toast("先选择一个方案")
            return
        }
        val ini = store.iniOf(selected) ?: ""
        editing = Draft(selected, ini, selected)
    }

    fun requestRename() {
        if (selected.isEmpty()) {
            toast("先选择一个方案")
            return
        }
        renaming = selected
    }

    fun requestDelete() {
        if (selected.isEmpty()) {
            toast("先选择一个方案")
            return
        }
        deleting = selected
    }

    fun dismissDialog() {
        editing = null
        renaming = null
        deleting = null
    }

    fun saveDraft(draft: Draft) {
        val name = draft.name.trim()
        if (name.isEmpty()) {
            toast("名称不能为空")
            return
        }
        if (draft.original == null && store.exists(name)) {
            toast("名称已存在")
            return
        }
        val scheme = try {
            SchemeParser.parse(draft.content)
        } catch (e: IllegalArgumentException) {
            toast("解析失败：" + e.message)
            return
        }
        if (scheme.table.isEmpty()) {
            toast("解析结果为空表")
            return
        }
        if (draft.original != null && draft.original != name) {
            store.rename(draft.original, name)
        }
        store.put(name, draft.content, scheme.normalizedJson())
        store.setActive(name)
        selected = name
        editing = null
        refreshSchemes()
        toast("已保存：$name（${scheme.table.size} 个键有候选）")
    }

    fun renameTo(newName: String) {
        val old = renaming ?: return
        val name = newName.trim()
        if (name.isEmpty()) {
            toast("名称不能为空")
            return
        }
        if (!store.rename(old, name)) {
            toast("名称已存在或未变化")
            return
        }
        if (selected == old) selected = name
        renaming = null
        refreshSchemes()
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
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            }.orEmpty()
        } catch (e: Exception) {
            toast("读取失败：" + e.message)
            return
        }
        if (text.isBlank()) {
            toast("文件为空")
            return
        }
        editing = Draft(deriveName(uri), text, null)
    }

    private fun deriveName(uri: Uri): String {
        var name = uri.lastPathSegment ?: return "导入方案"
        name = name.substringAfterLast('/')
        if (name.lastIndexOf('.') > 0) name = name.substring(0, name.lastIndexOf('.'))
        return name.ifBlank { "导入方案" }
    }

    // ---------------- 生效 / 还原 / 重启 ----------------

    fun apply() {
        val cfg = ModuleConfig.load(ctx)
        cfg.mode = mode
        cfg.slot = slot
        cfg.table = ""

        if (mode == ModuleConfig.MODE_CUSTOM) {
            val ini = store.iniOf(selected)
            if (ini == null) {
                toast("请先新建或导入一个自定义方案")
                return
            }
            val s = try {
                SchemeParser.parse(ini)
            } catch (e: IllegalArgumentException) {
                toast("解析失败：" + e.message)
                return
            }
            if (s.table.isEmpty()) {
                toast("解析结果为空表")
                return
            }
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
        } catch (ignored: Throwable) {
        }
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
    }
}
