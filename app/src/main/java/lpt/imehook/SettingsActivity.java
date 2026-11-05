package lpt.imehook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
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

public class SettingsActivity extends Activity {

    private static final int ID_OFF = 100;
    private static final int ID_SLOT = 101;
    private static final int ID_CUSTOM = 102;

    private static final String[] SLOT_LABELS = {
            "槽 1（方案未知，未验证）",
            "槽 2（自然码，原生可选）",
            "槽 3（方案未知，未验证）",
            "槽 4（方案未知，未验证）",
            "槽 5（方案未知，未验证）",
            "槽 6（小鹤，原生可选）",
            "槽 7（方案未知，未验证）",
    };

    private RadioGroup modeGroup;
    private Spinner slotSpinner;
    private EditText tableEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView modeLabel = new TextView(this);
        modeLabel.setText("模式");
        root.addView(modeLabel);

        modeGroup = new RadioGroup(this);
        modeGroup.addView(radio("关闭（原生行为）", ID_OFF));
        modeGroup.addView(radio("启用内置槽位（下方选择槽）", ID_SLOT));
        modeGroup.addView(radio("自定义方案（下方编辑映射表）", ID_CUSTOM));
        int mode = prefs.getInt(ModuleConfig.KEY_MODE, ModuleConfig.MODE_OFF);
        modeGroup.check(mode == ModuleConfig.MODE_SLOT ? ID_SLOT
                : mode == ModuleConfig.MODE_CUSTOM ? ID_CUSTOM : ID_OFF);
        root.addView(modeGroup);

        TextView slotLabel = new TextView(this);
        slotLabel.setText("内置槽位（仅槽位模式使用）");
        root.addView(slotLabel);

        slotSpinner = new Spinner(this);
        slotSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, SLOT_LABELS));
        int slot = prefs.getInt(ModuleConfig.KEY_SLOT, 2);
        slotSpinner.setSelection(Math.min(Math.max(slot, 1), 7) - 1);
        root.addView(slotSpinner);

        TextView tableLabel = new TextView(this);
        tableLabel.setText("自定义映射表（仅自定义模式使用），格式：每行 按键=拼音1,拼音2");
        root.addView(tableLabel);

        tableEdit = new EditText(this);
        tableEdit.setMinLines(6);
        tableEdit.setHint("例：\nvv=zhong,chong\nul=shuang");
        tableEdit.setText(prefs.getString(ModuleConfig.KEY_TABLE, ""));
        root.addView(tableEdit);

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> {
            int checked = modeGroup.getCheckedRadioButtonId();
            int newMode = checked == ID_SLOT ? ModuleConfig.MODE_SLOT
                    : checked == ID_CUSTOM ? ModuleConfig.MODE_CUSTOM
                    : ModuleConfig.MODE_OFF;
            prefs.edit()
                    .putInt(ModuleConfig.KEY_MODE, newMode)
                    .putInt(ModuleConfig.KEY_SLOT, slotSpinner.getSelectedItemPosition() + 1)
                    .putString(ModuleConfig.KEY_TABLE, tableEdit.getText().toString())
                    .apply();
            Toast.makeText(this, "已保存，请强制停止并重启豆包输入法", Toast.LENGTH_LONG).show();
        });
        root.addView(save);

        TextView note = new TextView(this);
        note.setText("说明：\n1. 保存后需强制停止并重启豆包输入法才生效；\n"
                + "2. 隐藏槽位（1/3/4/5/7）的方案身份与完整性尚未验证，行为异常属预期；\n"
                + "3. 自定义模式下未列出的按键回退到原生方案。");
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private RadioButton radio(String text, int id) {
        RadioButton b = new RadioButton(this);
        b.setText(text);
        b.setId(id);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
