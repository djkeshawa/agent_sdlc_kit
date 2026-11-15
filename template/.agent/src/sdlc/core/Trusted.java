package sdlc.core;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import sdlc.util.Proc;

public final class Trusted {
    private Trusted() {}

    public static Function<String, String> env = System::getenv;

    public static final List<String> BASE_VARIABLES = List.of("SDLC_BASE_BRANCH", "GITHUB_BASE_REF", "CHANGE_TARGET");

    private static final Pattern NOT_A_BRANCH = Pattern.compile("^[-/.]|[\\x00-\\x20\\x7f~^:?*\\[\\\\]|\\.\\.|@\\{|//|/\\.|\\.lock(/|$)|[/.]$");

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

    public static final String FIRST_INSTALLATION = "SDLC_FIRST_INSTALLATION";
    public static final String LOCKS_REMOVABLE = "SDLC_LOCKS_REMOVABLE";

    // Read from the environment, never from a file of the checkout: a pull request must not grant itself an exception.
    public static boolean flag(String name) {
        String v = env.apply(name);
        return v != null && (v.trim().equalsIgnoreCase("true") || v.trim().equals("1"));
    }

    public static java.util.Set<String> list(String name) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        String v = env.apply(name);
        if (v != null) for (String s : v.split("[,\\s]+")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    public static boolean inCi() {
        return !eventBase().isEmpty();
    }

    // A full ref: a short origin/main would resolve first to a local branch of that name.
    public static String baseRef() {
        String event = eventBase();
        return "refs/remotes/origin/" + (event.isEmpty() ? Ctx.cfg.baseBranch() : event);
    }

    public static String shortName(String ref) {
        return ref.startsWith("refs/remotes/") ? ref.substring("refs/remotes/".length()) : ref;
    }

    public static String read(String ref, String path) {
        Proc ls = Proc.run(Ctx.root, "git", "ls-tree", "-z", "--name-only", ref, "--", path);
        if (!ls.ok()) throw new UsageException("cannot read " + shortName(ref) + " (git ls-tree: " + ls.err().strip() + ")");
        if (ls.out().isEmpty()) return null;
        Proc p = Proc.run(Ctx.root, "git", "show", ref + ":" + path);
        if (!p.ok()) throw new UsageException("cannot read " + path + " at " + shortName(ref) + " (git show: " + p.err().strip() + ")");
        return p.out();
    }

    public static Config config(String ref) {
        String repo = read(ref, ".agent/sdlc.yml");
        if (repo == null) return null;
        return Config.fromText(read(ref, ".agent/org.yml"), repo);
    }
}
