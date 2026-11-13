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
        if (opt.containsKey("since")) return since(opt, runs, timeout);
        Change change = Change.of(opt.get("base"));
        PrintStream out = System.out;
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
            section(out, w, Kind.SENSITIVE, n -> n + (n == 1 ? " fails" : " fail") + " before the change and " + (n == 1 ? "passes" : "pass") + " after it"
                    + (w.runs() > 1 ? ", in " + w.runs() + " of " + w.runs() + " runs" : ""), false);
            failed |= section(out, w, Kind.PASSES_ON_BOTH, n -> n + (n == 1 ? " new test passes" : " new tests pass") + " before and after, so "
                    + (n == 1 ? "it does" : "they do") + " not test the change", false);
            section(out, w, Kind.UNSTABLE, n -> n + " gave different results from run to run", true);
            if (section(out, w, Kind.UNKNOWN, n -> n + " could not be judged", true)) {
                out.println("      A test of code that did not exist before the change cannot fail there for the right reason, so it is not counted either way.");
            }
            section(out, w, Kind.SKIPPED, n -> n + (n == 1 ? " is" : " are") + " skipped", false);
            long old = w.count(Kind.EXISTING_PASSES_ON_BOTH);
            if (old > 0) out.println("  " + old + " test" + (old == 1 ? "" : "s") + " that " + (old == 1 ? "was" : "were") + " already in the edited files " + (old == 1 ? "passes" : "pass") + " before and after (not counted)");
            if (w.count(Kind.SENSITIVE) > 0) {
                out.println("  A test that fails before and passes after is sensitive to the change. That is not evidence that the change is correct.");
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
        PrintStream out = System.out;
        sdlc.util.Proc log = sdlc.util.Proc.run(sdlc.core.Ctx.root, "git", "log", "--first-parent", "--no-merges", "--since=" + window, "--format=%H%x09%cs%x09%s", "HEAD");
        if (!log.ok()) throw new UsageException("git log failed: " + log.err().strip());
        List<String> commits = log.out().isBlank() ? List.of() : List.of(log.out().strip().split("\\R"));
        out.println("sdlc check --since " + opt.get("since") + ": " + commits.size() + " commit" + (commits.size() == 1 ? "" : "s") + " on this branch since " + window
                + (commits.size() > max ? ", the newest " + max + " checked (--max N for more)" : ""));
        long sensitive = 0, both = 0, unknown = 0, failing = 0, untested = 0, problems = 0;
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
                part(parts, w.count(Kind.SENSITIVE), "fail before and pass after");
                part(parts, w.count(Kind.PASSES_ON_BOTH), "new, pass on both sides");
                part(parts, w.count(Kind.FAILS_AFTER), "fail after");
                part(parts, w.count(Kind.UNKNOWN) + w.count(Kind.UNSTABLE), "could not be judged");
                part(parts, w.count(Kind.SKIPPED), "skipped");
                out.println("    tests: " + (parts.isEmpty() ? "only tests that were already there, passing on both sides" : String.join("; ", parts)));
                for (Verdict v : w.verdicts()) if (v.kind() == Kind.PASSES_ON_BOTH) out.println("        does not test the change: " + v.file() + "  " + v.name());
                sensitive += w.count(Kind.SENSITIVE);
                both += w.count(Kind.PASSES_ON_BOTH);
                failing += w.count(Kind.FAILS_AFTER);
                unknown += w.count(Kind.UNKNOWN) + w.count(Kind.UNSTABLE);
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
            out.println("  " + both + (both == 1 ? " new test passes on both sides, so does not test its commit" : " new tests pass on both sides, so do not test their commit"));
            out.println("  " + unknown + " could not be judged; " + failing + " fail after their commit");
            out.println("  " + untested + " commit" + (untested == 1 ? "" : "s") + " add or edit no test this version can run");
            out.println("  " + problems + " integrity problem" + (problems == 1 ? "" : "s"));
        }
        return 0;
    }

    private static void part(List<String> parts, long n, String what) {
        if (n > 0) parts.add(n + " " + what);
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

    /** Prints one kind of verdict as a count and its tests; true when there was at least one. */
    private static boolean section(PrintStream out, Witness.Result w, Kind kind, java.util.function.LongFunction<String> title, boolean withDetail) {
        List<Verdict> all = w.verdicts().stream().filter(v -> v.kind() == kind).toList();
        if (all.isEmpty()) return false;
        out.println("  " + title.apply(all.size()));
        Map<String, List<Verdict>> byFile = new LinkedHashMap<>();
        for (Verdict v : all) byFile.computeIfAbsent(v.file(), k -> new ArrayList<>()).add(v);
        for (Map.Entry<String, List<Verdict>> e : byFile.entrySet()) {
            out.println("      " + e.getKey() + "  [" + e.getValue().get(0).runner() + "]");
            for (Verdict v : e.getValue()) {
                String name = v.name().isEmpty() ? "" : v.name();
                String detail = withDetail && !v.detail().isBlank() ? (name.isEmpty() ? "" : ": ") + v.detail() : "";
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
