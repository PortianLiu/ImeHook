package lpt.imehook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析 sp26.ini 形式的码表，反转键值，产出接近目标 APK 内部结构的 exact/prefix，
 * 并离线展开成 双键码 → 音节数组 的扁平表（供 native hook 直接使用）。
 *
 * 导入时会先做一轮「兜底补全」，补全后的内容才会被存进 app：
 *   1. a o e i u v 六个韵母若未写，默认映射到自身字母。
 *   2. 除 zh ch sh 外的单字母声母若未写，默认映射到自身字母。
 *   3. vn/un、ve/ue、van/uan 若只出现其一，另一个按同键补全。
 *   4. [sheng] 中 键=~ 表示该键是零声母占位键（识别后从声母表移除，归入 zeroInitial）。
 *
 * 零声母有三种常见处理方式，导入时自动猜测，用户可在管理页修改：
 *   ZI_FIXED            指定固定键作为零声母（~ 声明或从音节栏首键推断）
 *   ZI_FIRST_LETTER     纯韵母的首字母
 *   ZI_FIRST_LETTER_FULL 纯韵母的首字母，但双字母韵母采用全拼第二字母
 *   ZI_OTHER            其它（无法识别时）
 */
public final class SchemeParser {

    private SchemeParser() {
    }

    public static final int ZI_FIXED = 0;
    public static final int ZI_FIRST_LETTER = 1;
    public static final int ZI_FIRST_LETTER_FULL = 2;
    public static final int ZI_OTHER = 3;

    /** 单字母声母（zh ch sh 之外），默认映射到自身字母。 */
    private static final Set<String> SINGLE_INITIALS = new LinkedHashSet<>(Arrays.asList(
            "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
            "j", "q", "x", "r", "z", "c", "s", "y", "w"));

    private static final Set<String> VOWELS = new LinkedHashSet<>(Arrays.asList("a", "o", "e", "i", "u", "v"));

    /** vn/un、ve/ue、van/uan 互为别名，导入时只写一个就补另一个。 */
    private static final String[][] ALIASES = {{"vn", "un"}, {"ve", "ue"}, {"van", "uan"}};

    public static final class Guess {
        public final int mode;
        public final String key;
        public Guess(int mode, String key) { this.mode = mode; this.key = key; }
    }

    public static final class ParseResult {
        public final Scheme scheme;
        public final List<String> patches;
        public final Guess guess;
        /** 补全后的 ini 文本：存进 app 的就是这一份，也是「编辑 ini」里展示的内容。 */
        public final String ini;
        public ParseResult(Scheme scheme, List<String> patches, Guess guess, String ini) {
            this.scheme = scheme;
            this.patches = patches;
            this.guess = guess;
            this.ini = ini;
        }
    }

    public static final class Scheme {
        public final Map<String, List<String>> exact = new LinkedHashMap<>();
        public final Map<Character, List<String>> a = new LinkedHashMap<>();
        public final Map<Character, List<String>> b = new LinkedHashMap<>();
        public final Set<Character> placeholders = new LinkedHashSet<>();
        public final Map<String, List<String>> table = new LinkedHashMap<>();
        public int syllables;
        public String flat;
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
        return parseWithPatches(ini).scheme;
    }

    public static ParseResult parseWithPatches(String ini) {
        if (ini == null) throw new IllegalArgumentException("内容为空");

        Map<String, String> sheng = new LinkedHashMap<>();
        Map<String, String> yun = new LinkedHashMap<>();
        List<String[]> yinjie = new ArrayList<>();
        List<String> patches = new ArrayList<>();

        String zeroKeyFromTilde = null;

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
            if (left.isEmpty()) continue;
            switch (section) {
                case "sheng":
                    if ("~".equals(right)) {
                        if (left.length() == 1) {
                            zeroKeyFromTilde = left;
                            patches.add("零声母占位键：" + left + "（来自 " + left + "=~）");
                        }
                    } else if (!right.isEmpty()) {
                        sheng.put(left, right.substring(0, 1));
                    }
                    break;
                case "yun":
                    if (!right.isEmpty()) yun.put(left, right.substring(0, 1));
                    break;
                case "yinjie":
                    if (!right.isEmpty()) yinjie.add(new String[]{left, right});
                    break;
                default:
                    break;
            }
        }

