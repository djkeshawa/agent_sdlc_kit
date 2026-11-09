package sdlc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only git helpers. */
final class Git {
    private Git() {}

    /** The current branch; in CI (detached HEAD) the PR's source branch from Jenkins or GitHub Actions. */
    static String branch() {
        Proc p = Proc.run(Ctx.root, "git", "symbolic-ref", "--short", "-q", "HEAD");
        if (p.ok()) return p.out().trim();
        for (String env : java.util.List.of("CHANGE_BRANCH", "GITHUB_HEAD_REF")) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    static String head() {
        Proc p = Proc.run(Ctx.root, "git", "rev-parse", "HEAD");
        return p.ok() ? p.out().trim() : "";
    }

    static String shortHead() {
        String h = head();
        return h.length() >= 7 ? h.substring(0, 7) : h;
    }

    static boolean refExists(String ref) {
        return Proc.run(Ctx.root, "git", "rev-parse", "--verify", "-q", ref).ok();
    }

    static String base() {
        return "origin/" + Ctx.cfg.baseBranch();
    }

    /** Files changed but not committed (tracked and untracked, not ignored). */
    static List<String> dirtyFiles() {
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
    static Set<String> branchFiles() {
        Set<String> files = new LinkedHashSet<>(dirtyFiles());
        if (refExists(base())) files.addAll(lines(Proc.run(Ctx.root, "git", "diff", "--name-only", base() + "...HEAD").out()));
        return files;
    }

    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("\n")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    /** Added lines per file of a unified diff with zero context. */
    static Map<String, List<String>> addedLines(String diff) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String file = null;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("+++ ")) {
                String f = line.substring(4).trim();
                file = f.equals("/dev/null") ? null : f.replaceFirst("^b/", "");
                if (file != null) out.putIfAbsent(file, new ArrayList<>());
            } else if (file != null && line.startsWith("+") && !line.startsWith("+++")) {
                out.get(file).add(line.substring(1));
            }
        }
        return out;
    }

    static String remoteUrl() {
        return Proc.run(Ctx.root, "git", "remote", "get-url", "origin").out().trim();
    }
}
