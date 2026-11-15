package sdlc.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private static final int MAX_DEPTH = 200;
    private final String s;
    private final boolean strict;
    private int i;
    private int depth;

    private Json(String s, boolean strict) {
        this.s = s;
        this.strict = strict;
    }

    public static Object parse(String text) {
        Json j = new Json(text, false);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw new IllegalArgumentException("trailing characters in JSON at " + j.i);
        return v;
    }

    public static Object parseStrict(String text) {
        Json j = new Json(text, true);
        try {
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) throw new IllegalArgumentException("there is more text after the end of the JSON value");
            return v;
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("the JSON ends too early (" + j.where(j.s.length()) + ")");
        } catch (IllegalArgumentException e) {
            String m = e.getMessage() == null ? "bad JSON" : e.getMessage().replaceFirst(" (in JSON )?at \\d+$", "").replace("unexpected end of JSON", "the JSON ends too early");
            throw new IllegalArgumentException(m + " (" + j.where(Math.min(j.i, j.s.length())) + ")");
        }
    }

    private String where(int offset) {
        int line = 1;
        int col = 1;
        for (int k = 0; k < offset; k++) {
            if (s.charAt(k) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return "line " + line + ", column " + col;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("expected a JSON object");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new java.util.LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }

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
        String number = s.substring(start, i);
        if (strict && !number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            i = start;
            throw new IllegalArgumentException("\"" + number + "\" is not a JSON number");
        }
        return Double.parseDouble(number);
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        if (++depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nested deeper than " + MAX_DEPTH + " levels");
        try {
            return members(m);
        } finally {
            depth--;
        }
    }

    private Map<String, Object> members(Map<String, Object> m) {
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            int at = i;
            String k = string();
            if (strict && m.containsKey(k)) {
                i = at;
                throw new IllegalArgumentException("the key \"" + k + "\" appears twice in one object");
            }
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
        if (++depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nested deeper than " + MAX_DEPTH + " levels");
        try {
            return items(l);
        } finally {
            depth--;
        }
    }

    private List<Object> items(List<Object> l) {
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
            if (strict && c < 0x20) {
                i--;
                throw new IllegalArgumentException("a line break or control character inside a string; write it as an escape such as \\n");
            }
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i++);
            if (strict && "\"\\/bfnrtu".indexOf(e) < 0) {
                i -= 2;
                throw new IllegalArgumentException("\"\\" + e + "\" is not a JSON escape");
            }
            if (strict && e == 'u' && !s.substring(i, i + 4).matches("[0-9a-fA-F]{4}")) {
                i -= 2;
                throw new IllegalArgumentException("\\u must be followed by four hexadecimal digits");
            }
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
