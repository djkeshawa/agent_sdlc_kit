package sdlc.check;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sdlc.check.Witness.Kind;
import sdlc.check.Witness.Verdict;
import sdlc.core.UsageException;

/**
 * sdlc check: what can be said about the change in front of you, with nothing to configure.
 * It runs the change's own tests before and after the change, reads the diff for damage to the
 * things that check it, and says what it did not check. It writes nothing to the working tree.
 */
public final class Check {
    private Check() {}

    public static int run(Map<String, String> opt) {
        int runs = number(opt, "runs", 3);
        int timeout = number(opt, "timeout", 900);
        if (sdlc.core.Trusted.inCi()) {
            // in a pull request build the scan's rules are the base branch's, or the built-in ones: never the checkout's own
            sdlc.core.Config trusted = sdlc.core.Git.refExists(sdlc.core.Trusted.baseRef()) ? sdlc.core.Trusted.config(sdlc.core.Trusted.baseRef()) : null;
            sdlc.core.Ctx.cfg = trusted != null ? trusted : sdlc.core.Config.parse("stack: java-maven\n");
        }
        if (opt.containsKey("mandate")) return MandateVerdict.run(opt, runners(opt), runs, timeout);
        if (opt.containsKey("since")) return since(opt, runs, timeout);
        if (opt.containsKey("stop-hook")) return stopHook(opt, opt.containsKey("runs") ? runs : 1, timeout);
        Change change = Change.of(opt.get("base"));
        PrintStream out = sdlc.util.SafeOut.stream(System.out);
        String sha = change.base.length() > 7 ? change.base.substring(0, 7) : change.base;
        if (change.isEmpty()) {
            out.println("sdlc check: nothing has changed against " + change.baseName + " (" + sha + ").");
            return 0;
        }
        out.println("sdlc check: " + change.files.size() + " file" + (change.files.size() == 1 ? "" : "s") + " changed against " + change.baseName + " (" + sha + ")");
        Witness.Result w = opt.containsKey("no-run") ? null : Witness.run(change, runners(opt), runs, timeout);
        List<Finding> findings = Integrity.scan(change);
        boolean failed = false;

        out.println();
        out.println("Tests this change adds or edits");
        if (w == null) {
            out.println("  not run (--no-run)");
        } else if (w.verdicts().isEmpty()) {
            out.println("  none that this version can run" + (w.unsupported().isEmpty() ? "." : " (see Not checked)."));
        } else {
            failed |= section(out, w, Kind.FAILS_AFTER, n -> n + (n == 1 ? " fails" : " fail") + " after the change", true);
            failed |= section(out, w, Kind.OLD_FAILS_AFTER, n -> n + (n == 1 ? " test passed before the change and fails" : " tests passed before the change and fail")
                    + " on the code after it as the test was, and the change took " + (n == 1 ? "it" : "them") + " out of the way: removed, renamed or switched off", true);
            section(out, w, Kind.SENSITIVE, n -> n + (n == 1 ? " fails" : " fail") + " before the change and " + (n == 1 ? "passes" : "pass") + " after it"
                    + (w.runs() > 1 ? ", in " + w.runs() + " of " + w.runs() + " runs" : ""), false);
            failed |= section(out, w, Kind.PASSES_ON_BOTH, n -> n + (n == 1 ? " new test passes" : " new tests pass") + " before and after, so "
                    + (n == 1 ? "it does" : "they do") + " not test the change", false);
            failed |= section(out, w, Kind.NOT_RUN_NOW, n -> n + (n == 1 ? " test that ran before the change does" : " tests that ran before the change do") + " not run now", true);
            section(out, w, Kind.UNSTABLE, n -> n + " gave different results from run to run", true);
            section(out, w, Kind.UNKNOWN, n -> n + " could not be judged", true);
            if (w.verdicts().stream().anyMatch(Verdict::newCode)) {
                out.println("      A test of code that did not exist before the change cannot fail there for the right reason, so it is not counted either way.");
            }
            section(out, w, Kind.SKIPPED, n -> n + (n == 1 ? " is" : " are") + " skipped", false);
            long old = w.count(Kind.EXISTING_PASSES_ON_BOTH);
            if (old > 0) out.println("  " + old + " test" + (old == 1 ? "" : "s") + " that " + (old == 1 ? "was" : "were") + " already in the edited files " + (old == 1 ? "passes" : "pass") + " before and after (not counted)");
            long off = w.count(Kind.EXISTING_SKIPPED);
            if (off > 0) out.println("  " + off + (off == 1 ? " test is skipped as before the change: the file skipped a test of its name before it too"
                    : " tests are skipped as before the change: the file skipped tests of their names before it too") + " (not counted)");
            if (w.count(Kind.SENSITIVE) > 0) {
                out.println("  A test that fails before and passes after is sensitive to the change. That is not evidence that the change is correct.");
            }
            if (w.verdicts().stream().anyMatch(v -> v.runner().equals("Vitest")) && change.files.stream().anyMatch(f -> f.path().matches("(.*/)?(package\\.json|package-lock\\.json|yarn\\.lock|pnpm-lock\\.yaml)"))) {
                out.println("  The change touches package.json or a lock file. Both sides ran with the packages installed now, so a fix that is a new package version shows as passing on both.");
            }
        }

        out.println();
        out.println("Integrity");
        if (findings.isEmpty()) out.println("  nothing found");
        for (Finding f : findings) {
            out.println("  " + (f.level() == Finding.Level.PROBLEM ? "PROBLEM  " : "note     ") + f.kind() + ": " + f.path() + (f.detail().isBlank() ? "" : "  " + f.detail().strip()));
            failed |= f.level() == Finding.Level.PROBLEM;
        }

        out.println();
        out.println("Not checked");
        for (String line : notChecked(w)) out.println("  - " + line);
        return failed ? 1 : 0;
    }

