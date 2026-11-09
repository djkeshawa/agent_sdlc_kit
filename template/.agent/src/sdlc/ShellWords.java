package sdlc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A POSIX-ish shell tokenizer, good enough for refusal checks (not for running commands). */
final class ShellWords {
    private ShellWords() {}

    /** Splits a command line into simple commands (on ; && || | & ( ) and newlines). Redirections stay as words ">", ">>". */
    /** argv -> one shell line, single-quoting words that need it (for tools that pass commands as arrays). */
    static String join(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (String a : argv) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(a.matches("[A-Za-z0-9_./:=@%+,-]+") ? a : "'" + a.replace("'", "'\\''") + "'");
        }
        return sb.toString();
    }

    static List<List<String>> commands(String line) {
        List<List<String>> out = new ArrayList<>();
        List<String> words = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        boolean inWord = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '$' && i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                i = ansiC(line, i + 2, w); // $'...': the shell decodes \x2e, \056, . ... before running the command
                inWord = true;
            } else if (c == '$' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                inWord = true; // $"...": a translatable string, read like "..."
            } else if (c == '\'') {
                int end = line.indexOf('\'', i + 1);
                if (end < 0) end = line.length();
                w.append(line, i + 1, end);
                i = end;
                inWord = true;
            } else if (c == '"') {
                int j = i + 1;
                while (j < line.length() && line.charAt(j) != '"') {
                    if (line.charAt(j) == '\\' && j + 1 < line.length()) j++;
                    w.append(line.charAt(j));
                    j++;
                }
                i = j;
                inWord = true;
            } else if (c == '\\' && i + 1 < line.length() && " \t\"'\\$`;&|()<>\n".indexOf(line.charAt(i + 1)) >= 0) {
                w.append(line.charAt(++i)); // a shell escape; other backslashes belong to Windows paths
                inWord = true;
            } else if (c == ';' || c == '|' || c == '&' || c == '\n' || c == '(' || c == ')') {
                if (c == '&' && i + 1 < line.length() && line.charAt(i + 1) == '>') {
                    flush(words, w, inWord);
                    inWord = false;
                    words.add("&>");
                    i++;
                    continue;
                }
                flush(words, w, inWord);
                inWord = false;
                if (!words.isEmpty()) out.add(words);
                words = new ArrayList<>();
            } else if (c == '>' || c == '<') {
                String digit = inWord && w.toString().matches("[0-9]") ? w.toString() : "";
                if (!digit.isEmpty()) {
                    w.setLength(0);
                    inWord = false;
                }
                flush(words, w, inWord);
                inWord = false;
                String op = digit + c;
                if (i + 1 < line.length() && line.charAt(i + 1) == c) {
                    op += c;
                    i++;
                }
                if (i + 1 < line.length() && line.charAt(i + 1) == '&') {
                    i++; // 2>&1: the target is a descriptor, not a file
                    while (i + 1 < line.length() && Character.isDigit(line.charAt(i + 1))) i++;
                    continue;
                }
                if (c == '>') words.add(op);
            } else if (Character.isWhitespace(c)) {
                flush(words, w, inWord);
                inWord = false;
            } else {
                w.append(c);
                inWord = true;
            }
        }
        flush(words, w, inWord);
        if (!words.isEmpty()) out.add(words);
        return out;
    }

    /** Decodes the body of $'...' starting at index j into w; returns the index of the closing quote. */
    private static int ansiC(String line, int j, StringBuilder w) {
        while (j < line.length() && line.charAt(j) != '\'') {
            char d = line.charAt(j);
            if (d != '\\' || j + 1 >= line.length()) {
                w.append(d);
                j++;
                continue;
            }
            char e = line.charAt(++j);
            int k = j + 1;
            switch (e) {
                case 'n' -> w.append('\n');
                case 't' -> w.append('\t');
                case 'r' -> w.append('\r');
                case 'a' -> w.append('\u0007');
                case 'b' -> w.append('\b');
                case 'f' -> w.append('\f');
                case 'v' -> w.append('\u000b');
                case 'e', 'E' -> w.append('\u001b');
                case 'x', 'u', 'U' -> {
                    int max = e == 'x' ? 2 : e == 'u' ? 4 : 8;
                    while (k < line.length() && k < j + 1 + max && Character.digit(line.charAt(k), 16) >= 0) k++;
                    if (k > j + 1) {
                        w.appendCodePoint(Integer.parseInt(line.substring(j + 1, k), 16));
                        j = k - 1;
                    } else {
                        w.append('\\').append(e);
                    }
                }
                case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                    k = j;
                    while (k < line.length() && k < j + 3 && line.charAt(k) >= '0' && line.charAt(k) <= '7') k++;
                    w.append((char) Integer.parseInt(line.substring(j, k), 8));
                    j = k - 1;
                }
                default -> w.append(e);
            }
            j++;
        }
        return j;
    }

    /** Brace expansion as bash does it before running a command: a.{x,y} -> a.x a.y, {a..c} -> a b c. */
    static List<String> braces(String word) {
        List<String> out = new ArrayList<>();
        expandBraces(word, out);
        return out.isEmpty() ? List.of(word) : out;
    }

    private static final Pattern RANGE = Pattern.compile("^(-?\\d+|[A-Za-z])\\.\\.(-?\\d+|[A-Za-z])$");

    private static void expandBraces(String s, List<String> out) {
        if (out.size() > 256) return;
        int depth = 0, open = -1, dollar = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' && i > 0 && s.charAt(i - 1) == '$') {
                dollar++; // ${VAR} is a variable, not an expansion
            } else if (c == '{') {
                if (depth++ == 0) open = i;
            } else if (c == '}' && dollar > 0 && depth == 0) {
                dollar--;
            } else if (c == '}' && depth > 0 && --depth == 0) {
                String inner = s.substring(open + 1, i), head = s.substring(0, open), tail = s.substring(i + 1);
                List<String> parts = splitTopLevel(inner);
                if (parts.size() > 1) {
                    for (String p : parts) expandBraces(head + p + tail, out);
                    return;
                }
                Matcher m = RANGE.matcher(inner);
                if (m.matches()) {
                    boolean letters = !Character.isDigit(m.group(1).charAt(m.group(1).length() - 1));
                    int a = letters ? m.group(1).charAt(0) : Integer.parseInt(m.group(1));
                    int b = letters ? m.group(2).charAt(0) : Integer.parseInt(m.group(2));
                    int step = a <= b ? 1 : -1;
                    for (int v = a, n = 0; n < 64; v += step, n++) {
                        expandBraces(head + (letters ? String.valueOf((char) v) : String.valueOf(v)) + tail, out);
                        if (v == b) break;
                    }
                    return;
                }
            }
        }
        out.add(s);
    }

    private static List<String> splitTopLevel(String inner) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(inner.substring(start));
        return parts;
    }

    private static void flush(List<String> words, StringBuilder w, boolean inWord) {
        if (inWord) words.add(w.toString());
        w.setLength(0);
    }

    /** Contents of $(...) and `...`, so they are checked too. */
    static List<String> substitutions(String line) {
        List<String> out = new ArrayList<>();
        Matcher back = Pattern.compile("`([^`]*)`").matcher(line);
        while (back.find()) out.add(back.group(1));
        int i = line.indexOf("$(");
        while (i >= 0) {
            int depth = 1;
            int j = i + 2;
            while (j < line.length() && depth > 0) {
                if (line.charAt(j) == '(') depth++;
                if (line.charAt(j) == ')') depth--;
                j++;
            }
            out.add(line.substring(i + 2, Math.max(i + 2, j - 1)));
            i = line.indexOf("$(", j);
        }
        return out;
    }
}
