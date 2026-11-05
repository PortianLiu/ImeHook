package lpt.imehook;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/** 模块配置：模块 App 侧用普通 SharedPreferences 写，hook 侧用 XSharedPreferences 读。 */
public final class ModuleConfig {

    public static final String PACKAGE = "lpt.imehook";
    public static final String PREFS = "config";

    public static final String KEY_MODE = "mode";   // int: 0/1/2
    public static final String KEY_SLOT = "slot";   // int: 1..7
    public static final String KEY_TABLE = "table"; // String: 每行 key=py1,py2

    /** 关闭，完全原生行为 */
    public static final int MODE_OFF = 0;
    /** 启用内置隐藏槽位（改写 scheme id） */
    public static final int MODE_SLOT = 1;
    /** 完全自定义映射表 */
    public static final int MODE_CUSTOM = 2;

    public int mode = MODE_OFF;
    public int slot = 2;
    public String table = "";

    private ModuleConfig() {
    }

    /** hook 侧（目标进程内）读取配置；失败时返回 MODE_OFF 全默认。 */
    public static ModuleConfig loadFromHookSide() {
        ModuleConfig c = new ModuleConfig();
        try {
            XSharedPreferences p = new XSharedPreferences(PACKAGE, PREFS);
            p.makeWorldReadable();
            p.reload();
            if (p.getFile().canRead()) {
                c.mode = p.getInt(KEY_MODE, MODE_OFF);
                c.slot = p.getInt(KEY_SLOT, 2);
                c.table = p.getString(KEY_TABLE, "");
            } else {
                XposedBridge.log("[ImeHook] XSharedPreferences file unreadable, using defaults");
            }
        } catch (Throwable t) {
            XposedBridge.log("[ImeHook] read prefs failed: " + t);
        }
        return c;
    }
}
