package sdlc.check;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Reads a whole source file and tells code from what is not code. Line by line this cannot be
 * done: a template string or a block comment runs over many lines, and what looks like a call
 * on one of them is text.
 */
final class Code {
    private Code() {}

    /**
     * The text with everything that is not code replaced by spaces, position by position (line
     * breaks stay, and so do the quotes around a string): comments, and the insides of strings,
     * of Java text blocks and of template strings. What a template string computes inside ${...}
     * is code and stays. The result has the length of the text, so a place found in it is the
     * same place in the text.
     */
    static String mask(String path, String text) {
        boolean hash = path.endsWith(".py") || path.endsWith(".rb") || path.endsWith(".sh"); // "#" starts a comment there
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
            } else if (text.startsWith("\"\"\"", i) || hash && text.startsWith("'''", i)) {
                int end = text.indexOf(text.substring(i, i + 3), i + 3);
                blank(out, i + 3, end < 0 ? n : end);
                i = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                // ends at its closing quote, or at the end of the line when there is none
                int j = i + 1;
                while (j < n && text.charAt(j) != c && text.charAt(j) != '\n') j += text.charAt(j) == '\\' ? 2 : 1;
                j = Math.min(j, n);
                blank(out, i + 1, j);
                i = j < n && text.charAt(j) == c ? j + 1 : j;
            } else if (c == '`' && !hash) {
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
}
