package sdlc.check;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Reads a whole Java source file and tells code from what is not code. Line by line this cannot
 * be done: a text block or a block comment runs over many lines, and what looks like an
 * annotation on one of them is text.
 *
 * It is a reader of tokens, not a parser of the language. Where a file is written so that only a
 * parser could tell, it may take code for text or text for code. Callers therefore also say when
 * a file holds something that looks like what they search for inside what this reader took for
 * text (see Integrity).
 */
final class Code {
    private Code() {}

    /**
     * The text with everything that is not code replaced by spaces, position by position (line
     * breaks stay, and so do the quotes around a string): comments, and the insides of strings,
     * character literals and text blocks. The result has the length of the text, so a place found
     * in it is the same place in the text.
     */
    static String mask(String text) {
        char[] out = text.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                i = blank(out, i, end < 0 ? n : end);
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = blank(out, i, end < 0 ? n : end + 2);
            } else if (text.startsWith("\"\"\"", i)) {
                int end = text.indexOf("\"\"\"", i + 3);
                blank(out, i + 3, end < 0 ? n : end);
                i = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                // a string or a character literal ends at its closing quote on the same line; a quote with none is not one
                int j = i + 1;
                while (j < n && text.charAt(j) != c && text.charAt(j) != '\n') j += text.charAt(j) == '\\' ? 2 : 1;
                if (j >= n || text.charAt(j) != c) {
                    i++;
                    continue;
                }
                blank(out, i + 1, j);
                i = j + 1;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int blank(char[] out, int from, int to) {
        for (int k = from; k < to && k < out.length; k++) if (out[k] != '\n' && out[k] != '\r') out[k] = ' ';
        return to;
    }

    /**
     * True when the brackets of masked code pair up: every one that opens is closed by its own
     * kind, in order. When they do not, the reader took text for code or code for text somewhere
     * (a file that does not compile, or one written so that only a parser could tell), and no
     * extent it finds in the file can be relied on.
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
