package sdlc.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sdlc.util.Proc;

public final class Git {
    private Git() {}

    public static String branch() {
        return branch(Ctx.root);
    }

    public static String branch(java.nio.file.Path root) {
        Proc p = Proc.run(root, "git", "symbolic-ref", "--short", "-q", "HEAD");
        if (p.ok()) return p.out().trim();
        for (String env : java.util.List.of("CHANGE_BRANCH", "GITHUB_HEAD_REF")) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    public static String head() {
        Proc p = Proc.run(Ctx.root, "git", "rev-parse", "HEAD");
        return p.ok() ? p.out().trim() : "";
    }

    public static String shortHead() {
        String h = head();
        return h.length() >= 7 ? h.substring(0, 7) : h;
    }

    public static boolean refExists(String ref) {
        return Proc.run(Ctx.root, "git", "rev-parse", "--verify", "-q", ref).ok();
    }

    public static String base() {
        return Trusted.baseRef();
    }

    public static List<String> dirtyFiles() {
        List<String> out = new ArrayList<>();
        for (String l : Proc.run(Ctx.root, "git", "status", "--porcelain", "--untracked-files=all").out().split("\n")) {
            if (l.length() < 4) continue;
            String f = l.substring(3);
            if (f.contains(" -> ")) f = f.substring(f.indexOf(" -> ") + 4);
            if (f.startsWith("\"") && f.endsWith("\"")) f = f.substring(1, f.length() - 1);
            out.add(f);
        }
        return out;
    }

    public static Set<String> branchFiles() {
        Set<String> files = new LinkedHashSet<>(dirtyFiles());
        if (refExists(base())) files.addAll(lines(Proc.run(Ctx.root, "git", "diff", "--name-only", base() + "...HEAD").out()));
        return files;
    }

    public static List<String> changed(String... diffArgs) {
        List<String> cmd = new ArrayList<>(List.of("git", "diff", "--name-only", "-z", "--no-renames"));
        cmd.addAll(List.of(diffArgs));
        Proc p = Proc.run(Ctx.root, cmd.toArray(new String[0]));
        if (!p.ok()) throw new UsageException("git diff failed (" + p.err().strip() + ")");
        List<String> out = new ArrayList<>();
        for (String s : p.out().split("\0")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    // --text, --no-ext-diff, --no-textconv: a pull request's .gitattributes must not hide its lines.
    public static String patch(String... diffArgs) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotePath=false", "diff", "--unified=0", "--no-color", "--no-ext-diff", "--no-textconv", "--text", "--no-renames"));
        cmd.addAll(List.of(diffArgs));
        Proc p = Proc.run(Ctx.root, cmd.toArray(new String[0]));
        if (!p.ok()) throw new UsageException("git diff failed (" + p.err().strip() + ")");
        return p.out();
    }

    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("\n")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    // Inside a hunk an added line whose text starts with "++ " also begins with "+++ ": file headers are read only before the first hunk.
    public static Map<String, List<String>> addedLines(String diff) {
        return changedLines(diff, '+');
    }

    public static Map<String, List<String>> removedLines(String diff) {
        return changedLines(diff, '-');
    }

    private static Map<String, List<String>> changedLines(String diff, char sign) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String header = sign == '+' ? "+++ " : "--- ";
        String file = null;
        boolean inHunk = false;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                inHunk = false;
                file = null;
            } else if (line.startsWith("@@ ")) {
                inHunk = true;
            } else if (!inHunk) {
                if (!line.startsWith(header)) continue;
                String f = unquote(line.substring(4).trim());
                file = f.equals("/dev/null") ? null : f.replaceFirst("^[ab]/", "");
                if (file != null) out.putIfAbsent(file, new ArrayList<>());
            } else if (file != null && !line.isEmpty() && line.charAt(0) == sign) {
                out.get(file).add(line.substring(1));
            }
        }
        return out;
    }

    private static String unquote(String name) {
        if (name.length() < 2 || !name.startsWith("\"") || !name.endsWith("\"")) return name;
        StringBuilder out = new StringBuilder();
        String s = name.substring(1, name.length() - 1);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            out.append(switch (n) {
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                default -> n;
            });
        }
        return out.toString();
    }

    static String remoteUrl() {
        return Proc.run(Ctx.root, "git", "remote", "get-url", "origin").out().trim();
    }
}
