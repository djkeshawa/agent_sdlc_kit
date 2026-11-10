package sdlc.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sdlc.core.Ctx;
import sdlc.core.Git;
import sdlc.core.Work;
import sdlc.util.Glob;
import sdlc.util.Json;
import sdlc.util.Proc;

/** Content checks shared by the git hooks, the PR gate and `sdlc check-pr`. */
public final class Checks {
    private Checks() {}

    // ---------------- secrets ----------------

    private static final Pattern CONFIG_ASSIGNMENT = Pattern.compile("(?i)([\\w.-]*(?:password|passwd|pwd|secret|api[_-]?key|token|credentials?)[\\w.-]*)\\s*[:=]\\s*[\"']?([^\\s\"'#,)]{8,})");
    private static final Pattern CODE_LITERAL = Pattern.compile("(?i)((?:password|passwd|secret|api_?key|token)\\w*)\\s*[:=]\\s*[\"'`]([^\"'`\\s]{8,})[\"'`]");
    private static final Pattern REFERENCE_KEY = Pattern.compile("(?i).*(id|ids|variable|var|name|names|path|paths|file|files|env|ref|url|uri|location|type|length|policy|header|param|field|prefix|suffix|expiry|expires|ttl|timeout|enabled|required|pattern|regex)$");
    private static final Pattern REFERENCE_VALUE = Pattern.compile("^(\\$\\{.*|\\{\\{.*|<.*|%.*|\\$[A-Za-z_].*|env\\..*|process\\.env.*|[A-Z][A-Z0-9_]+|(?i:changeme|dummy|example|placeholder|your[-_]|xxxx|\\*\\*\\*\\*).*|(?i:true|false|null|none|undefined))$");
    private static final Pattern CONFIG_FILE = Pattern.compile("(?i).*\\.(properties|ya?ml|xml|json|conf|cfg|ini|env|sh|ps1|bat|cmd|tf|tfvars|groovy|toml)$|(^|.*/)Jenkinsfile$");
    private static final List<Pattern> TOKENS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN (RSA |EC |DSA |OPENSSH |ENCRYPTED |PGP )?PRIVATE KEY"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"),
            Pattern.compile("github_pat_[A-Za-z0-9_]{40,}"),
            Pattern.compile("xox[abprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("ATATT3[A-Za-z0-9_\\-=]{20,}"),
            Pattern.compile("\\bsq[pua]_[a-f0-9]{40}\\b"),
            Pattern.compile("AIza[0-9A-Za-z_\\-]{35}"),
            Pattern.compile("npm_[A-Za-z0-9]{36}"),
            Pattern.compile("(?i)jdbc:[a-z]+://[^\\s]*password=[^&\\s;$]{4,}"));

    public static List<String> secrets(Map<String, List<String>> added) {
        List<String> hits = new ArrayList<>();
        Set<String> exts = new LinkedHashSet<>(Ctx.cfg.list("tests.codeExtensions"));
        for (Map.Entry<String, List<String>> e : added.entrySet()) {
            String file = e.getKey();
            boolean config = CONFIG_FILE.matcher(file).matches();
            boolean code = exts.contains(ext(file)) && !Glob.any(Ctx.cfg.list("paths.test"), file);
            for (String line : e.getValue()) {
                if (line.contains("guard:allow-secret")) continue;
                boolean hit = TOKENS.stream().anyMatch(p -> p.matcher(line).find())
                        || config && assigns(CONFIG_ASSIGNMENT.matcher(line))
                        || code && assigns(CODE_LITERAL.matcher(line));
                if (hit) hits.add(file + ": " + mask(line.trim()));
            }
        }
        return hits;
    }

    private static boolean assigns(Matcher m) {
        while (m.find()) if (!REFERENCE_KEY.matcher(m.group(1)).matches() && !REFERENCE_VALUE.matcher(m.group(2)).matches()) return true;
        return false;
    }

    private static String mask(String line) {
        return line.length() > 24 ? line.substring(0, 12) + "...(masked)" : "(masked)";
    }

    public static String ext(String file) {
        String name = file.substring(file.replace('\\', '/').lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ---------------- tests ----------------

    public static boolean containsDisabled(String text) {
        for (String re : Ctx.cfg.list("tests.disabledPatterns")) if (Pattern.compile(re).matcher(text).find()) return true;
        return false;
    }

    public static List<String> disabledTests(Map<String, List<String>> added) {
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : added.entrySet()) {
            if (!Glob.any(Ctx.cfg.list("paths.test"), e.getKey())) continue;
            for (String line : e.getValue()) if (containsDisabled(line)) hits.add(e.getKey() + ": " + line.trim());
        }
        return hits;
    }

    public static boolean isMainCode(String f) {
        return Ctx.cfg.list("tests.codeExtensions").contains(ext(f)) && Glob.any(Ctx.cfg.list("paths.main"), f) && !Glob.any(Ctx.cfg.list("paths.test"), f);
    }

    /** Main code changed but no test code changed. */
    public static boolean testsMissing(List<String> files) {
        if (!Ctx.cfg.bool("tests.requireTestsForMainChanges", true)) return false;
        boolean main = files.stream().anyMatch(Checks::isMainCode);
        boolean tests = files.stream().anyMatch(f -> Glob.any(Ctx.cfg.list("paths.test"), f));
        return main && !tests;
    }

    // ---------------- PR policy (no CI change needed) ----------------

    public record Result(List<String> passes, List<String> fails, List<String> warns) {
        boolean ok() { return fails.isEmpty(); }
    }

    public static Result prPolicy(String title, boolean withTrace) {
        List<String> passes = new ArrayList<>(), fails = new ArrayList<>(), warns = new ArrayList<>();
        String base = Git.base();
        String branch = Git.branch();
        if (!Git.refExists(base)) {
            fails.add("Base " + base + " is missing locally: run git fetch origin");
            return new Result(passes, fails, warns);
        }
        check(branch.matches(Ctx.cfg.str("git.branchPattern", ".*")), "Branch '" + branch + "' matches the naming rule", passes, fails);
        if (title != null) {
            Matcher km = Ctx.cfg.keyPattern().matcher(title);
            check(km.find(), "PR title '" + title + "' names a Jira issue of " + Ctx.cfg.projectKeys(), passes, fails);
        }
        List<String> subjects = Git.lines(Proc.run(Ctx.root, "git", "log", "--no-merges", "--format=%s", base + "..HEAD").out());
        List<String> bad = subjects.stream().filter(s -> !s.matches(Ctx.cfg.str("git.commitPattern", ".*")) && !s.startsWith("fixup! ") && !s.startsWith("Revert \"")).toList();
        check(bad.isEmpty(), "All " + subjects.size() + " commit subjects follow the format" + (bad.isEmpty() ? "" : ": bad -> " + bad), passes, fails);
        List<String> files = Git.lines(Proc.run(Ctx.root, "git", "diff", "--name-only", "--diff-filter=ACMR", base + "...HEAD").out());
        check(!testsMissing(files), "Main code changes come with test changes", passes, fails);
        Map<String, List<String>> added = Git.addedLines(Proc.run(Ctx.root, "git", "diff", "--unified=0", "--no-color", "--no-ext-diff", base + "...HEAD").out());
        List<String> disabled = disabledTests(added);
        check(disabled.isEmpty(), "No test was disabled or focused" + (disabled.isEmpty() ? "" : ": " + disabled), passes, fails);
        List<String> secrets = secrets(added);
        check(secrets.isEmpty(), "No secrets added" + (secrets.isEmpty() ? "" : ": " + secrets), passes, fails);
        String story = Work.currentStory();
        List<String> outside = story.isEmpty() ? null : outsideScope(story, Git.lines(Proc.run(Ctx.root, "git", "diff", "--name-only", base + "...HEAD").out()));
        if (outside != null) {
            check(outside.isEmpty(), "Changed code stays inside the allowed paths of " + story + "'s tasks"
                    + (outside.isEmpty() ? "" : ": outside -> " + outside + " (change the LLD's task scope and get it re-approved, or move this change to its own story)"), passes, fails);
        }
        List<String> patchContracts = Lanes.contractChangesInPatch(story, files);
        if (Lanes.enabled() && !story.isEmpty()) {
            check(patchContracts.isEmpty(), "No contract file changed in a patch-lane story" + (patchContracts.isEmpty() ? ""
                    : ": " + patchContracts + " changed (raise the LLD's lane to component, get it approved, run tasks create again)"), passes, fails);
        }
        List<String> guardrails = files.stream().filter(f -> Glob.any(Ctx.cfg.list("paths.protected"), f)).toList();
        if (!guardrails.isEmpty()) warns.add(guardrails.size() + " guardrail file(s) changed (CODEOWNERS review required)");
        if (withTrace) {
            if (!story.isEmpty()) {
                List<String> trace = Trace.problems(story);
                check(trace.isEmpty(), "Every test scenario of " + story + "'s tasks links to an existing test" + (trace.isEmpty() ? "" : ": " + trace), passes, fails);
            }
        }
        return new Result(passes, fails, warns);
    }

    /** Changed code files outside every task's scope, or null when the story has no tasks (nothing to compare with). */
    public static List<String> outsideScope(String story, List<String> changed) {
        List<String> scopes = new ArrayList<>();
        for (Object t : Work.tasks(Work.state(story)).values()) scopes.addAll(Work.strings(Json.obj(t).get("scope")));
        if (scopes.isEmpty()) return null;
        // guardrail files (the kit's own .agent/src among them) have their own rule: CODEOWNERS review
        return changed.stream().filter(f -> Glob.any(Ctx.cfg.list("paths.code"), f) && !Glob.any(scopes, f) && !Glob.any(Ctx.cfg.list("paths.ask"), f)
                && !Glob.any(Ctx.cfg.list("paths.protected"), f) && !Glob.any(Ctx.cfg.list("paths.doc"), f)).toList();
    }

    private static void check(boolean ok, String text, List<String> passes, List<String> fails) {
        (ok ? passes : fails).add(text);
    }

    public static int printPolicy(Result r) {
        System.out.println("==== PR policy check ====");
        r.passes().forEach(p -> System.out.println("PASS  " + p));
        r.warns().forEach(w -> System.out.println("WARN  " + w));
        r.fails().forEach(f -> System.out.println("FAIL  " + f));
        System.out.println(r.ok() ? "RESULT: PASSED" : "RESULT: FAILED (" + r.fails().size() + ")");
        return r.ok() ? 0 : 1;
    }

    public static Map<String, String> options(String[] args, int from) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String k = args[i].substring(2);
                String v = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
                m.put(k, v);
            }
        }
        return m;
    }

    static boolean exists(String rel) {
        return Files.exists(Ctx.root.resolve(rel));
    }

    static Path path(String rel) {
        return Ctx.root.resolve(rel);
    }
}
