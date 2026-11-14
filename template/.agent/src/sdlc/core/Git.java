package sdlc.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sdlc.util.Proc;

/** Read-only git helpers. */
public final class Git {
    private Git() {}

    /** The current branch; in CI (detached HEAD) the PR's source branch from Jenkins or GitHub Actions. */
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

    /** origin/BRANCH of the base branch: named by the CI event in a pull request build, else by delivery.baseBranch. */
    public static String base() {
        return Trusted.baseRef();
    }

    /** Files changed but not committed (tracked and untracked, not ignored). */
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

    /** Files changed on this branch since the base branch, plus uncommitted changes. */
    public static Set<String> branchFiles() {
        Set<String> files = new LinkedHashSet<>(dirtyFiles());
        if (refExists(base())) files.addAll(lines(Proc.run(Ctx.root, "git", "diff", "--name-only", base() + "...HEAD").out()));
        return files;
    }

    /**
     * The file names of a `git diff --name-only` with the given further arguments, exactly as git
     * holds them (NUL-separated, never quoted). A diff that fails is an error, not an empty list:
     * a check that sees no changes must not pass because git could not answer.
     */
    public static List<String> changed(String... diffArgs) {
        List<String> cmd = new ArrayList<>(List.of("git", "diff", "--name-only", "-z", "--no-renames"));
        cmd.addAll(List.of(diffArgs));
        Proc p = Proc.run(Ctx.root, cmd.toArray(new String[0]));
        if (!p.ok()) throw new UsageException("git diff failed (" + p.err().strip() + ")");
        List<String> out = new ArrayList<>();
        for (String s : p.out().split("\0")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    /**
     * The unified diff with no context that the content scanners read. --text: a pull request
     * cannot hide its lines by marking a file as binary in .gitattributes; no external diff or
     * text conversion either.
     */
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

    /**
     * Added lines per file of a unified diff. File headers are read only between "diff --git" and
     * the first hunk: inside a hunk, an added line whose text starts with "++ " also begins with
     * "+++ ", and must not be taken for the header of another file.
     */
    public static Map<String, List<String>> addedLines(String diff) {
        return changedLines(diff, '+');
    }

    /** Removed lines per file of a unified diff, read the same way. */
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

    /** A file name as git writes it in a patch header: in double quotes with escapes when it holds unusual characters. */
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
                default -> n; // \\ and \"
            });
        }
        return out.toString();
    }

    static String remoteUrl() {
        return Proc.run(Ctx.root, "git", "remote", "get-url", "origin").out().trim();
    }
}
