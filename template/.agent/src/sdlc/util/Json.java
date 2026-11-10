package sdlc.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON: parses to Map / List / String / Double / Boolean / null, and writes the same. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw new IllegalArgumentException("trailing characters in JSON at " + j.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("expected a JSON object");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }

    /** Value at a dotted path such as "fields.status.name", or null. */
    public static Object at(Object root, String path) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(part);
        }
        return cur;
    }

    public static String str(Object root, String path) {
        Object v = at(root, path);
        return v == null ? "" : v instanceof Double d && d == Math.rint(d) ? String.valueOf(d.longValue()) : v.toString();
    }

    private Object value() {
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw new IllegalArgumentException("bad JSON at " + i);
        return Double.parseDouble(s.substring(start, i));
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            if (s.charAt(i) == ',') { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (s.charAt(i) == ',') { i++; continue; }
            expect(']');
            return l;
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> sb.append(e);
            }
        }
        i++;
        return sb.toString();
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected '" + c + "' in JSON at " + i);
        i++;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    public static String write(Object o) {
        if (o == null) return "null";
        if (o instanceof String str) return quote(str);
        if (o instanceof Integer || o instanceof Long || o instanceof Boolean) return o.toString();
        if (o instanceof Number n) return n.doubleValue() == Math.rint(n.doubleValue()) ? String.valueOf(n.longValue()) : n.toString();
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (sb.length() > 1) sb.append(',');
                sb.append(quote(String.valueOf(e.getKey()))).append(':').append(write(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (Object v : l) {
                if (sb.length() > 1) sb.append(',');
                sb.append(write(v));
            }
            return sb.append(']').toString();
        }
        return quote(o.toString());
    }

    /** Indented output for files people read. */
    public static String pretty(Object o) {
        return pretty(o, "");
    }

    private static String pretty(Object o, String indent) {
        String next = indent + "  ";
        if (o instanceof Map<?, ?> m && !m.isEmpty()) {
            StringBuilder sb = new StringBuilder("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(next).append(quote(String.valueOf(e.getKey()))).append(": ").append(pretty(e.getValue(), next));
                sb.append(++n < m.size() ? ",\n" : "\n");
            }
            return sb.append(indent).append('}').toString();
        }
        if (o instanceof List<?> l && !l.isEmpty()) {
            StringBuilder sb = new StringBuilder("[\n");
            for (int k = 0; k < l.size(); k++) sb.append(next).append(pretty(l.get(k), next)).append(k + 1 < l.size() ? ",\n" : "\n");
            return sb.append(indent).append(']').toString();
        }
        return write(o);
    }

    public static String quote(String str) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