        // ---- 兜底补全 ----
        // 1. a o e i u v 韵母自映射
        for (String v : VOWELS) {
            if (!yun.containsKey(v)) {
                yun.put(v, v);
                patches.add("韵母 " + v + " 未写，默认 " + v + "=" + v);
            }
        }
        // 2. 单字母声母自映射
        for (String init : SINGLE_INITIALS) {
            if (!sheng.containsKey(init)) {
                sheng.put(init, init);
                patches.add("声母 " + init + " 未写，默认 " + init + "=" + init);
            }
        }
        // 3. 别名 vn/un、ve/ue、van/uan
        for (String[] pair : ALIASES) {
            boolean hasA = yun.containsKey(pair[0]);
            boolean hasB = yun.containsKey(pair[1]);
            if (hasA && !hasB) {
                yun.put(pair[1], yun.get(pair[0]));
                patches.add("韵母 " + pair[1] + " 未写，按 " + pair[0] + " 同键补全为 " + yun.get(pair[0]));
            } else if (hasB && !hasA) {
                yun.put(pair[0], yun.get(pair[1]));
                patches.add("韵母 " + pair[0] + " 未写，按 " + pair[1] + " 同键补全为 " + yun.get(pair[1]));
            }
        }
        // 双字母 sh 之外：zh/ch/sh 保留原值；若未写则不补（它们不映射到单字母）。

        if (sheng.isEmpty() && yinjie.isEmpty()) {
            throw new IllegalArgumentException("未找到 [sheng] 或 [yinjie] 段");
        }

        Scheme s = new Scheme();

        // 声母：键 -> A
        for (Map.Entry<String, String> e : sheng.entrySet()) {
            char key = e.getValue().charAt(0);
            List<String> list = s.a.get(key);
            if (list == null) { list = new ArrayList<>(); s.a.put(key, list); }
            if (list.isEmpty()) list.add(e.getKey());
        }

        // 韵母：键 -> B
        for (Map.Entry<String, String> e : yun.entrySet()) {
            char key = e.getValue().charAt(0);
            List<String> list = s.b.get(key);
            if (list == null) { list = new ArrayList<>(); s.b.put(key, list); }
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
            if (list == null) { list = new ArrayList<>(); s.exact.put(code, list); }
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

        Guess guess = guessZeroInitial(zeroKeyFromTilde, yinjie, yun);
        return new ParseResult(s, patches, guess, buildIni(sheng, yun, yinjie));
    }

