package sdlc.core;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import sdlc.util.Proc;

/**
 * What a check in CI may trust. A pull request can change every file of its own checkout, so the
 * rules it is judged by are read from the tip of the base branch as git holds it, and the base
 * branch is named by the CI event, not by the pull request's own configuration.
 *
 * This is the code half of the boundary. The other half is the host's: the pipeline definition
 * and the tool itself must also come from outside the pull request (docs/05).
 */
public final class Trusted {
    private Trusted() {}

    /** The environment. A test replaces it. */
    public static Function<String, String> env = System::getenv;

    /** The variables that name the base branch of a pull request, in order: an explicit one, GitHub Actions, Jenkins. */
    public static final List<String> BASE_VARIABLES = List.of("SDLC_BASE_BRANCH", "GITHUB_BASE_REF", "CHANGE_TARGET");

    /** What git itself refuses in a branch name, plus a leading dash: the value becomes part of a ref in git commands. */
    private static final Pattern NOT_A_BRANCH = Pattern.compile("^[-/.]|[\\x00-\\x20\\x7f~^:?*\\[\\\\]|\\.\\.|@\\{|//|/\\.|\\.lock(/|$)|[/.]$");

    /** The base branch the CI event names, or "" outside a pull request build. */
    public static String eventBase() {
        for (String name : BASE_VARIABLES) {
            String v = env.apply(name);
            if (v == null || v.isBlank()) continue;
            v = v.trim();
            if (v.length() > 250 || v.equals("@") || NOT_A_BRANCH.matcher(v).find()) throw new UsageException(name + " does not hold a branch name: " + v);
            return v;
        }
        return "";
    }

    /** True in a pull request build: the rules then come from the base branch. */
    public static boolean inCi() {
        return !eventBase().isEmpty();
    }

    /**
     * The base branch as a full ref, refs/remotes/origin/BRANCH: the branch of the CI event when
     * there is one, else delivery.baseBranch of this checkout. The full name matters: a short
     * "origin/main" would resolve to a local branch of that name first, and the author of a pull
     * request names its branch.
     */
    public static String baseRef() {
        String event = eventBase();
        return "refs/remotes/origin/" + (event.isEmpty() ? Ctx.cfg.baseBranch() : event);
    }

    /** A ref as people write it: refs/remotes/origin/main -> origin/main. */
    public static String shortName(String ref) {
        return ref.startsWith("refs/remotes/") ? ref.substring("refs/remotes/".length()) : ref;
    }

    /**
     * A file as committed at a ref (a commit or a tree), or null when the ref does not hold it.
     * "Not there" and "cannot be read" are different answers: the second one throws, so a check
     * never mistakes a failed read for a missing rule.
     */
    public static String read(String ref, String path) {
        Proc ls = Proc.run(Ctx.root, "git", "ls-tree", "-z", "--name-only", ref, "--", path);
        if (!ls.ok()) throw new UsageException("cannot read " + shortName(ref) + " (git ls-tree: " + ls.err().strip() + ")");
        if (ls.out().isEmpty()) return null;
        Proc p = Proc.run(Ctx.root, "git", "show", ref + ":" + path);
        if (!p.ok()) throw new UsageException("cannot read " + path + " at " + shortName(ref) + " (git show: " + p.err().strip() + ")");
        return p.out();
    }

    /** The configuration as committed at a ref (sdlc.yml over org.yml), or null when the ref has no .agent/sdlc.yml. */
    public static Config config(String ref) {
        String repo = read(ref, ".agent/sdlc.yml");
        if (repo == null) return null;
        return Config.fromText(read(ref, ".agent/org.yml"), repo);
    }
}
