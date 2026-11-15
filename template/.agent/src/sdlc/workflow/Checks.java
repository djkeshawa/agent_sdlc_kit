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
import sdlc.core.Trusted;
import sdlc.core.Work;
import sdlc.util.Glob;
import sdlc.util.Json;
import sdlc.util.Proc;

public final class Checks {
    private Checks() {}

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

    public static boolean containsDisabled(String text) {
        for (String re : Ctx.cfg.list("tests.disabledPatterns")) {
            try {
                if (Pattern.compile(re).matcher(text).find()) return true;
            } catch (StackOverflowError e) {
                return true;
            }
        }
        return false;
    }

    public static List<String> disabledTests(Map<String, List<String>> added) {
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : added.entrySet()) {
            if (!Glob.any(Ctx.cfg.list("paths.test"), e.getKey())) continue;
            for (String line : e.getValue()) if (line.length() <= sdlc.check.Integrity.LONGEST && containsDisabled(line)) hits.add(e.getKey() + ": " + line.trim());
        }
        return hits;
    }

    public static boolean isMainCode(String f) {
        return Ctx.cfg.list("tests.codeExtensions").contains(ext(f)) && Glob.any(Ctx.cfg.list("paths.main"), f) && !Glob.any(Ctx.cfg.list("paths.test"), f);
    }

    public static boolean testsMissing(List<String> files) {
        if (!Ctx.cfg.bool("tests.requireTestsForMainChanges", true)) return false;
        boolean main = files.stream().anyMatch(Checks::isMainCode);
        boolean tests = files.stream().anyMatch(f -> Glob.any(Ctx.cfg.list("paths.test"), f));
        return main && !tests;
    }

    public record Result(List<String> passes, List<String> fails, List<String> warns) {
        boolean ok() { return fails.isEmpty(); }
    }

    public static Result prPolicy(String title, boolean withTrace) {
        return prPolicy(title, withTrace, "");
    }

    // In a pull request build the rules are the base branch tip's: a pull request cannot loosen the rules it is judged by.
    public static Result prPolicy(String title, boolean withTrace, String declaredStory) {
        sdlc.core.Config own = Ctx.cfg;
        try {
            return policy(title, withTrace, declaredStory);
        } finally {
            Ctx.cfg = own;
        }
    }

    private static Result policy(String title, boolean withTrace, String declaredStory) {
        List<String> passes = new ArrayList<>(), fails = new ArrayList<>(), warns = new ArrayList<>();
        String base = Git.base();
        String shown = Trusted.shortName(base);
        String branch = Git.branch();
        if (!Git.refExists(base)) {
            fails.add("Base " + shown + " is missing locally: run git fetch origin");
            return new Result(passes, fails, warns);
        }
        if (!Proc.run(Ctx.root, "git", "merge-base", base, "HEAD").ok()) {
            // without a common ancestor every diff below would fail or be empty, and an empty diff passes every rule
            fails.add("This checkout has no commit in common with " + shown + " (a shallow clone?): fetch more history, e.g. git fetch --unshallow");
            return new Result(passes, fails, warns);
        }
        boolean ci = Trusted.inCi();
        if (ci) {
            sdlc.core.Config trusted;
            try {
                trusted = Trusted.config(base);
            } catch (RuntimeException e) {
                fails.add("The rules on " + shown + " (.agent/sdlc.yml) cannot be read: " + e.getMessage());
                return new Result(passes, fails, warns);
            }
            if (trusted == null) {
                if (!Trusted.flag(Trusted.FIRST_INSTALLATION)) {
                    fails.add(shown + " has no .agent/sdlc.yml, so there are no rules to judge this pull request by. For the pull request that installs the kit, "
                            + "the pipeline sets " + Trusted.FIRST_INSTALLATION + "=true (the pipeline, not the pull request)");
                    return new Result(passes, fails, warns);
                }
                warns.add(shown + " has no .agent/sdlc.yml and the pipeline says this is the first installation (" + Trusted.FIRST_INSTALLATION
                        + "): this pull request's own rules were used (a tech lead reviews it)");
            } else {
                Ctx.cfg = trusted;
                passes.add("The rules are read from " + shown + ", not from this pull request");
            }
        }
        check(branch.matches(Ctx.cfg.str("git.branchPattern", ".*")), "Branch '" + branch + "' matches the naming rule", passes, fails);
        if (title != null) {
            Matcher km = Ctx.cfg.keyPattern().matcher(title);
            check(km.find(), "PR title '" + title + "' names a Jira issue of " + Ctx.cfg.projectKeys(), passes, fails);
        }
        Proc log = Proc.run(Ctx.root, "git", "log", "--no-merges", "--format=%s", base + "..HEAD");
        if (!log.ok()) throw new sdlc.core.UsageException("git log failed (" + log.err().strip() + ")");
        List<String> subjects = Git.lines(log.out());
        List<String> bad = subjects.stream().filter(s -> !s.matches(Ctx.cfg.str("git.commitPattern", ".*")) && !s.startsWith("fixup! ") && !s.startsWith("Revert \"")).toList();
        check(bad.isEmpty(), "All " + subjects.size() + " commit subjects follow the format" + (bad.isEmpty() ? "" : ": bad -> " + bad), passes, fails);
        List<String> files = Git.changed("--diff-filter=ACMR", base + "...HEAD");
        List<String> everyChange = Git.changed(base + "...HEAD");
        check(!testsMissing(files), "Main code changes come with test changes", passes, fails);
        Map<String, List<String>> added = Git.addedLines(Git.patch(base + "...HEAD"));
        Set<String> disabled = new LinkedHashSet<>();
        for (String hit : disabledTests(added)) if (containsDisabled(sdlc.check.Integrity.withoutBuiltInSwitches(hit.substring(hit.indexOf(": ") + 2)))) disabled.add(hit);
        for (String f : files) {
            if (!sdlc.check.Integrity.testFile(f, Ctx.cfg.list("paths.test"))) continue;
            sdlc.check.Integrity.Lint lint = sdlc.check.Integrity.lint(added.getOrDefault(f, List.of()));
            for (String hit : lint.sure()) disabled.add(f + ": " + hit);
            if (!lint.notes().isEmpty()) {
                warns.add(f + ": may switch a test off under a condition or in a test's body, or is no switch at all (a text check; sdlc check shows whether a test"
                        + " stopped running): " + lint.notes());
            }
            int unread = sdlc.check.Integrity.unlinted(added.getOrDefault(f, List.of()));
            if (unread > 0) {
                warns.add(f + ": " + unread + " added line(s) longer than " + sdlc.check.Integrity.LONGEST + " characters were not read for switches (sdlc check runs the file)");
            }
        }
        check(disabled.isEmpty(), "No switch that turns a test off or focuses one was found on the added test lines (a text check; a test run is the proof)"
                + (disabled.isEmpty() ? "" : ": " + disabled), passes, fails);
        List<String> secrets = secrets(added);
        check(secrets.isEmpty(), "No secrets added" + (secrets.isEmpty() ? "" : ": " + secrets), passes, fails);
        List<String> state = everyChange.stream().filter(f -> f.startsWith(".sdlc/")).toList();
        check(state.isEmpty(), "No local state (.sdlc/) is committed" + (state.isEmpty() ? "" : ": " + state), passes, fails);
        String story = ci ? "" : Work.currentStory();
        List<String> outside = story.isEmpty() ? null : outsideScope(story, everyChange);
        if (outside != null) {
            check(outside.isEmpty(), "Changed code stays inside the allowed paths of " + story + "'s tasks"
                    + (outside.isEmpty() ? "" : ": outside -> " + outside + " (change the LLD's task scope and get it re-approved, or move this change to its own story)"), passes, fails);
        }
        List<String> broken = sdlc.core.Locks.broken(Ctx.root);
        if (!sdlc.core.Locks.locked(Ctx.root).isEmpty() || !broken.isEmpty()) check(broken.isEmpty(), "Locked acceptance tests unchanged" + (broken.isEmpty() ? "" : ": " + broken), passes, fails);
        List<String> loosened = sdlc.core.Locks.loosened(Ctx.root, Contracts.mergeBase(base));
        // Taking a lock away is never the reviewed exception: the test could then be deleted in the same pull request.
        Set<String> removable = Trusted.list(Trusted.LOCKS_REMOVABLE);
        List<String> removed = loosened.stream().filter(l -> l.endsWith(": lock removed") && !removable.contains(storyOf(l))).toList();
        List<String> released = loosened.stream().filter(l -> l.endsWith(": lock removed") && removable.contains(storyOf(l))).toList();
        List<String> rehashed = loosened.stream().filter(l -> !l.endsWith(": lock removed")).toList();
        if (!removed.isEmpty()) {
            fails.add("A lock on " + shown + " is not removed by a pull request: " + removed + " (a locked test may be locked again with new content in QE's own pull request; "
                    + "to retire one, the pipeline names its story in " + Trusted.LOCKS_REMOVABLE + ")");
        }
        if (!released.isEmpty()) warns.add("acceptance test locks removed, as the pipeline allows (" + Trusted.LOCKS_REMOVABLE + "): " + released + " (QE leads review it, CODEOWNERS)");
        if (!rehashed.isEmpty()) {
            if (lockOnly(everyChange, Contracts.mergeBase(base))) warns.add("acceptance tests of the base branch are locked again with new content in a lock-only pull request: " + rehashed + " (QE leads review it, CODEOWNERS)");
            else fails.add("Locked acceptance tests keep the lock they have on " + shown + ": " + rehashed
                    + " (QE changes a lock in its own pull request that holds only the lock files and their tests)");
        }
        String mergeBase = Contracts.mergeBase(base);
        Map<String, sdlc.core.Locks.Entry> atBranchPoint = sdlc.core.Locks.lockedAt(Ctx.root, mergeBase, new ArrayList<>());
        List<String> lockedOnBase = new ArrayList<>(), relocked = new ArrayList<>();
        List<String> damagedOnBase = new ArrayList<>();
        Map<String, sdlc.core.Locks.Entry> onBase = sdlc.core.Locks.lockedAt(Ctx.root, base, damagedOnBase);
        if (!damagedOnBase.isEmpty()) fails.add("Lock files on " + shown + " cannot be read, so its locked tests cannot be checked: " + damagedOnBase);
        for (sdlc.core.Locks.Entry e : onBase.values()) {
            sdlc.core.Locks.Entry before = atBranchPoint.get(e.path());
            if (before != null && before.hash().equals(e.hash())) continue;
            if (!everyChange.contains(e.path())) continue;
            java.nio.file.Path f = Ctx.root.resolve(e.path());
            String content;
            try {
                content = java.nio.file.Files.isRegularFile(f) ? sdlc.core.Locks.hash(java.nio.file.Files.readAllBytes(f)) : "";
            } catch (java.io.IOException ex) {
                content = "";
            }
            if (content.equals(e.hash())) continue;
            sdlc.core.Locks.Entry own = sdlc.core.Locks.locked(Ctx.root).get(e.path());
            (own != null && own.hash().equals(content) ? relocked : lockedOnBase).add(e.path() + " (" + e.story() + ")");
        }
        if (!relocked.isEmpty()) {
            if (lockOnly(everyChange, base)) warns.add("tests locked on " + shown + " are locked again in a lock-only pull request: " + relocked + " (QE leads review it, CODEOWNERS)");
            else lockedOnBase.addAll(relocked);
        }
        if (!lockedOnBase.isEmpty()) {
            fails.add("This pull request changes tests that " + shown + " has locked since the branch was made: " + lockedOnBase
                    + " (the code must pass them as QE locked them; update the branch from " + shown + ")");
        }
        List<String> lockChanges = everyChange.stream().filter(f -> f.startsWith(sdlc.core.Locks.DIR + "/")).toList();
        if (!lockChanges.isEmpty() && loosened.isEmpty()) warns.add("acceptance test locks added: " + lockChanges + " (a QE lead reviews them, CODEOWNERS)");
        Contracts.Policy contracts = Contracts.prPolicy(story.isEmpty() ? declaredStory(declaredStory, branch, fails, warns) : story, base, files);
        passes.addAll(contracts.passes());
        fails.addAll(contracts.fails());
        warns.addAll(contracts.warns());
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

    private static String storyOf(String loosened) {
        int close = loosened.lastIndexOf("): "), open = loosened.lastIndexOf(" (", close);
        return open < 0 || close < 0 ? "" : loosened.substring(open + 2, close);
    }

    public static boolean lockOnly(List<String> changed, String ref) {
        Set<String> locked = new LinkedHashSet<>(sdlc.core.Locks.locked(Ctx.root).keySet());
        locked.addAll(sdlc.core.Locks.lockedAt(Ctx.root, ref, new ArrayList<>()).keySet());
        List<String> tests = Ctx.cfg.list("paths.test");
        return changed.stream().allMatch(f -> f.startsWith(sdlc.core.Locks.DIR + "/") || locked.contains(f) && Glob.any(tests, f));
    }

    public static String declaredStory(String declared, String branch, List<String> fails, List<String> warns) {
        String fromBranch = storyFromBranch(branch);
        String d = declared == null ? "" : declared.trim();
        if (d.isEmpty()) {
            if (!fromBranch.isEmpty() && Trusted.inCi()) {
                warns.add("the story " + fromBranch + " is taken from the branch name, which the author chooses: pass check-pr --story <KEY> from the pull request");
            }
            return fromBranch;
        }
        if (!Ctx.cfg.keyPattern().matcher(d).matches()) {
            fails.add("--story " + d + " is not an issue key of " + Ctx.cfg.projectKeys());
            return "";
        }
        if (!fromBranch.isEmpty() && !fromBranch.equals(d)) fails.add("The pull request declares " + d + " but its branch names " + fromBranch + ": one story per pull request");
        return d;
    }

    static String storyFromBranch(String branch) {
        Matcher m = Ctx.cfg.keyPattern().matcher(branch);
        return m.find() ? m.group() : "";
    }

    public static List<String> outsideScope(String story, List<String> changed) {
        List<String> scopes = new ArrayList<>();
        for (Object t : Work.tasks(Work.state(story)).values()) scopes.addAll(Work.strings(Json.obj(t).get("scope")));
        if (scopes.isEmpty()) return null;
        Map<String, sdlc.core.Locks.Entry> locked = sdlc.core.Locks.locked(Ctx.root);
        return changed.stream().filter(f -> Glob.any(Ctx.cfg.list("paths.code"), f) && !Glob.any(scopes, f) && !locked.containsKey(f) && !Glob.any(Ctx.cfg.list("paths.ask"), f)
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
