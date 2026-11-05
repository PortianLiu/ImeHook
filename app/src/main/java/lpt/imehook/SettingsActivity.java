package lpt.imehook;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

public class SettingsActivity extends Activity {

    private static final String TARGET_PKG = "com.bytedance.android.doubaoime";

    /** 单选按钮 id 与 ModuleConfig 模式值显式对应，避免顺序错位。 */
    private static final int RDO_NATIVE = 101;
    private static final int RDO_SLOT = 102;
    private static final int RDO_CUSTOM = 103;

    private static final int REQ_IMPORT = 1001;

    /** 槽位身份：2/6 有字符串实测，其余为键位指纹推断（不保证）。 */
    private static final String[] SLOT_LABELS = {
            "槽 1 · 智能ABC双拼（推断，不保证）",
            "槽 2 · 自然码（原生可选）",
            "槽 3 · 搜狗双拼（推断，不保证）",
            "槽 4 · 微软双拼（推断，不保证）",
            "槽 5 · 拼音加加双拼（推断，不保证）",
            "槽 6 · 小鹤（原生可选）",
            "槽 7 · 紫光双拼（推断，不保证）",
    };

    private SharedPreferences prefs;
    private SchemeStore store;

    private RadioGroup modeGroup;
    private Spinner schemeSpinner;
    private Spinner slotSpinner;
    private TextView slotTitle;
    private EditText testInput;
    private TextView testResult;
    private SchemeParser.Scheme current;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE);
        store = new SchemeStore(prefs);
        ModuleConfig saved = ModuleConfig.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // ---- 模式 ----
        root.addView(label("模式"));
        modeGroup = new RadioGroup(this);
        modeGroup.addView(radio("原生（不干预，跟随豆包自身的方案设置）", RDO_NATIVE));
        modeGroup.addView(radio("切换内置槽位（只改写方案 id，不替换表）", RDO_SLOT));
        modeGroup.addView(radio("注入自定义方案（改写 id 并替换该槽的表）", RDO_CUSTOM));
        modeGroup.check(modeToRadio(saved.mode));
        root.addView(modeGroup);

        // ---- 方案库 ----
        root.addView(label("自定义方案库"));
        schemeSpinner = new Spinner(this);
        root.addView(schemeSpinner);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("新建", v -> showSchemeDialog(null, "", "")));
        row.addView(button("导入文件", v -> pickFile()));
        row.addView(button("重命名", v -> showRenameDialog()));
        row.addView(button("删除", v -> confirmDelete()));
        root.addView(row);

        // ---- 槽位 ----
        slotTitle = label("目标槽位");
        root.addView(slotTitle);
        slotSpinner = new Spinner(this);
        slotSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, SLOT_LABELS));
        int slot = saved.slot;
        slotSpinner.setSelection(Math.min(Math.max(slot, 1), 7) - 1);
        root.addView(slotSpinner);

        // ---- 试打 ----
        root.addView(label("试打（输入双键当场查看解出的拼音，与注入内容无关）"));
        testInput = new EditText(this);
        testInput.setSingleLine(true);
        testInput.setHint("例如 we、sf、ni、zh");
        root.addView(testInput);
        testResult = new TextView(this);
        testResult.setPadding(0, dp(6), 0, 0);
        root.addView(testResult);

        // ---- 操作 ----
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button("应用设置", v -> apply()));
        actions.addView(button("还原原生", v -> restore()));
        root.addView(actions);
        root.addView(button("重启豆包输入法", v -> restartTarget()));

        status = new TextView(this);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status);

        root.addView(label("说明"));
        TextView note = new TextView(this);
        note.setText("1. 注入是运行时行为：只在豆包输入法进程内 hook，不修改 APK、不写目标数据，"
                + "强制停止/重启后自动恢复原生；配置存在本模块内。\n"
                + "2. 改完设置点「应用设置」即生效，不需要重启；「重启豆包输入法」也不需要 root。\n"
                + "3. 槽 1/3/4/5/7 的方案身份是键位指纹推断（智能ABC/搜狗/微软/拼音加加/紫光），"
                + "完整性未验证，行为异常属预期；槽 2/6 为原生可选。\n"
                + "4. 自定义模式下，表内未覆盖的键会回退到目标槽的原生展开结果。");
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        modeGroup.setOnCheckedChangeListener((g, id) -> onModeChanged());
        schemeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View v, int pos, long id) {
                String name = selectedScheme();
                if (!name.isEmpty()) store.setActive(name);
                loadCurrent();
                runTest();
                updateStatus();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        testInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                runTest();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        refreshSchemes();
        onModeChanged();
        loadCurrent();
        updateStatus();
    }

    // ---------------- 模式 ----------------

    private int radioToMode(int radioId) {
        if (radioId == RDO_SLOT) return ModuleConfig.MODE_SLOT;
        if (radioId == RDO_CUSTOM) return ModuleConfig.MODE_CUSTOM;
        return ModuleConfig.MODE_OFF;
    }

    private int modeToRadio(int mode) {
        if (mode == ModuleConfig.MODE_SLOT) return RDO_SLOT;
        if (mode == ModuleConfig.MODE_CUSTOM) return RDO_CUSTOM;
        return RDO_NATIVE;
    }

    private int currentMode() {
        return radioToMode(modeGroup.getCheckedRadioButtonId());
    }

    private void onModeChanged() {
        int mode = currentMode();
        boolean usesSlot = mode != ModuleConfig.MODE_OFF;
        slotSpinner.setEnabled(usesSlot);
        slotSpinner.setAlpha(usesSlot ? 1f : 0.4f);
        slotTitle.setText(mode == ModuleConfig.MODE_CUSTOM
                ? "注入目标槽位（表会覆盖这个槽）"
                : mode == ModuleConfig.MODE_SLOT
                ? "切换目标槽位（使用该槽的原生表）"
                : "目标槽位（原生模式下不使用）");
    }

    // ---------------- 方案库 ----------------

    private void refreshSchemes() {
        List<String> names = store.names();
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names);
        schemeSpinner.setAdapter(ad);
        String active = store.active();
        int idx = names.indexOf(active);
        if (idx >= 0) schemeSpinner.setSelection(idx);
        else if (!names.isEmpty()) store.setActive(names.get(0));
    }

    private String selectedScheme() {
        Object o = schemeSpinner.getSelectedItem();
        return o == null ? "" : o.toString();
    }

    /** 把当前选中方案的解析结果装进内存，供试打使用。 */
    private void loadCurrent() {
        String name = selectedScheme();
        current = null;
        if (name.isEmpty()) return;
        String ini = store.iniOf(name);
        try {
            current = SchemeParser.parse(ini);
        } catch (Exception ignored) {
            current = null;
        }
    }

    /** 试打：按每 2 个字符一段查表，显示候选拼音。 */
    private void runTest() {
        String raw = testInput.getText().toString().trim().toLowerCase();
        if (raw.isEmpty()) {
            testResult.setText("");
            return;
        }
        if (current == null) {
            testResult.setText("当前方案未解析成功");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i += 2) {
            String k = raw.substring(i, Math.min(i + 2, raw.length()));
            List<String> v = current.table.get(k);
            sb.append(k).append(" → ");
            if (v == null || v.isEmpty()) {
                sb.append("（无候选）");
            } else {
                for (int j = 0; j < v.size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(v.get(j));
                }
            }
            sb.append('\n');
        }
        testResult.setText(sb.toString());
    }

    private void showSchemeDialog(String originalName, String nameText, String content) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, pad);

        EditText nameEdit = new EditText(this);
        nameEdit.setHint("方案名（如 小鹤改进版）");
        nameEdit.setText(nameText);
        box.addView(nameEdit);

        EditText contentEdit = new EditText(this);
        contentEdit.setHint("[sheng]\nzh=v\nch=i\n\n[yun]\niai=d\n\n[yinjie]\na=aa");
        contentEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        contentEdit.setMinLines(10);
        contentEdit.setText(content);
        box.addView(contentEdit);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(originalName == null ? "新建方案" : "编辑 " + originalName)
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = nameEdit.getText().toString().trim();
                    String text = contentEdit.getText().toString();
                    if (name.isEmpty()) {
                        toast("请填写方案名");
                        return;
                    }
                    try {
                        SchemeParser.Scheme s = SchemeParser.parse(text);
                        if (s.table.isEmpty()) throw new IllegalArgumentException("解析结果为空表");
                        store.put(name, text, s.normalizedJson());
                        store.setActive(name);
                        dlg.dismiss();
                        refreshSchemes();
                        loadCurrent(); runTest();
                        updateStatus();
                        toast("已保存：" + name + "（" + s.table.size() + " 个键）");
                    } catch (IllegalArgumentException e) {
                        toast("解析失败：" + e.getMessage());
                    }
                }));
        dlg.show();
    }

    private void showRenameDialog() {
        String old = selectedScheme();
        if (old.isEmpty()) {
            toast("先选择一个方案");
            return;
        }
        EditText edit = new EditText(this);
        edit.setText(old);
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(edit)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    String name = edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("名称不能为空");
                    } else if (!store.rename(old, name)) {
                        toast("名称已存在或未变化");
                    } else {
                        refreshSchemes();
                        loadCurrent(); runTest();
                        updateStatus();
                    }
                })
                .show();
    }

    private void confirmDelete() {
        String name = selectedScheme();
        if (name.isEmpty()) {
            toast("先选择一个方案");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除 " + name + "？")
                .setPositiveButton("删除", (d, w) -> {
                    store.remove(name);
                    refreshSchemes();
                    loadCurrent(); runTest();
                    updateStatus();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 导入 ----------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            String name = uri.getLastPathSegment();
            if (name != null && name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
            if (name != null && name.lastIndexOf('.') > 0) name = name.substring(0, name.lastIndexOf('.'));
            showSchemeDialog(null, name == null ? "" : name, sb.toString());
        } catch (Exception e) {
            toast("读取失败：" + e.getMessage());
        }
    }

    // ---------------- 应用 / 还原 / 重启 ----------------

    private void apply() {
        int mode = currentMode();
        int slot = slotSpinner.getSelectedItemPosition() + 1;
        String name = selectedScheme();

        ModuleConfig cfg = ModuleConfig.load(this);
        cfg.mode = mode;
        cfg.slot = slot;
        cfg.table = "";

        if (mode == ModuleConfig.MODE_CUSTOM) {
            String ini = store.iniOf(name);
            if (ini == null) {
                toast("请先新建或导入一个自定义方案");
                return;
            }
            try {
                SchemeParser.Scheme s = SchemeParser.parse(ini);
                if (s.table.isEmpty()) throw new IllegalArgumentException("解析结果为空表");
                cfg.table = s.flat;
            } catch (IllegalArgumentException e) {
                toast("解析失败：" + e.getMessage());
                return;
            }
            store.setActive(name);
            store.setInjectedNote("方案 " + name
                    + " → 槽 " + slot + "（运行时注入，原件未修改，还原=关闭注入）");
        } else if (mode == ModuleConfig.MODE_SLOT) {
            store.setInjectedNote("内置槽 " + slot + "（未替换表）");
        } else {
            store.setInjectedNote("");
        }
        cfg.publish(this);
        updateStatus();
        toast("已应用，目标进程在运行时立即生效");
    }

    private void restore() {
        ModuleConfig cfg = ModuleConfig.load(this);
        cfg.mode = ModuleConfig.MODE_OFF;
        cfg.table = "";
        cfg.publish(this);
        store.setInjectedNote("已还原原生（注入为运行时行为，APK 从未被修改）");
        modeGroup.check(RDO_NATIVE);
        onModeChanged();
        updateStatus();
        toast("已还原为原生行为");
    }

    /**
     * 重启目标 App 不需要 root：
     * 1) 配置里下发一个新的 exit 标记，目标进程内的钩子收到后自杀；
     * 2) 再调 killBackgroundProcesses 兜底（钩子没装进进程时，输入法通常在后台）。
     */
    private void restartTarget() {
        ModuleConfig cfg = ModuleConfig.load(this);
        cfg.exit = String.valueOf(System.currentTimeMillis());
        cfg.publish(this);
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            am.killBackgroundProcesses(TARGET_PKG);
        } catch (Throwable ignored) {
        }
        toast("已请求豆包输入法退出，再次点击输入框即重新加载");
    }

    // ---------------- 状态 ----------------

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        ModuleConfig cfg = ModuleConfig.load(this);
        int mode = cfg.mode;
        int slot = cfg.slot;
        sb.append("已保存：")
                .append(mode == ModuleConfig.MODE_CUSTOM ? "注入自定义"
                        : mode == ModuleConfig.MODE_SLOT ? "切换内置槽" : "原生")
                .append(mode == ModuleConfig.MODE_OFF ? "" : "，槽 " + slot)
                .append('\n');

        String name = selectedScheme();
        if (!name.isEmpty()) {
            String ini = store.iniOf(name);
            try {
                SchemeParser.Scheme s = SchemeParser.parse(ini);
                sb.append("方案 ").append(name)
                        .append("：").append(s.table.size()).append(" 键有候选")
                        .append("，占位键 ").append(s.placeholders).append('\n');
            } catch (Exception e) {
                sb.append("方案 ").append(name).append("：解析失败（").append(e.getMessage()).append("）\n");
            }
        }
        String inj = store.injectedNote();
        if (!inj.isEmpty()) sb.append("上次注入：").append(inj).append('\n');
        status.setText(sb.toString());
    }

    // ---------------- 小工具 ----------------

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private RadioButton radio(String text, int id) {
        RadioButton b = new RadioButton(this);
        b.setText(text);
        b.setId(id);
        return b;
    }

    private Button button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
