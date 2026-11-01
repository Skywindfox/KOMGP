package komgp.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析/序列化。
 *
 * <p>只实现项目需要的部分，不引入任何第三方依赖：
 * 对象 -> {@link LinkedHashMap}，数组 -> {@link ArrayList}，数字 -> {@link Double}，
 * 布尔 -> {@link Boolean}，字符串 -> {@link String}，null -> {@code null}。
 *
 * <p>之所以自己写：Malody 谱面里可能出现注释、尾逗号、超大整数（时间戳），
 * 用一个宽容的实现比引入 Jackson 更容易控制解析行为。
 */
public final class Json {

    private Json() {}

    public static final class ParseException extends RuntimeException {
        public ParseException(String msg) { super(msg); }
    }

    // ------------------------------------------------------------------ 解析

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        return v;
    }

    /** 宽容解析：允许注释、尾逗号；失败返回 null。 */
    public static Object parseLenient(String text) {
        try {
            return parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                    while (i < s.length() && s.charAt(i) != '\n') i++;
                } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                    i = Math.min(s.length(), i + 2);
                } else {
                    break;
                }
            }
        }

        Object value() {
            if (i >= s.length()) throw new ParseException("意外的文件结尾");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private void expect(String lit) {
            if (s.regionMatches(i, lit, 0, lit.length())) {
                i += lit.length();
            } else {
                throw new ParseException("期望 " + lit + " @" + i);
            }
        }

        Map<String, Object> object() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skipWs();
                if (i < s.length() && s.charAt(i) == '}') { i++; return m; } // 尾逗号
                String k = string();
                skipWs();
                if (i >= s.length() || s.charAt(i) != ':') throw new ParseException("缺少 ':' @" + i);
                i++;
                skipWs();
                m.put(k, value());
                skipWs();
                if (i >= s.length()) throw new ParseException("对象未闭合");
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw new ParseException("对象中出现 '" + c + "' @" + i);
            }
        }

        List<Object> array() {
            ArrayList<Object> l = new ArrayList<>();
            i++; // [
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                skipWs();
                if (i < s.length() && s.charAt(i) == ']') { i++; return l; } // 尾逗号
                l.add(value());
                skipWs();
                if (i >= s.length()) throw new ParseException("数组未闭合");
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return l; }
                throw new ParseException("数组中出现 '" + c + "' @" + i);
            }
        }

        String string() {
            if (i >= s.length() || s.charAt(i) != '"') throw new ParseException("期望字符串 @" + i);
            i++;
            StringBuilder sb = new StringBuilder(32);
            while (true) {
                if (i >= s.length()) throw new ParseException("字符串未闭合");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw new ParseException("转义未闭合");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new ParseException("\\u 不完整");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            }
        }

        Double number() {
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || ((c == '-' || c == '+') && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E'))) {
                    i++;
                } else {
                    break;
                }
            }
            if (start == i) throw new ParseException("无法识别的数值 @" + i);
            return Double.valueOf(Double.parseDouble(s.substring(start, i)));
        }
    }

    // ------------------------------------------------------------------ 序列化

    public static String write(Object v, boolean pretty) {
        StringBuilder sb = new StringBuilder(8192);
        writeValue(sb, v, pretty, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, boolean pretty, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, pretty, depth + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                if (pretty) sb.append(' ');
                writeValue(sb, e.getValue(), pretty, depth + 1);
            }
            newline(sb, pretty, depth);
            sb.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) sb.append(',');
                newline(sb, pretty, depth + 1);
                writeValue(sb, l.get(k), pretty, depth + 1);
            }
            newline(sb, pretty, depth);
            sb.append(']');
        } else if (v instanceof String str) {
            writeString(sb, str);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void newline(StringBuilder sb, boolean pretty, int depth) {
        if (!pretty) return;
        sb.append('\n');
        for (int k = 0; k < depth; k++) sb.append("  ");
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ 读取辅助

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArr(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : null;
    }

    /** 按路径取值：{@code get(root, "meta", "song", "title")} */
    public static Object get(Object root, String... path) {
        Object cur = root;
        for (String p : path) {
            Map<String, Object> m = asObj(cur);
            if (m == null) return null;
            cur = m.get(p);
        }
        return cur;
    }

    public static String str(Object o, String def) {
        if (o instanceof String s) return s;
        if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
        return def;
    }

    public static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static int i(Object o, int def) {
        return (int) Math.round(num(o, def));
    }

    public static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0;
        if (o instanceof String s) return "true".equalsIgnoreCase(s) || "1".equals(s);
        return def;
    }
}
