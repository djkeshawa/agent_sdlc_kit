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
        Change change = Change.of(opt.get("base"));
        PrintStream out = System.out;
        String sha = change.base.length() > 7 ? change.base.substring(0, 7) : change.base;
        if (change.isEmpty()) {
            out.println("sdlc check: nothing has changed against " + change.baseName + " (" + sha + ").");
            return 0;
        }
        out.println("sdlc check: " + change.files.size() + " file" + (change.files.size() == 1 ? "" : "s") + " changed against " + change.baseName + " (" + sha + ")");
        Witness.Result w = opt.containsKey("no-run") ? null : Witness.run(change, opt.containsKey("selftest-runner") ? List.of(new SelfTestRunner()) : Runners.all(), runs, timeout);
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
