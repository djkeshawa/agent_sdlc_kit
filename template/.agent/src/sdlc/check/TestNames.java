package sdlc.check;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The names of the test cases a Java test file declares, read from its text, and the plain form
 * of a name a test report gives. Test files of other languages are not read: which tests they
 * hold, and which are new, gone or skipped, is read from their run (Witness).
 */
public final class TestNames {
    private TestNames() {}

    /** The names as a report would give them (see counts). */
    public static Set<String> of(String path, String text) {
        return new LinkedHashSet<>(counts(path, text).keySet());
    }

    /**
     * Each name a Java file declares, with how often it declares it: a nested class may use a
     * name again. Only code declares a test: a declaration inside a comment or a string is text.
     * Empty for a file of another language.
     */
    public static Map<String, Integer> counts(String path, String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (text == null) return out;
        if (path.endsWith(".t")) { // the self test's stand-in format: "name: outcome"
            for (String line : text.split("\\R")) if (line.contains(": ")) out.merge(plain(path, line.substring(0, line.indexOf(": "))), 1, Integer::sum);
        } else if (path.endsWith(".java")) {
            for (Declared.Item item : Declared.java(text)) if (item.test()) out.merge(item.name(), 1, Integer::sum);
        }
        return out;
    }

    /**
     * A report's case name in its plain form: for Java the method, without the parameters and the
     * invocation ("refunds(int)[2]" -> "refunds"); elsewhere the last part of the name a report
     * gives a test inside its groups ("suite > nested > adds" -> "adds").
     */
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