    /** 把补全后的三张表拼回 ini 文本（补全项会出现在里面）。 */
    private static String buildIni(Map<String, String> sheng, Map<String, String> yun, List<String[]> yinjie) {
        StringBuilder sb = new StringBuilder();
        sb.append("[sheng]\n");
        for (Map.Entry<String, String> e : sheng.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        sb.append("\n[yun]\n");
        for (Map.Entry<String, String> e : yun.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        sb.append("\n[yinjie]\n");
        for (String[] e : yinjie) sb.append(e[0]).append('=').append(e[1]).append('\n');
        return sb.toString();
    }

    /** 猜测零声母方案。优先用 ~ 声明；其次从音节栏的首键规律推断。 */
    private static Guess guessZeroInitial(String tildeKey, List<String[]> yinjie, Map<String, String> yun) {
        if (tildeKey != null) return new Guess(ZI_FIXED, tildeKey);

        // 收集零声音节（纯韵母）
        List<String> zeroSyls = new ArrayList<>();
        for (String v : VOWELS) zeroSyls.add(v);
        for (String f : Arrays.asList("ai", "ei", "ao", "ou", "an", "en", "ang", "eng", "er", "ong")) {
            zeroSyls.add(f);
        }

        int firstLetterHits = 0, fullHits = 0, total = 0;
        Map<String, String> sylToCode = new LinkedHashMap<>();
        for (String[] e : yinjie) sylToCode.put(e[0], e[1]);

        for (String syl : zeroSyls) {
            String code = sylToCode.get(syl);
            if (code == null || code.length() < 2) continue;
            total++;
            if (code.charAt(0) == syl.charAt(0)) firstLetterHits++;
            if (syl.length() == 2 && code.charAt(0) == syl.charAt(0) && code.charAt(1) == syl.charAt(1)) fullHits++;
        }

        if (total > 0) {
            if (fullHits >= total / 2) return new Guess(ZI_FIRST_LETTER_FULL, "");
            if (firstLetterHits >= total / 2) return new Guess(ZI_FIRST_LETTER, "");
        }
        // 退化为固定键：看所有零声音节首键是否一致
        Set<Character> firsts = new LinkedHashSet<>();
        for (String syl : zeroSyls) {
            String code = sylToCode.get(syl);
            if (code != null && !code.isEmpty()) firsts.add(code.charAt(0));
        }
        if (firsts.size() == 1) {
            char c = firsts.iterator().next();
            return new Guess(ZI_FIXED, String.valueOf(c));
        }
        return new Guess(ZI_OTHER, "");
    }

    /**
     * 按零声母方案改写零声音节的 exact 映射，返回同一个 Scheme（原地修改并重建 flat）。
     * priority=true  时以方案生成的为准，覆盖 [yinjie]；
     * priority=false 时只补 [yinjie] 没写的音节。
     */
    public static Scheme applyZeroInitial(Scheme s, int mode, String key, boolean priority) {
        if (mode == ZI_OTHER) return s;

        Map<String, String> yun = new LinkedHashMap<>();
        for (Map.Entry<Character, List<String>> e : s.b.entrySet()) {
            for (String f : e.getValue()) {
                if (!yun.containsKey(f)) yun.put(f, String.valueOf(e.getKey()));
            }
        }

        List<String> zeroSyls = new ArrayList<>(VOWELS);
        zeroSyls.addAll(Arrays.asList("ai", "ei", "ao", "ou", "an", "en", "ang", "eng", "er", "ong"));

        for (String syl : zeroSyls) {
            String code = zeroCode(syl, mode, key, yun);
            if (code == null) continue;
            List<String> list = s.exact.get(code);
            if (list == null) {
                if (!priority && s.exact.values().stream().anyMatch(l -> l.contains(syl))) continue;
                list = new ArrayList<>();
                s.exact.put(code, list);
            } else {
                if (priority) list.clear();
                else if (list.contains(syl)) continue;
            }
            if (!list.contains(syl)) list.add(syl);
        }

        // 重建 flat 表
        s.table.clear();
        Set<Character> letters = new LinkedHashSet<>();
        for (char c = 'a'; c <= 'z'; c++) letters.add(c);
        Set<Character> tail = new LinkedHashSet<>(letters);
        tail.addAll(s.a.keySet());
        tail.addAll(s.b.keySet());
        for (String k : s.exact.keySet()) if (!k.isEmpty()) tail.add(k.charAt(k.length() - 1));
        List<String> keys = new ArrayList<>();
        for (char c : letters) keys.add(String.valueOf(c));
        for (char c1 : letters) for (char c2 : tail) keys.add("" + c1 + c2);
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

    private static String zeroCode(String syl, int mode, String key, Map<String, String> yun) {
        String finKey = yun.get(syl);
        switch (mode) {
            case ZI_FIXED:
                if (key == null || key.length() != 1) return null;
                if (syl.length() == 1) return key + syl;
                return finKey == null ? null : key + finKey;
            case ZI_FIRST_LETTER:
                if (syl.isEmpty()) return null;
                if (syl.length() == 1) return syl + syl;
                return finKey == null ? null : syl.charAt(0) + finKey;
            case ZI_FIRST_LETTER_FULL:
                if (syl.isEmpty()) return null;
                if (syl.length() == 1) return syl + syl;
                if (syl.length() == 2) return syl;
                return finKey == null ? null : syl.charAt(0) + finKey;
            default:
                return null;
        }
    }

    private static List<String> expand(String key, Scheme s) {
        List<String> out = new ArrayList<>();
        if (key.isEmpty()) return out;
        char c0 = key.charAt(0);
        if (!Character.isLetter(c0)) { out.add(key); return out; }
        List<String> hit = s.exact.get(key);
        if (hit != null) { out.addAll(hit); return out; }
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