    /**
     * For the self test: makes the four checkouts of a change and closes them. Returns their
     * folders, which must all be gone now, or an empty list when one of them was not there while
     * open.
     */
    public static List<java.nio.file.Path> checkoutsAfterClose(Change change) {
        List<java.nio.file.Path> made = new ArrayList<>();
        Runner runner = new SelfTestRunner();
        try (Sides sides = new Sides(change, List.of(runner))) {
            for (Scratch s : List.of(sides.head(), sides.base(), sides.old(), sides.oldTests(runner))) {
                if (!java.nio.file.Files.isDirectory(s.dir)) return List.of();
                made.add(s.dir);
            }
        }
        return made;
    }

    /**
     * For the self test: runs the witness of a change once with the stand-in runner, and says
     * whether it made the fourth checkout ("made" or "not made") and whether the checkout after
     * the change then still holds exactly the files of the change's tree ("head intact" or "head
     * changed"; what the stand-in runner builds, .built/, is not part of the tree).
     */
    public static String fourthCheckout(Change change) throws java.io.IOException {
        List<Runner> runners = List.of(new SelfTestRunner());
        try (Sides sides = new Sides(change, runners); Scratch fresh = Scratch.of(change.head, "compare")) {
            Witness.run(change, runners, 1, 900, sides);
            boolean intact = files(sides.head().dir).equals(files(fresh.dir));
            return (sides.madeOldTests() ? "made" : "not made") + ", " + (intact ? "head intact" : "head changed");
        }
    }

    /**
     * For the self test: runs the witness of a change once with two stand-in runners, the first of
     * which owns the test files under a folder (apart) and the second all others, and says of each
     * runner's fourth checkout which files differ from the change's tree: "runner: a, b" for each
     * runner that made one, joined by "; ".
     */
    public static String fourthCheckoutsApart(Change change, String apart) throws java.io.IOException {
        List<Runner> runners = List.of(new SelfTestRunner(apart), new SelfTestRunner());
        try (Sides sides = new Sides(change, runners); Scratch fresh = Scratch.of(change.head, "compare")) {
            Witness.run(change, runners, 1, 900, sides);
            Map<String, String> tree = files(fresh.dir);
            List<String> out = new ArrayList<>();
            for (Runner r : runners) {
                if (!sides.madeOldTests(r)) continue;
                Map<String, String> there = files(sides.oldTests(r).dir);
                java.util.Set<String> differ = new java.util.TreeSet<>();
                for (String p : tree.keySet()) if (!tree.get(p).equals(there.get(p))) differ.add(p);
                for (String p : there.keySet()) if (!tree.containsKey(p)) differ.add(p);
                out.add(r.name() + ": " + String.join(", ", differ));
            }
            return String.join("; ", out);
        }
    }

