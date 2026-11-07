package lpt.imehook;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 自定义方案库：名称 + 用户 ini 原文，存在模块自己的 SharedPreferences。 */
public final class SchemeStore {

    public static final String KEY_SCHEMES = "schemes";
    public static final String KEY_ACTIVE = "active_scheme";
    public static final String KEY_INJECTED = "injected_note";

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

    private JSONObject find(String name) {
        JSONArray arr = array();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(o.optString("name"))) return o;
        }
        return null;
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
        return find(name) != null;
    }

    public String iniOf(String name) {
        JSONObject o = find(name);
        return o == null ? null : o.optString("ini", "");
    }

    /** 保存修订后的 ini 原文；模型不落盘，读入时现算。 */
    public void put(String name, String ini) {
        JSONArray arr = array();
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(o.optString("name"))) arr.remove(i);
        }
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("ini", ini);
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
                try { o.put("name", to); } catch (Exception ignored) {}
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
