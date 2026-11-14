package sdlc.check;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** The names of the test cases a test file declares, read from its text. Used to tell a new test from one that was there before. */
public final class TestNames {
    private TestNames() {}


    /** The names as a report would give them (see counts). */
    public static Set<String> of(String path, String text) {
        return new LinkedHashSet<>(counts(path, text).keySet());
    }

    /**
     * The names exactly as the file writes them, escapes and all: what a search of the source
     * for a test's declaration needs. A script test declared with an escaped quote is written
     * with the backslash here and without it in counts.
     */
    public static Set<String> declared(String path, String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        if (!Code.isScript(path)) return of(path, text);
        for (Declared.Item item : Declared.script(path, text)) if (item.test() && item.written() != null) out.add(item.written());
        return out;
    }

    /**
     * Each declared name, as a report would give it, with how often the file declares it: a nested
     * class or another describe block may use a name again. Only code declares a test: a
     * declaration inside a comment or a string is text. A report gives "outer > kept" for a test
     * "kept" inside a group "outer" and also for a test whose own title is "outer > kept", and
     * gives a string's value, not its spelling: both are read the same way here.
     */
    public static Map<String, Integer> counts(String path, String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (text == null) return out;
        if (path.endsWith(".t")) { // the self test's stand-in format: "name: outcome"
            for (String line : text.split("\\R")) if (line.contains(": ")) out.merge(plain(path, line.substring(0, line.indexOf(": "))), 1, Integer::sum);
        } else if (path.endsWith(".java")) {
            for (Declared.Item item : Declared.java(path, text)) if (item.test()) out.merge(item.title(), 1, Integer::sum);
        } else {
            for (Declared.Item item : Declared.script(path, text)) if (item.test() && item.title() != null) out.merge(plain(path, item.title()), 1, Integer::sum);
        }
        return out;
    }

    /**
     * True when a script file builds test names while it runs (it.each, a template with ${...}, a
     * variable or a sum of strings as the name): its reported names cannot all be read from its
     * text, so "this name is not declared" does not mean "this test is new".
     */
    public static boolean namesBuiltAtRunTime(String path, String text) {
        if (text == null || !Code.isScript(path)) return false;
        return Declared.script(path, text).stream().anyMatch(item -> item.test() && item.generated());
    }

    /**
     * True when the names read from a script or Java file cannot be relied on: its brackets do not
     * pair up for the reader, or a declaration could not be read to its end. Nothing about which
     * of its tests are new may be concluded from such a file.
     */
    public static boolean unsure(String path, String text) {
        return text != null && (Code.isScript(path) || path.endsWith(".java")) && Declared.unsure(path, text);
    }

    /** The value of a script string literal's inside, with its escapes (quotes, line break, tab, hexadecimal and Unicode forms) resolved; an escape it does not know stands for its character. */
    static String unescaped(String inside) {
        if (inside.indexOf('\\') < 0) return inside;
        StringBuilder out = new StringBuilder(inside.length());
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c != '\\' || i + 1 >= inside.length()) {
                out.append(c);
                continue;
            }
            char e = inside.charAt(++i);
            try {
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'v' -> out.append('\u000b');
                    case '0' -> out.append('\0');
                    case '\n' -> { } // a line continued
                    case 'x' -> {
                        out.append((char) Integer.parseInt(inside.substring(i + 1, i + 3), 16));
                        i += 2;
                    }
                    case 'u' -> {
                        if (i + 1 < inside.length() && inside.charAt(i + 1) == '{') {
                            int close = inside.indexOf('}', i);
                            out.appendCodePoint(Integer.parseInt(inside.substring(i + 2, close), 16));
                            i = close;
                        } else {
                            out.append((char) Integer.parseInt(inside.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> out.append(e);
                }
            } catch (RuntimeException bad) {
                out.append(e); // not an escape after all: keep the character
            }
        }
        return out.toString();
    }

    /** A report's case name as the file declares it: "refunds(int)[2]" -> "refunds", "suite > nested > adds" -> "adds". */
    public static String plain(String path, String reported) {
        if (path.endsWith(".java")) {
            int cut = reported.length();
            for (char c : new char[] {'(', '[', '{'}) {
                int at = reported.indexOf(c);
                if (at >= 0 && at < cut) cut = at;
            }
            return reported.substring(0, cut).strip();
        }
        int at = reported.lastIndexOf(" > ");
        return at < 0 ? reported : reported.substring(at + 3);
    }
}
