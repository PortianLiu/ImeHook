package lpt.imehook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 解析 sp26.ini 形式的码表。
 *
 * 导入规则（详见 prompts/option3-custom-scheme.md）：
 *   1. 只识别 [sheng] / [yun] / [yinjie] 中列出的合法左名，其余行丢弃；
 *   2. 等号右侧只保留合法字母（声母/韵母取第一个，音节取前两个），为空则整行丢弃；
 *   3. 存储「修订后的原文」：不补单字母声母，但补单韵母、按三组别名规则化归；
 *   4. 模型由该原文生成：A/B 键值反转 → 双键叉乘 → [yinjie] 覆盖/追加 → 单键合并。
 *
 * 模型在每次读入内存时构建一次，native 热路径只查扁平表。
 */
public final class SchemeParser {

    private SchemeParser() {
    }

    private static final Set<String> SHENG_KEYS = new LinkedHashSet<>(Arrays.asList(
            "b", "c", "ch", "d", "f", "g", "h", "j", "k", "l", "m", "n",
            "p", "q", "r", "s", "sh", "t", "w", "x", "y", "z", "zh"));

    private static final Set<String> YUN_KEYS = new LinkedHashSet<>(Arrays.asList(
            "a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "i", "ia", "ian",
            "iang", "iao", "ie", "in", "ing", "iong", "iu", "o", "ong", "ou", "u",
            "ua", "uai", "uan", "uang", "ue", "ui", "un", "uo", "v", "van", "ve", "vn"));

