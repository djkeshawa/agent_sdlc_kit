package sdlc.check;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The names of the test cases a test file declares, read from its text. Used to tell a new test from one that was there before. */
public final class TestNames {
    private TestNames() {}

    private static final Pattern JAVA = Pattern.compile("@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b[\\s\\S]{0,600}?\\bvoid\\s+(\\w+)\\s*\\(");
    private static final Pattern SCRIPT = Pattern.compile("\\b(?:it|test)(?:\\.\\w+)*\\s*\\(\\s*(['\"`])((?:\\\\.|(?!\\1).)*)\\1");

    public static Set<String> of(String path, String text) {
        return new LinkedHashSet<>(counts(path, text).keySet());
    }

    /** Each declared name with how often the file declares it: a nested class or another describe block may use a name again. */
    public static Map<String, Integer> counts(String path, String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (text == null) return out;
        if (path.endsWith(".t")) { // the self test's stand-in format: "name: outcome"
            for (String line : text.split("\\R")) if (line.contains(": ")) out.merge(plain(path, line.substring(0, line.indexOf(": "))), 1, Integer::sum);
        } else if (path.endsWith(".java")) {
            Matcher m = JAVA.matcher(text);
            while (m.find()) out.merge(m.group(1), 1, Integer::sum);
        } else {
            Matcher m = SCRIPT.matcher(text);
            // a report gives "outer > kept" for it('kept') inside describe('outer') and also for it('outer > kept'):
            // both are counted under the name a report is read as
            while (m.find()) out.merge(plain(path, m.group(2)), 1, Integer::sum);
        }
        return out;
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
