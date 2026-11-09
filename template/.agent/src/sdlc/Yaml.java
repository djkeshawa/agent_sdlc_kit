package sdlc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small YAML reader for configuration files. Supported: block mappings and sequences,
 * sequences of mappings, plain / 'single' / "double" quoted scalars, comments, flow
 * collections on one line ([a, b], {k: v}), and | or > block scalars. Not supported:
 * anchors, aliases, tags, multi-document files, flow collections over several lines.
 * Scalars stay strings (except null and ~); Config converts types when read.
 */
final class Yaml {
    private record Line(int indent, String text, String raw, int number) {}

    private final List<Line> lines = new ArrayList<>();
    private int pos;

    private Yaml(String text) {
        String[] raw = text.replace("\r\n", "\n").replace('\t', ' ').split("\n", -1);
        for (int n = 0; n < raw.length; n++) {
            String r = raw[n];
            if (r.trim().equals("---") || r.trim().equals("...")) continue;
            String noComment = stripComment(r);
            if (noComment.isBlank()) {
                lines.add(new Line(-1, "", r, n + 1)); // kept for block scalars
                continue;
            }
            int indent = 0;
            while (indent < noComment.length() && noComment.charAt(indent) == ' ') indent++;
            lines.add(new Line(indent, noComment.trim(), r, n + 1));
        }
    }

    static Object parse(String text) {
        Yaml y = new Yaml(text);
        y.skipBlank();
        if (y.pos >= y.lines.size()) return new LinkedHashMap<String, Object>();
        Object v = y.node(y.lines.get(y.pos).indent);
        y.skipBlank();
        if (y.pos < y.lines.size()) {
            Line l = y.lines.get(y.pos);
            throw new IllegalArgumentException("YAML line " + l.number + ": unexpected indentation: " + l.raw.trim());
        }
        return v;
    }

    private void skipBlank() {
        while (pos < lines.size() && lines.get(pos).indent < 0) pos++;
    }

    private Object node(int indent) {
        skipBlank();
        Line l = lines.get(pos);
        return isSeqItem(l.text) ? sequence(l.indent) : mapping(l.indent);
    }

    private static boolean isSeqItem(String t) {
        return t.equals("-") || t.startsWith("- ");
    }

    private List<Object> sequence(int indent) {
        List<Object> out = new ArrayList<>();
        while (true) {
            skipBlank();
            if (pos >= lines.size()) return out;
            Line l = lines.get(pos);
            if (l.indent != indent || !isSeqItem(l.text)) {
                if (l.indent > indent) throw error(l, "bad indentation in a list");
                return out;
            }
            String rest = l.text.equals("-") ? "" : l.text.substring(2).trim();
            if (rest.isEmpty()) {
                pos++;
                skipBlank();
                out.add(pos < lines.size() && lines.get(pos).indent > indent ? node(lines.get(pos).indent) : null);
            } else if (keyEnd(rest) > 0 && !rest.startsWith("[") && !rest.startsWith("{") && !rest.startsWith("\"") && !rest.startsWith("'")) {
                // "- key: value" starts a mapping whose keys line up after the dash
                int childIndent = indent + (l.text.length() - rest.length());
                lines.set(pos, new Line(childIndent, rest, l.raw, l.number));
                out.add(mapping(childIndent));
            } else {
                pos++;
                out.add(scalar(rest, l));
            }
        }
    }

    private Map<String, Object> mapping(int indent) {
        Map<String, Object> out = new LinkedHashMap<>();
        while (true) {
            skipBlank();
            if (pos >= lines.size()) return out;
            Line l = lines.get(pos);
            if (l.indent < indent) return out;
            if (l.indent > indent) throw error(l, "bad indentation");
            if (isSeqItem(l.text)) return out;
            int k = keyEnd(l.text);
            if (k < 0) throw error(l, "expected 'key: value'");
            String key = unquote(l.text.substring(0, k).trim());
            String value = l.text.substring(k + 1).trim();
            pos++;
            if (value.isEmpty()) {
                skipBlank();
                if (pos < lines.size()) {
                    Line next = lines.get(pos);
                    if (next.indent > indent || next.indent == indent && isSeqItem(next.text)) {
                        out.put(key, node(next.indent));
                        continue;
                    }
                }
                out.put(key, null);
            } else if (value.startsWith("|") || value.startsWith(">")) {
                out.put(key, blockScalar(indent, value.startsWith(">")));
            } else {
                out.put(key, scalar(value, l));
            }
        }
    }

