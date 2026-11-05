package lpt.imehook;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 自定义方案库：名称 / 原始 ini / 归一化结构，全部存在模块自己的 SharedPreferences。 */
public final class SchemeStore {

    public static final String KEY_SCHEMES = "schemes";        // JSON 数组
    public static final String KEY_ACTIVE = "active_scheme";   // 当前选中的方案名
    public static final String KEY_INJECTED = "injected_note"; // 上次注入的记录（提示用）

    private final SharedPreferences prefs;

    public SchemeStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    private JSONArray array() {
        try {
            return new JSONArray(prefs.getString(KEY_SCHEMES, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void write(JSONArray arr) {
        prefs.edit().putString(KEY_SCHEMES, arr.toString()).apply();
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        JSONArray arr = array();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o.optString("name"));
        }
        return out;
    }

    public boolean exists(String name) {
        return names().contains(name);
    }

    public String iniOf(String name) {
        JSONArray arr = array();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(o.optString("name"))) {
                return o.optString("ini", "");
            }
        }
        return null;
    }

    public String normalizedOf(String name) {
        JSONArray arr = array();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(o.optString("name"))) {
                return o.optString("normalized", "{}");
            }
        }
        return null;
    }

    public void put(String name, String ini, String normalized) {
        JSONArray arr = array();
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(o.optString("name"))) arr.remove(i);
        }
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("ini", ini);
            o.put("normalized", normalized);
            arr.put(o);
        } catch (Exception ignored) {
        }
        write(arr);
    }

    public void remove(String name) {
        JSONArray arr = array();
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(o.optString("name"))) arr.remove(i);
        }
        write(arr);
        if (name.equals(active())) prefs.edit().remove(KEY_ACTIVE).apply();
    }

    public boolean rename(String from, String to) {
        if (from.equals(to) || exists(to)) return false;
        JSONArray arr = array();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && from.equals(o.optString("name"))) {
                try {
                    o.put("name", to);
                } catch (Exception ignored) {
                }
            }
        }
        write(arr);
        if (from.equals(active())) prefs.edit().putString(KEY_ACTIVE, to).apply();
        return true;
    }

    public String active() {
        return prefs.getString(KEY_ACTIVE, "");
    }

    public void setActive(String name) {
        prefs.edit().putString(KEY_ACTIVE, name).apply();
    }

    public String injectedNote() {
        return prefs.getString(KEY_INJECTED, "");
    }

    public void setInjectedNote(String note) {
        prefs.edit().putString(KEY_INJECTED, note).apply();
    }
}
