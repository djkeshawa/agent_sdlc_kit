package sdlc.check;

public final class TestNames {
    private TestNames() {}

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
