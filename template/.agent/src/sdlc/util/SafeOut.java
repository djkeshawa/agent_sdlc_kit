package sdlc.util;

import java.io.PrintStream;
import java.nio.charset.Charset;

public final class SafeOut extends PrintStream {
    private final PrintStream to;

    private SafeOut(PrintStream to) {
        super(to, true, Charset.defaultCharset());
        this.to = to;
    }

    public static PrintStream stream(PrintStream to) {
        return to instanceof SafeOut ? to : new SafeOut(to);
    }

    public static boolean unprintable(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE || type == Character.UNASSIGNED || type == Character.PRIVATE_USE;
    }

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

    @Override
    public void print(String s) {
        flush();
        to.print(clean(s));
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