    /** Every file under a folder, outside .built/, by its path, with its bytes as text. */
    private static Map<String, String> files(java.nio.file.Path dir) throws java.io.IOException {
        Map<String, String> out = new java.util.TreeMap<>();
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path p : s.filter(java.nio.file.Files::isRegularFile).toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith(".built/")) out.put(rel, java.util.HexFormat.of().formatHex(java.nio.file.Files.readAllBytes(p)));
            }
        }
        return out;
    }

    private static List<Runner> runners(Map<String, String> opt) {
        return opt.containsKey("selftest-runner") ? List.of(new SelfTestRunner()) : Runners.all();
    }

    /**
     * --since 30d: the same check, replayed over each commit of the recent history, one line or two
     * per commit and a summary. A report on the past: it never fails.
     */
    private static int since(Map<String, String> opt, int runs, int timeout) {
        String window = window(opt.get("since"));
        int max = number(opt, "max", 20);
        PrintStream out = sdlc.util.SafeOut.stream(System.out);
        sdlc.util.Proc log = sdlc.util.Proc.run(sdlc.core.Ctx.root, "git", "log", "--first-parent", "--since=" + window, "--format=%H%x09%cs%x09%s", "HEAD");
        if (!log.ok()) throw new UsageException("git log failed: " + log.err().strip());
        List<String> commits = log.out().isBlank() ? List.of() : List.of(log.out().strip().split("\\R"));
        out.println("sdlc check --since " + opt.get("since") + ": " + commits.size() + " commit" + (commits.size() == 1 ? "" : "s") + " on this branch since " + window
                + (commits.size() > max ? ", the newest " + max + " checked (--max N for more)" : ""));
        long sensitive = 0, both = 0, unknown = 0, failing = 0, untested = 0, problems = 0, notRun = 0, takenAway = 0;
        java.util.Set<Kind> least = java.util.EnumSet.noneOf(Kind.class); // kinds whose total is a least number
        int checked = 0;
        for (String line : commits) {
            if (checked++ >= max) break;
            String[] f = line.split("\t", 3);
            Change change = Change.ofCommit(f[0]);
            Witness.Result w = opt.containsKey("no-run") ? null : Witness.run(change, runners(opt), runs, timeout);
            List<Finding> findings = Integrity.scan(change);
            out.println();
            out.println(f[0].substring(0, 7) + "  " + f[1] + "  " + (f.length > 2 ? f[2] : ""));
            if (w == null) {
                out.println("    tests: not run (--no-run)");
            } else if (w.verdicts().isEmpty()) {
                out.println("    tests: none added or edited that this version can run");
                untested++;
            } else {
                List<String> parts = new ArrayList<>();
                part(parts, w, Kind.SENSITIVE, "fail before and pass after");
                part(parts, w, Kind.PASSES_ON_BOTH, "new, pass on both sides");
                part(parts, w, Kind.FAILS_AFTER, "fail after");
                part(parts, w, Kind.OLD_FAILS_AFTER, "passed before, fail as they were after, taken out");
                part(parts, w, Kind.NOT_RUN_NOW, "ran before, do not run after");
                part(parts, w.count(Kind.UNKNOWN) + w.count(Kind.UNSTABLE), "could not be judged");
                part(parts, w, Kind.SKIPPED, "skipped");
                out.println("    tests: " + (parts.isEmpty() ? onlyOld(w) : String.join("; ", parts)));
                for (Verdict v : w.verdicts()) {
                    String which = v.file() + "  " + (v.name().isEmpty() ? v.detail() : v.name());
                    if (v.kind() == Kind.PASSES_ON_BOTH) out.println("        does not test the change: " + which);
                    else if (v.kind() == Kind.NOT_RUN_NOW) out.println("        ran before, do not run after: " + which);
                    else if (v.kind() == Kind.OLD_FAILS_AFTER) out.println("        passed before, fails as it was after, taken out: " + which);
                }
                for (Kind k : List.of(Kind.PASSES_ON_BOTH, Kind.NOT_RUN_NOW, Kind.OLD_FAILS_AFTER)) if (w.atLeast(k)) least.add(k);
                sensitive += w.count(Kind.SENSITIVE);
                both += w.count(Kind.PASSES_ON_BOTH);
                failing += w.count(Kind.FAILS_AFTER);
                unknown += w.count(Kind.UNKNOWN) + w.count(Kind.UNSTABLE);
                notRun += w.count(Kind.NOT_RUN_NOW);
                takenAway += w.count(Kind.OLD_FAILS_AFTER);
            }
            for (Finding x : findings) {
                if (x.level() != Finding.Level.PROBLEM) continue;
                out.println("    PROBLEM  " + x.kind() + ": " + x.path() + (x.detail().isBlank() ? "" : "  " + x.detail().strip()));
                problems++;
            }
        }
        int n = Math.min(commits.size(), max);
        if (n > 0) {
            out.println();
            out.println("Summary of " + n + " commit" + (n == 1 ? "" : "s"));
            out.println("  " + sensitive + (sensitive == 1 ? " new or edited test fails before its commit and passes after it" : " new or edited tests fail before their commit and pass after it"));
            out.println("  " + (least.contains(Kind.PASSES_ON_BOTH) ? "at least " : "") + both
                    + (both == 1 ? " new test passes on both sides, so does not test its commit" : " new tests pass on both sides, so do not test their commit"));
            out.println("  " + unknown + " could not be judged; " + failing + " fail after their commit");
            out.println("  " + (least.contains(Kind.NOT_RUN_NOW) ? "at least " : "") + notRun + (notRun == 1 ? " test ran before its commit and does" : " tests ran before their commit and do")
                    + " not run after it");
            out.println("  " + (least.contains(Kind.OLD_FAILS_AFTER) ? "at least " : "") + takenAway + (takenAway == 1 ? " test passed before its commit, fails on it as it was, and was" : " tests passed before their commit, fail on it as they were, and were")
                    + " taken out of the way");
            out.println("  " + untested + " commit" + (untested == 1 ? "" : "s") + " add or edit no test this version can run");
            out.println("  " + problems + " integrity problem" + (problems == 1 ? "" : "s"));
        }
        return 0;
    }

    /**
     * --stop-hook: for an agent's Stop hook (Claude Code: exit code 2 keeps the agent working, and
     * what is on stderr is the reason it is given). It blocks on what the agent can fix and is
     * certain: a changed test that fails, a new test that passes with or without the change, tests
     * that ran before the change and do not run after it (deleted, switched off or hidden), an
     * old test that fails on the change as it was and was taken out of the way, a problem of the
     * integrity scan. Never on "could
     * not be judged", nor on a note. Says nothing when all is well.
     *
     * The host says on stdin whether the agent is already continuing because of a stop hook
     * (stop_hook_active). Then the result is printed and the agent is let go: a second block on
     * the same condition would only loop.
     */
    private static int stopHook(Map<String, String> opt, int runs, int timeout) {
        boolean again = stdin().replaceAll("\\s+", "").contains("\"stop_hook_active\":true");
        Change change;
        try {
            change = Change.of(opt.get("base"));
        } catch (UsageException e) {
            return 0; // not a git repository, or no base: nothing to say, and a hook must not trap the agent
        }
        if (change.isEmpty()) return 0;
        Witness.Result w = Witness.run(change, runners(opt), runs, timeout);
        List<String> reasons = new ArrayList<>();
        for (Verdict v : w.verdicts()) {
            String test = v.file() + (v.name().isEmpty() ? "" : " \u00bb " + v.name());
            // a verdict about a file says in its detail which tests it means
            String file = v.file() + " (" + v.detail() + ")";
            boolean whole = v.name().isEmpty();
            if (v.kind() == Kind.FAILS_AFTER) reasons.add("fails: " + test + (v.detail().isBlank() ? "" : " (" + v.detail() + ")"));
            else if (v.kind() == Kind.OLD_FAILS_AFTER) reasons.add("passed before your change, fails on your code as it was, and your change took it out of the way: " + test + " (" + v.detail() + ")");
            else if (v.kind() == Kind.PASSES_ON_BOTH) reasons.add(whole ? "new tests pass with and without your change, so they do not test it: " + file
                    : "passes with and without your change, so it does not test it: " + test);
            else if (v.kind() == Kind.NOT_RUN_NOW) reasons.add("tests that ran before your change do not run now: " + file);
        }
        for (Finding f : Integrity.scan(change)) if (f.level() == Finding.Level.PROBLEM) reasons.add(f.kind() + ": " + f.path() + (f.detail().isBlank() ? "" : " (" + f.detail().strip() + ")"));
        if (reasons.isEmpty()) return 0;
        StringBuilder msg = new StringBuilder("sdlc check found " + reasons.size() + " thing" + (reasons.size() == 1 ? "" : "s") + " to fix before you finish:\n");
        for (String r : reasons) msg.append("- ").append(r).append('\n');
        msg.append("A new test must fail without the change and pass with it. Fix the test or the code; do not delete the test to get past this, and do not switch tests off. "
                + "If a finding is wrong, say so in your final message and stop.");
        System.err.println(sdlc.util.SafeOut.clean(msg.toString()));
        return again ? 0 : 2;
    }

    /** What the host wrote on standard input, or "" when nothing arrives within two seconds (run by hand). */
    private static String stdin() {
        String[] text = {""};
        Thread reader = new Thread(() -> {
            try {
                text[0] = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                // no input
            }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return text[0];
    }

    /** The line of a commit whose tests were all there before it and kept their result. */
    private static String onlyOld(Witness.Result w) {
        boolean passing = w.count(Kind.EXISTING_PASSES_ON_BOTH) > 0, skipped = w.count(Kind.EXISTING_SKIPPED) > 0;
        if (passing && skipped) return "only tests that were already there, passing on both sides or skipped as before";
        if (skipped) return "only tests that were already there, skipped as before";
        return "only tests that were already there, passing on both sides";
    }

    private static void part(List<String> parts, long n, String what) {
        if (n > 0) parts.add(n + " " + what);
    }

    /** A kind's count, with "at least" when it is a least number. */
    private static void part(List<String> parts, Witness.Result w, Kind kind, String what) {
        long n = w.count(kind);
        if (n > 0) parts.add((w.atLeast(kind) ? "at least " : "") + n + " " + what);
    }

    /** "30d" -> "30 days ago"; anything else goes to git as it is ("2026-11-01", "2 weeks ago"). */
    private static String window(String since) {
        if (since == null || since.isBlank() || since.equals("true") || since.startsWith("-")) throw new UsageException("--since takes a time span or a date: 30d, 2w, 12h, 2026-11-01");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([hdwm])").matcher(since.strip());
        if (!m.matches()) return since.strip();
        String unit = switch (m.group(2)) {
            case "h" -> "hours";
            case "d" -> "days";
            case "w" -> "weeks";
            default -> "months";
        };
        return m.group(1) + " " + unit + " ago";
    }

    /**
     * Prints one kind of verdict as a count and its tests; true when there was at least one. The
     * count is of tests; where a verdict about a file proves only a least number, it says "at
     * least", and the file's own line, which always has its detail, says which tests it means.
     */
    private static boolean section(PrintStream out, Witness.Result w, Kind kind, java.util.function.LongFunction<String> title, boolean withDetail) {
        List<Verdict> all = w.verdicts().stream().filter(v -> v.kind() == kind).toList();
        if (all.isEmpty()) return false;
        out.println("  " + (w.atLeast(kind) ? "at least " : "") + title.apply(w.count(kind)));
        Map<String, List<Verdict>> byFile = new LinkedHashMap<>();
        for (Verdict v : all) byFile.computeIfAbsent(v.file(), k -> new ArrayList<>()).add(v);
        for (Map.Entry<String, List<Verdict>> e : byFile.entrySet()) {
            out.println("      " + e.getKey() + "  [" + e.getValue().get(0).runner() + "]");
            for (Verdict v : e.getValue()) {
                String name = v.name().isEmpty() ? "" : v.name();
                String detail = (withDetail || name.isEmpty()) && !v.detail().isBlank() ? (name.isEmpty() ? "" : ": ") + v.detail() : "";
                if (!name.isEmpty() || !detail.isEmpty()) out.println("        " + name + detail);
            }
        }
        return true;
    }

    private static List<String> notChecked(Witness.Result w) {
        List<String> out = new ArrayList<>();
        if (w != null) for (String f : w.unsupported()) out.add("the test file " + f + ": no runner for it in this version (Maven Surefire and Vitest are supported)");
        out.add("whether the tests execute the lines the change touches");
        out.add("whether tests that passed before the change still pass: run your full test suite");
        out.add("whether the change is correct or complete; only that its own tests would notice its absence");
        out.add("any test this change did not add or edit" + (w != null && w.verdicts().stream().anyMatch(v -> v.runner().startsWith("Maven")) ? ", and integration tests (Maven Failsafe)" : ""));
        return out;
    }

    private static int number(Map<String, String> opt, String name, int fallback) {
        String v = opt.get(name);
        if (v == null) return fallback;
        try {
            int n = Integer.parseInt(v);
            if (n < 1) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " takes a whole number above zero, not '" + v + "'");
        }
    }
}
