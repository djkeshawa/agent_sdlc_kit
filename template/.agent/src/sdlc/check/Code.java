package sdlc.check;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Reads a whole source file and tells code from what is not code. Line by line this cannot be
 * done: a template string or a block comment runs over many lines, and what looks like a call
 * on one of them is text.
 *
 * It is a reader of tokens, not a parser of the language. Where a file is written so that only
 * a parser could tell (a lone backtick in the text of a JSX element), it may take code for text
 * or text for code. Callers therefore also say when a file holds something that looks like what
 * they search for inside what this reader took for text (see Integrity).
 */
final class Code {
    private Code() {}

    static boolean isScript(String path) {
        return path.matches(".*\\.([cm]?[jt]sx?|vue|svelte)");
    }

    /** Words after which a "/" starts a regular expression, not a division. */
    private static final Set<String> BEFORE_A_REGEXP = Set.of("return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await");

    /**
     * The text with everything that is not code replaced by spaces, position by position (line
     * breaks stay, and so do the quotes around a string and the slashes around a regular
     * expression): comments, and the insides of strings, of Java text blocks, of template
     * strings and of regular expressions of scripts. What a template string computes inside
     * ${...} is code and stays. The result has the length of the text, so a place found in it is
     * the same place in the text.
     */
    static String mask(String path, String text) {
        boolean hash = path.endsWith(".py") || path.endsWith(".rb") || path.endsWith(".sh"); // "#" starts a comment there
        boolean script = isScript(path);
        char[] out = text.toCharArray();
        int n = out.length;
        Deque<Integer> outer = new ArrayDeque<>(); // for each open ${ : the brace depth of the code around its template
        int depth = 0;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (!hash && text.startsWith("//", i) || hash && c == '#') {
                int end = text.indexOf('\n', i);
                i = blank(out, i, end < 0 ? n : end);
            } else if (!hash && text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = blank(out, i, end < 0 ? n : end + 2);
            } else if (script && c == '/' && regexpMayStart(text, i)) {
                int end = regexpEnd(text, i);
                if (end < 0) {
                    i++; // no end on this line: a division after all
                } else {
                    blank(out, i + 1, end);
                    i = end + 1;
                }
            } else if (text.startsWith("\"\"\"", i) || hash && text.startsWith("'''", i)) {
                int end = text.indexOf(text.substring(i, i + 3), i + 3);
                blank(out, i + 3, end < 0 ? n : end);
                i = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                // A string ends at its closing quote on the same line. A quote with none is not a string: it is an
                // apostrophe in the text of a JSX element (It's ready), and what follows it on the line is code.
                int j = i + 1;
                while (j < n && text.charAt(j) != c && text.charAt(j) != '\n') j += text.charAt(j) == '\\' ? 2 : 1;
                if (j >= n || text.charAt(j) != c) {
                    i++;
                    continue;
                }
                blank(out, i + 1, j);
                i = j + 1;
            } else if (c == '`' && script) {
                if (text.indexOf('`', i + 1) < 0) {
                    i++; // no second backtick in the rest of the file: not a template string
                    continue;
                }
                i = template(text, out, i + 1);
                if (text.startsWith("${", i - 2)) {
                    outer.push(depth);
                    depth = 0;
                }
            } else if (c == '}' && !outer.isEmpty() && depth == 0) {
                // the ${...} ends here: what follows is the text of its template again
                depth = outer.pop();
                i = template(text, out, i + 1);
                if (text.startsWith("${", i - 2)) {
                    outer.push(depth);
                    depth = 0;
                }
            } else {
                if (!outer.isEmpty() && c == '{') depth++;
                else if (!outer.isEmpty() && c == '}') depth--;
                i++;
            }
        }
        return new String(out);
    }

    /**
     * True when the "/" at this place starts a regular expression. After something that is not a
     * value (an operator, an opening bracket, a comma, a word such as return, the start of a
     * line) it does. After a value (a name, a number, a closing bracket, a string) it is a
     * division, unless what stands between it and the next "/" of the line holds a quote: read as
     * a division, that quote would open a string that the file does not have, and everything
     * after it would be misread. Reading a division as an expression costs one line at most.
     */
    private static boolean regexpMayStart(String text, int at) {
        int i = at - 1;
        while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i--;
        boolean afterValue = false;
        if (i >= 0) {
            char before = text.charAt(i);
            if (Character.isLetterOrDigit(before) || before == '_' || before == '$') {
                int start = i;
                while (start >= 0 && (Character.isLetterOrDigit(text.charAt(start)) || text.charAt(start) == '_' || text.charAt(start) == '$')) start--;
                afterValue = !BEFORE_A_REGEXP.contains(text.substring(start + 1, i + 1));
            } else {
                afterValue = before == ')' || before == ']' || before == '}' || before == '"' || before == '\'' || before == '`';
            }
        }
        if (!afterValue) return true;
        int end = regexpEnd(text, at);
        if (end < 0) return false;
        for (int k = at + 1; k < end; k++) {
            char c = text.charAt(k);
            if (c == '"' || c == '\'' || c == '`') return true;
        }
        return false;
    }

    /** The place of the "/" that ends the regular expression starting at open, on the same line; -1 when there is none. */
    private static int regexpEnd(String text, int open) {
        boolean inClass = false;
        for (int i = open + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') return -1;
            if (c == '\\') i++;
            else if (c == '[') inClass = true;
            else if (c == ']') inClass = false;
            else if (c == '/' && !inClass) return i > open + 1 ? i : -1; // "//" is a comment, not an empty expression
        }
        return -1;
    }

    /**
     * Blanks the text of a template string from i on. Returns the place after its closing quote,
     * or the place after a "${" (which stays as it is): the code inside is the caller's to read.
     */
    private static int template(String text, char[] out, int i) {
        int n = out.length;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                blank(out, i, Math.min(n, i + 2));
                i += 2;
            } else if (c == '`') {
                return i + 1;
            } else if (text.startsWith("${", i)) {
                return i + 2;
            } else {
                if (c != '\n' && c != '\r') out[i] = ' ';
                i++;
            }
        }
        return n;
    }

    private static int blank(char[] out, int from, int to) {
        for (int k = from; k < to && k < out.length; k++) if (out[k] != '\n' && out[k] != '\r') out[k] = ' ';
        return to;
    }

    /**
     * True when the brackets of masked code pair up: every one that opens is closed by its own
     * kind, in order. When they do not, the reader took text for code or code for text somewhere
     * (an apostrophe or a bracket in the text of a JSX element), and no extent it finds in the
     * file can be relied on.
     */
    static boolean balanced(String code) {
        Deque<Character> open = new ArrayDeque<>();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') open.push(c);
            else if (c == ')' || c == ']' || c == '}') {
                if (open.isEmpty()) return false;
                char o = open.pop();
                if (o == '(' && c != ')' || o == '[' && c != ']' || o == '{' && c != '}') return false;
            }
        }
        return open.isEmpty();
    }

    /** The place of the bracket that closes the one at open, in masked code; -1 when there is none. */
    static int closing(String code, int open) {
        char in = code.charAt(open);
        char end = in == '(' ? ')' : in == '{' ? '}' : ']';
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == in) depth++;
            else if (c == end && --depth == 0) return i;
        }
        return -1;
    }
}