    private static final Set<String> YINJIE_KEYS = new LinkedHashSet<>(Arrays.asList(
            "a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "er", "o", "ou"));

    /** 单字母声母：模型生成时补自身映射；不写回原文。 */
    private static final Set<String> SINGLE_INITIALS = new LinkedHashSet<>(Arrays.asList(
            "b", "c", "d", "f", "g", "h", "j", "k", "l", "m",
            "n", "p", "q", "r", "s", "t", "w", "x", "y", "z"));

    private static final List<String> VOWELS = Arrays.asList("a", "o", "e", "i", "u", "v");

    /** 只出现一个时统一为前者（改写左名，不新增另一项）。 */
    private static final String[][] ALIAS_RENAME = {{"un", "vn"}, {"uan", "van"}};
    /** 只出现一个时补足另一个（两项同时保留）。 */
    private static final String[][] ALIAS_ADD = {{"ue", "ve"}};

    public static final class Scheme {
        /** 最终查询表：键为 1 或 2 个字母，值为完整音节数组。 */
        public final Map<String, List<String>> table = new LinkedHashMap<>();
        /** 中间结果 4.3 A：声母反转收集（键位字母 → 声母），取自 buildModel 中途变量 a。 */
        public final Map<String, List<String>> initials = new LinkedHashMap<>();
        /** 中间结果 4.3 B：韵母反转收集（键位字母 → 韵母），取自 buildModel 中途变量 b。 */
        public final Map<String, List<String>> finals = new LinkedHashMap<>();
        /** 中间结果 4.6：音节键表（双键 4.6.1 + 单键聚合 4.6.2），与写入 table 的音节来源一致。 */
        public final Map<String, List<String>> syllables = new LinkedHashMap<>();
        public String flat = "";
    }

    public static final class ParseResult {
        public final Scheme scheme;
        /** 修订后的原文，即本模块所称的「原始 ini」，可直接持久化。 */
        public final String ini;
        public final List<String> patches;
        public ParseResult(Scheme scheme, String ini, List<String> patches) {
            this.scheme = scheme;
            this.ini = ini;
            this.patches = patches;
        }
    }

    /** 解析失败抛 IllegalArgumentException，异常消息可直接给用户看。 */
    public static Scheme parse(String ini) {
        return parseWithPatches(ini).scheme;
    }

    public static ParseResult parseWithPatches(String raw) {
        if (raw == null) throw new IllegalArgumentException("内容为空");
        Normalized n = normalize(raw);
        Scheme s = buildModel(n.lines);
        if (s.table.isEmpty()) throw new IllegalArgumentException("解析结果为空表");
        return new ParseResult(s, render(n.lines), n.patches);
    }

    // ---------------- 原文修订 ----------------

    private static final class Line {
        boolean content;
        String text;      // 非内容行原样
        String section;   // 内容行所属栏目
        String left;
        String right;
        String comment;   // 含前导分号
    }

    private static final class Normalized {
        final List<Line> lines;
        final List<String> patches;
        Normalized(List<Line> lines, List<String> patches) {
            this.lines = lines;
            this.patches = patches;
        }
    }

    private static Normalized normalize(String raw) {
        List<Line> lines = new ArrayList<>();
        List<String> patches = new ArrayList<>();

        String section = "";
        int dropped = 0;
        int yunHeader = -1;
        int yunLast = -1;

        for (String rawLine : raw.split("\n", -1)) {
            String text = rawLine.endsWith("\r")
                    ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            String trim = text.trim();

            if (trim.startsWith("[") && trim.endsWith("]")) {
                section = trim.substring(1, trim.length() - 1).trim().toLowerCase(Locale.ROOT);
                if (isKnownSection(section)) {
                    if (section.equals("yun")) yunHeader = lines.size();
                    lines.add(textLine(text));
                }
                continue;
            }
            if (trim.isEmpty() || trim.startsWith(";")) {
                lines.add(textLine(text));
                continue;
            }
            if (!isKnownSection(section)) {
                dropped++;
                continue;
            }

            int sc = text.indexOf(';');
            String code = sc >= 0 ? text.substring(0, sc) : text;
            String comment = sc >= 0 ? text.substring(sc) : "";
            int eq = code.indexOf('=');
            if (eq <= 0) {
                dropped++;
                continue;
            }

            String left = code.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String rightRaw = code.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
            if (!legalFor(section).contains(left)) {
                dropped++;
                continue;
            }
            int maxLen = section.equals("yinjie") ? 2 : 1;
            String right = sanitizeRight(rightRaw, maxLen);
            if (right.isEmpty()) {
                dropped++;
                continue;
            }

            Line l = contentLine(section, left, right);
            l.comment = comment;
            l.text = left + "=" + right + comment;
            lines.add(l);
            if (section.equals("yun")) yunLast = lines.size() - 1;
        }

        if (dropped > 0) patches.add("已忽略 " + dropped + " 行非法键名或取值");

        int insertPos = yunLast >= 0 ? yunLast + 1
                : yunHeader >= 0 ? yunHeader + 1 : lines.size();
        if (yunHeader < 0) {
            lines.add(textLine("[yun]"));
            insertPos = lines.size();
        }

        // 1.3 别名化归（先改名，再补足）
        Map<String, List<Line>> yunByLeft = yunByLeft(lines);
        for (String[] pair : ALIAS_RENAME) {
            String keep = pair[0], other = pair[1];
            if (!yunByLeft.containsKey(keep) && yunByLeft.containsKey(other)) {
                for (Line l : yunByLeft.get(other)) l.left = keep;
                patches.add("韵母 " + other + " 统一为 " + keep);
            }
        }
        yunByLeft = yunByLeft(lines);

        for (String[] pair : ALIAS_ADD) {
            String a = pair[0], b = pair[1];
            boolean hasA = yunByLeft.containsKey(a);
            boolean hasB = yunByLeft.containsKey(b);
            if (hasA == hasB) continue;
            String present = hasA ? a : b;
            String missing = hasA ? b : a;
            Set<String> keys = new LinkedHashSet<>();
            for (Line l : yunByLeft.get(present)) keys.add(l.right);
            for (String key : keys) {
                Line l = contentLine("yun", missing, key);
                lines.add(insertPos++, l);
                patches.add("韵母 " + missing + "=" + key + "（按 " + present + " 补足）");
            }
        }

        // 1.2 单韵母补足
        yunByLeft = yunByLeft(lines);
        for (String v : VOWELS) {
            if (yunByLeft.containsKey(v)) continue;
            lines.add(insertPos++, contentLine("yun", v, v));
            patches.add("韵母 " + v + " 未写，默认 " + v + "=" + v);
        }

        return new Normalized(lines, patches);
    }

    private static boolean isKnownSection(String section) {
        return section.equals("sheng") || section.equals("yun") || section.equals("yinjie");
    }

    private static Set<String> legalFor(String section) {
        switch (section) {
            case "sheng": return SHENG_KEYS;
            case "yun": return YUN_KEYS;
            default: return YINJIE_KEYS;
        }
    }

    /** 只保留字母与撇号，最多 maxLen 个；遇到非法字符即截断。 */
    private static String sanitizeRight(String raw, int maxLen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < maxLen; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || c == '\'') sb.append(c);
            else break;
        }
        return sb.toString();
    }

    private static Line textLine(String text) {
        Line l = new Line();
        l.text = text;
        return l;
    }

    private static Line contentLine(String section, String left, String right) {
        Line l = new Line();
        l.content = true;
        l.section = section;
        l.left = left;
        l.right = right;
        l.comment = "";
        l.text = left + "=" + right;
        return l;
    }