    private String blockScalar(int parentIndent, boolean folded) {
        List<String> body = new ArrayList<>();
        int base = -1;
        while (pos < lines.size()) {
            Line l = lines.get(pos);
            int rawIndent = 0;
            while (rawIndent < l.raw.length() && l.raw.charAt(rawIndent) == ' ') rawIndent++;
            if (!l.raw.isBlank() && rawIndent <= parentIndent) break;
            if (!l.raw.isBlank() && base < 0) base = rawIndent;
            body.add(l.raw.isBlank() ? "" : l.raw.substring(Math.min(base, l.raw.length())));
            pos++;
        }
        while (!body.isEmpty() && body.get(body.size() - 1).isEmpty()) body.remove(body.size() - 1);
        return folded ? String.join(" ", body).replaceAll(" {2,}", " ").trim() : String.join("\n", body) + "\n";
    }

    /** Index of the ':' that ends a key (followed by space or end of line), outside quotes. */
    static int keyEnd(String t) {
        char q = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (q != 0) {
                if (c == q) q = 0;
            } else if (c == '"' || c == '\'') {
                if (i == 0) q = c;
            } else if (c == ':' && (i + 1 == t.length() || t.charAt(i + 1) == ' ')) {
                return i;
            } else if ((c == '[' || c == '{') && i == 0) {
                return -1;
            }
        }
        return -1;
    }

    static String stripComment(String line) {
        char q = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q != 0) {
                if (c == q) q = 0;
            } else if (c == '"' || c == '\'') {
                q = c;
            } else if (c == '#' && (i == 0 || line.charAt(i - 1) == ' ')) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private Object scalar(String v, Line l) {
        if (v.startsWith("[") || v.startsWith("{")) {
            Flow f = new Flow(v, l);
            Object o = f.value();
            f.ws();
            if (f.i != v.length()) throw error(l, "unexpected text after a [list] or {map}");
            return o;
        }
        if (v.equals("~") || v.equals("null")) return null;
        return unquote(v);
    }

    static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            String in = v.substring(1, v.length() - 1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < in.length(); i++) {
                char c = in.charAt(i);
                if (c == '\\' && i + 1 < in.length()) {
                    char e = in.charAt(++i);
                    sb.append(switch (e) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> e;
                    });
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) return v.substring(1, v.length() - 1).replace("''", "'");
        return v;
    }

    private IllegalArgumentException error(Line l, String msg) {
        return new IllegalArgumentException("YAML line " + l.number + ": " + msg + ": " + l.raw.trim());
    }

    /** One-line flow collections: [a, "b c", {k: v}] */
    private final class Flow {
        final String s;
        final Line line;
        int i;

        Flow(String s, Line line) {
            this.s = s;
            this.line = line;
        }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '[') {
                List<Object> out = new ArrayList<>();
                i++;
                ws();
                if (peek(']')) { i++; return out; }
                while (true) {
                    out.add(value());
                    ws();
                    if (peek(',')) { i++; ws(); if (peek(']')) { i++; return out; } continue; }
                    if (peek(']')) { i++; return out; }
                    throw error(line, "expected , or ] in a list");
                }
            }
            if (c == '{') {
                Map<String, Object> out = new LinkedHashMap<>();
                i++;
                ws();
                if (peek('}')) { i++; return out; }
                while (true) {
                    String key = token(":").trim();
                    if (!peek(':')) throw error(line, "expected ':' in a {map}");
                    i++;
                    out.put(unquote(key), value());
                    ws();
                    if (peek(',')) { i++; ws(); continue; }
                    if (peek('}')) { i++; return out; }
                    throw error(line, "expected , or } in a map");
                }
            }
            String t = token(",]}").trim();
            return t.equals("~") || t.equals("null") ? null : unquote(t);
        }

        String token(String stops) {
            ws();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                char q = s.charAt(i++);
                while (i < s.length() && s.charAt(i) != q) i += s.charAt(i) == '\\' && q == '"' ? 2 : 1;
                i++;
                return s.substring(start, Math.min(i, s.length()));
            }
            while (i < s.length() && stops.indexOf(s.charAt(i)) < 0) i++;
            return s.substring(start, i);
        }

        boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        void ws() {
            while (i < s.length() && s.charAt(i) == ' ') i++;
        }
    }
}
