package lpt.imehook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析 sp26.ini 形式的码表，反转键值，产出接近目标 APK 内部结构的 exact/prefix，
 * 并离线展开成 双键码 → 音节数组 的扁平表（供 native hook 直接使用）。
 *
 * ini 三段：
 *   [sheng]  声母=键        例: zh=v
 *   [yun]    韵母=键        例: ian=m，一键多韵可写多行
 *   [yinjie] 音节=两键编码   例: ang=ah（最高优先级，写入 exact）
 */
public final class SchemeParser {

    private SchemeParser() {
    }

    public static final class Scheme {
        /** 字面键串 -> 音节列表（最高优先级） */
        public final Map<String, List<String>> exact = new LinkedHashMap<>();
        /** 键 -> 声母（A）；[ "" ] 表示零声母占位键 */
        public final Map<Character, List<String>> a = new LinkedHashMap<>();
        /** 键 -> 韵母（B） */
        public final Map<Character, List<String>> b = new LinkedHashMap<>();
        public final Set<Character> placeholders = new LinkedHashSet<>();
        /** 展开结果：键串 -> 候选拼音 */
        public final Map<String, List<String>> table = new LinkedHashMap<>();
        public int syllables;
        public String flat;

        /** 直接存进模块里的「接近目标 APK」结构 */
        public String normalizedJson() {
            try {
                JSONObject root = new JSONObject();
                JSONArray prefixArr = new JSONArray();
                for (Map.Entry<Character, List<String>> e : a.entrySet()) {
                    JSONObject o = new JSONObject();
                    o.put("key", String.valueOf(e.getKey()));
                    o.put("A", toArray(e.getValue()));
                    o.put("B", toArray(b.getOrDefault(e.getKey(), new ArrayList<>())));
                    prefixArr.put(o);
                }
                root.put("exact", toObject(exact));
                root.put("prefix", prefixArr);
                List<String> ph = new ArrayList<>();
                for (char c : placeholders) ph.add(String.valueOf(c));
                root.put("placeholders", toArray(ph));
                return root.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    /** 解析失败抛 IllegalArgumentException，异常消息可直接给用户看。 */
    public static Scheme parse(String ini) {
        if (ini == null) throw new IllegalArgumentException("内容为空");

        Map<String, String> sheng = new LinkedHashMap<>();
        Map<String, String> yun = new LinkedHashMap<>();
        // yinjie 允许同一音节写多行（字面键 + 占位键），不能用 Map
        List<String[]> yinjie = new ArrayList<>();

        String section = "";
        for (String raw : ini.split("\r?\n")) {
            String line = raw;
            int sc = line.indexOf(';');
            if (sc >= 0) line = line.substring(0, sc);
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim().toLowerCase();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String left = line.substring(0, eq).trim().toLowerCase();
            String right = line.substring(eq + 1).trim().toLowerCase();
            if (left.isEmpty() || right.isEmpty()) continue;
            switch (section) {
                case "sheng":
                    sheng.put(left, right.substring(0, 1));
                    break;
                case "yun":
                    yun.put(left, right.substring(0, 1));
                    break;
                case "yinjie":
                    yinjie.add(new String[]{left, right});
                    break;
                default:
                    break;
            }
        }
        if (sheng.isEmpty() && yinjie.isEmpty()) {
            throw new IllegalArgumentException("未找到 [sheng] 或 [yinjie] 段");
        }

        Scheme s = new Scheme();

        // 声母：键 -> A（同键多声母只保留第一个）
        for (Map.Entry<String, String> e : sheng.entrySet()) {
            char key = e.getValue().charAt(0);
            List<String> list = s.a.get(key);
            if (list == null) {
                list = new ArrayList<>();
                s.a.put(key, list);
            }
            if (list.isEmpty()) list.add(e.getKey());
        }

        // 韵母：键 -> B（保留出现顺序）
        for (Map.Entry<String, String> e : yun.entrySet()) {
            char key = e.getValue().charAt(0);
            List<String> list = s.b.get(key);
            if (list == null) {
                list = new ArrayList<>();
                s.b.put(key, list);
            }
            if (!list.contains(e.getKey())) list.add(e.getKey());
        }

        // 零声母占位键：作为 yinjie 编码首字符、但没有任何声母落在该键上
        for (String[] e : yinjie) {
            String code = e[1];
            if (code.isEmpty()) continue;
            char c0 = code.charAt(0);
            if (!Character.isLetter(c0)) continue;
            if (!s.a.containsKey(c0)) s.placeholders.add(c0);
        }
        for (char c : s.placeholders) {
            List<String> list = new ArrayList<>();
            list.add("");
            s.a.put(c, list);
        }

        // 音节最高优先级
        for (String[] e : yinjie) {
            String code = e[1];
            if (code.length() > 2) continue;
            List<String> list = s.exact.get(code);
            if (list == null) {
                list = new ArrayList<>();
                s.exact.put(code, list);
            }
            if (!list.contains(e[0])) list.add(e[0]);
        }
        s.syllables = s.exact.size();

        // 展开
        Set<Character> letters = new LinkedHashSet<>();
        for (char c = 'a'; c <= 'z'; c++) letters.add(c);
        Set<Character> tail = new LinkedHashSet<>(letters);
        tail.addAll(s.a.keySet());
        tail.addAll(s.b.keySet());
        for (String k : s.exact.keySet()) {
            if (!k.isEmpty()) tail.add(k.charAt(k.length() - 1));
        }

        List<String> keys = new ArrayList<>();
        for (char c : letters) keys.add(String.valueOf(c));
        for (char c1 : letters) {
            for (char c2 : tail) keys.add("" + c1 + c2);
        }
        StringBuilder flat = new StringBuilder();
        for (String k : keys) {
            List<String> vals = expand(k, s);
            if (vals.isEmpty()) continue;
            s.table.put(k, vals);
            flat.append(k).append('=').append(join(vals)).append('\n');
        }
        s.flat = flat.toString();
        return s;
    }

    /** 与 sub_59953C 的规则一致，另加「占位键 + 字面次键」兜底。 */
    private static List<String> expand(String key, Scheme s) {
        List<String> out = new ArrayList<>();
        if (key.isEmpty()) return out;

        char c0 = key.charAt(0);
        if (!Character.isLetter(c0)) {
            out.add(key);
            return out;
        }
        List<String> hit = s.exact.get(key);
        if (hit != null) {
            out.addAll(hit);
            return out;
        }
        List<String> a0 = s.a.get(c0);
        if (a0 == null || a0.isEmpty()) return out;

        String initial = a0.get(0);
        if (key.length() == 1) {
            if (!initial.isEmpty()) out.add(initial);
            return out;
        }
        char c1 = key.charAt(1);
        List<String> finals = s.b.get(c1);
        if (finals == null || finals.isEmpty()) {
            if (initial.isEmpty()) out.add(String.valueOf(c1));
            else out.add(initial);
            return out;
        }
        for (String f : finals) out.add(initial + f);
        return out;
    }

    private static String join(List<String> vs) {
        StringBuilder sb = new StringBuilder();
        for (String v : vs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        return sb.toString();
    }

    private static JSONArray toArray(List<String> vs) {
        JSONArray arr = new JSONArray();
        for (String v : vs) arr.put(v);
        return arr;
    }

    private static JSONObject toObject(Map<String, List<String>> map) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            o.put(e.getKey(), toArray(e.getValue()));
        }
        return o;
    }
}