    private static Map<String, List<Line>> yunByLeft(List<Line> lines) {
        Map<String, List<Line>> out = new LinkedHashMap<>();
        for (Line l : lines) {
            if (!l.content || !l.section.equals("yun")) continue;
            List<Line> list = out.get(l.left);
            if (list == null) {
                list = new ArrayList<>();
                out.put(l.left, list);
            }
            list.add(l);
        }
        return out;
    }

    private static String render(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            Line l = lines.get(i);
            sb.append(l.content ? (l.left + "=" + l.right + l.comment) : l.text);
        }
        return sb.toString();
    }

    // ---------------- 模型生成 ----------------

    private static Scheme buildModel(List<Line> lines) {
        Map<Character, List<String>> a = new LinkedHashMap<>();
        Map<Character, List<String>> b = new LinkedHashMap<>();
        List<String[]> yinjie = new ArrayList<>();

        for (Line l : lines) {
            if (!l.content || l.right.isEmpty()) continue;
            switch (l.section) {
                case "sheng":
                    add(a, l.right.charAt(0), l.left);
                    break;
                case "yun":
                    add(b, l.right.charAt(0), l.left);
                    break;
                case "yinjie":
                    yinjie.add(new String[]{l.left, l.right});
                    break;
                default:
                    break;
            }
        }

        // 4.2：单字母声母补自身映射（只进模型，不改原文）
        for (String initial : SINGLE_INITIALS) {
            char key = initial.charAt(0);
            add(a, key, initial);
        }
        // 4.4.2：等号右侧从未出现的字母不生成项，因此不构造空声母占位；
        // 纯韵母键只来自 [yinjie] 的二字母码。

        Scheme s = new Scheme();
        Map<String, List<String>> table = s.table;

        // 中间结果 4.3：声母反转表（A）与韵母反转表（B），直接取自中途变量
        for (Map.Entry<Character, List<String>> e : a.entrySet()) {
            s.initials.put(String.valueOf(e.getKey()), new ArrayList<>(e.getValue()));
        }
        for (Map.Entry<Character, List<String>> e : b.entrySet()) {
            s.finals.put(String.valueOf(e.getKey()), new ArrayList<>(e.getValue()));
        }

        // 4.5：A × B 叉乘
        for (Map.Entry<Character, List<String>> ea : a.entrySet()) {
            for (Map.Entry<Character, List<String>> eb : b.entrySet()) {
                String key = String.valueOf(ea.getKey()) + eb.getKey();
                for (String initial : ea.getValue()) {
                    for (String fin : eb.getValue()) {
                        add(table, key, initial + fin);
                    }
                }
            }
        }

        // 4.6.1：二字母音节键直接追加（同时落入中间结果音节键表的双键部分）
        for (String[] e : yinjie) {
            if (e[1].length() == 2) {
                add(s.syllables, e[1], e[0]);
                add(table, e[1], e[0]);
            }
        }

        // 4.6.2：二字母键取首字母聚合（中间结果音节键表单键部分）
        for (String[] e : yinjie) {
            if (e[1].length() == 2) {
                add(s.syllables, e[1].substring(0, 1), e[0]);
                add(table, e[1].substring(0, 1), e[0]);
            }
        }
        // 音节栏的单字母键（中间结果音节键表单键部分）
        for (String[] e : yinjie) {
            if (e[1].length() == 1) {
                add(s.syllables, e[1], e[0]);
                add(table, e[1], e[0]);
            }
        }
        // 声母反转后的单字母键（如 b→b）；空声母占位不产生单键值
        for (Map.Entry<Character, List<String>> e : a.entrySet()) {
            for (String initial : e.getValue()) {
                if (!initial.isEmpty()) add(table, String.valueOf(e.getKey()), initial);
            }
        }

        StringBuilder flat = new StringBuilder();
        for (Map.Entry<String, List<String>> e : table.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            flat.append(e.getKey()).append('=').append(join(e.getValue())).append('\n');
        }
        s.flat = flat.toString();
        return s;
    }

    private static void add(Map<Character, List<String>> map, char key, String value) {
        List<String> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>();
            map.put(key, list);
        }
        if (!list.contains(value)) list.add(value);
    }

    private static void add(Map<String, List<String>> map, String key, String value) {
        List<String> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>();
            map.put(key, list);
        }
        if (!list.contains(value)) list.add(value);
    }

    private static String join(List<String> vs) {
        StringBuilder sb = new StringBuilder();
        for (String v : vs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        return sb.toString();
    }
}
