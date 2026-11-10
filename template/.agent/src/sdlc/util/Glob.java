package sdlc.util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Path globs: ** any folders, * any characters but '/', ? one character, {a,b} alternatives. */
public final class Glob {
    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private Glob() {}

    public static boolean any(List<String> globs, String path) {
        String p = normalize(path);
        for (String g : globs) if (compile(g).matcher(p).matches()) return true;
        return false;
    }

    public static String normalize(String path) {
        return path.replace('\\', '/').replaceFirst("^\\./", "");
    }

    static Pattern compile(String glob) {
        return CACHE.computeIfAbsent(glob, Glob::toRegex);
    }

    private static Pattern toRegex(String glob) {
        String s = normalize(glob);
        if (s.endsWith("/**")) s = s.substring(0, s.length() - 3); // "dir/**" also matches "dir"
        StringBuilder re = new StringBuilder();
        int braces = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                boolean slash = i + 2 < s.length() && s.charAt(i + 2) == '/';
                re.append(slash ? "(?:.*/)?" : ".*");
                i += slash ? 2 : 1;
            } else if (c == '*') {
                re.append("[^/]*");
            } else if (c == '?') {
                re.append("[^/]");
            } else if (c == '{') {
                braces++;
                re.append("(?:");
            } else if (c == '}' && braces > 0) {
                braces--;
                re.append(')');
            } else if (c == ',' && braces > 0) {
                re.append('|');
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re + "(?:/.*)?");
    }
}
