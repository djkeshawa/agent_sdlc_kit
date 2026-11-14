package sdlc.util;

import java.io.PrintStream;

/**
 * A print stream for text that partly comes from the repository being checked: file names, test
 * names, sentences of a mandate, messages of a build. Every character that could make a terminal
 * show something other than what was written becomes "?": control characters and escapes (a line
 * break and a tab stay), invisible characters, and marks that turn the direction of text.
 */
public final class SafeOut extends PrintStream {
    private SafeOut(PrintStream to) {
        super(to, true, to.charset());
    }

    public static PrintStream stream(PrintStream to) {
        return to instanceof SafeOut ? to : new SafeOut(to);
    }

    /**
     * True for a character that must not reach a terminal: Unicode categories Cc (control), Cf
     * (format: direction marks, zero width), Zl and Zp (line and paragraph separators), Cs (half
     * of a surrogate pair), Co (private use) and Cn (not assigned in this Java's Unicode).
     */
    public static boolean unprintable(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE || type == Character.UNASSIGNED || type == Character.PRIVATE_USE;
    }

    /** The text with every such character but a line break and a tab replaced by "?". */
    public static String clean(String text) {
        if (text == null) return "null";
        if (text.codePoints().noneMatch(c -> c != '\n' && c != '\t' && unprintable(c))) return text;
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            if (c != '\n' && c != '\t' && unprintable(c)) out.append('?');
            else out.appendCodePoint(c);
        });
        return out.toString();
    }

    // println, printf, format and append all arrive at one of these
    @Override
    public void print(String s) {
        super.print(clean(s));
    }

    @Override
    public void print(Object o) {
        print(String.valueOf(o));
    }

    @Override
    public void print(char c) {
        print(String.valueOf(c));
    }

    @Override
    public void print(char[] s) {
        print(new String(s));
    }
}
