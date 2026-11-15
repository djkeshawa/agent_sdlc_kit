package sdlc.check;

/**
 * The plain form of a name a test report gives. Test files are not read for their tests: which
 * tests a file holds, and which are new, gone or skipped, is read from its run (Witness).
 */
public final class TestNames {
    private TestNames() {}

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
