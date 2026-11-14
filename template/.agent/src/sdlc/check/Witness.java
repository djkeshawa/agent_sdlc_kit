package sdlc.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sdlc.check.JUnitXml.Status;
import sdlc.check.Runner.FileRun;

/**
 * The executed statement about a change's own tests. Each test file the change adds or edits is
 * run three ways:
 *
 *   after   the file as the change has it, on the code after the change
 *   before  the same file, on the code before the change (the base commit, with only the
 *           change's test files laid over it)
 *   old     the file as it was, on the code before the change (the base commit as it is); only
 *           for a file the change edits, as an added file has no earlier version
 *
 * A test that fails before and passes after is sensitive to the change; one that passes on both
 * sides does not test it. Which tests are new, which ran and are skipped now, and which are gone
 * is read from the reports of the old and the after run, not from the files' text: the test
 * framework knows which tests a file has.
 *
 * What this says is narrow. The tests and the code come from the same author, so a pass here is
 * not evidence that the change is correct, only that these tests would notice its absence.
 */
final class Witness {
    private Witness() {}

    enum Kind {
        /** Fails before by assertion, passes after, in every run. */
        SENSITIVE,
        /** A new test that passes on both sides: it does not test the change. */
        PASSES_ON_BOTH,
        /** A test that was already in the file and passes on both sides: unchanged in effect, not counted against the change. */
        EXISTING_PASSES_ON_BOTH,
        /** Fails after the change. */
        FAILS_AFTER,
        /** Nothing can be said: it could not run before the change, or threw something other than an assertion there. */
        UNKNOWN,
        /** Runs disagreed with each other. */
        UNSTABLE,
        /** Skipped after the change: a new test, or one whose state before the change is not known. */
        SKIPPED,
        /** Ran in the file as it was before the change, and is skipped after it: switched off. */
        SKIPPED_NOW,
        /** Skipped in the file as it was and after the change: unchanged, not counted. */
        EXISTING_SKIPPED,
        /** The edited file had this test before the change and does not report it now, and it was not renamed or moved. */
        REMOVED,
        /**
         * A note about a file (name ""): tests found again under a renamed group, tests whose name
         * alone the change rewrote (renamed), or tests another changed file now reports (moved).
         */
        RENAMED
    }

    /** name is "" when the verdict is about the whole file. */
    record Verdict(String file, String name, Kind kind, String detail, String runner) {}

    record Result(List<Verdict> verdicts, List<String> unsupported, int runs) {
        long count(Kind k) {
            return verdicts.stream().filter(v -> v.kind() == k).count();
        }
    }

    static Result run(Change change, List<Runner> runners, int runs, int timeoutSeconds) {
        try (Sides sides = new Sides(change, runners)) {
            return run(change, runners, runs, timeoutSeconds, sides);
        }
    }

    /** As above, in checkouts the caller made and will remove. */
    static Result run(Change change, List<Runner> runners, int runs, int timeoutSeconds, Sides sides) {
        List<Verdict> verdicts = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        Scratch head = sides.head();
        Map<Runner, List<String>> owned = new LinkedHashMap<>();
        for (String path : change.paths("AM")) {
            Runner owner = runners.stream().filter(r -> r.runs(head.dir, path)).findFirst().orElse(null);
            if (owner != null) owned.computeIfAbsent(owner, k -> new ArrayList<>()).add(path);
            else if (Integrity.testNamed(path)) unsupported.add(path);
        }
        if (owned.isEmpty()) return new Result(verdicts, unsupported, 0);
        Set<String> edited = new LinkedHashSet<>(change.paths("M"));
        for (Map.Entry<Runner, List<String>> e : owned.entrySet()) {
            verdicts.addAll(judge(change, e.getKey(), e.getValue(), edited, sides, Math.max(1, runs), timeoutSeconds));
        }
        return new Result(verdicts, unsupported, Math.max(1, runs));
    }

    private static String key(JUnitXml.Case c) {
        return c.suite() + "#" + c.name();
    }

    /**
     * What the run of a test file as it was before the change reported: its cases, or why it
     * reported nothing (problem). A file the change adds has no earlier version: NONE, and every
     * test of it is new.
     */
    private record Earlier(List<JUnitXml.Case> cases, String problem) {
        static final Earlier NONE = new Earlier(List.of(), null);
    }

    /** Runs the edited files as they were, on the code as it was: the third run. Added files need none. */
    private static Map<String, Earlier> earlier(Runner runner, List<String> files, Set<String> edited, Sides sides, int timeout) {
        Map<String, Earlier> out = new LinkedHashMap<>();
        List<String> had = files.stream().filter(edited::contains).toList();
        Map<String, FileRun> runs = had.isEmpty() ? Map.of() : runner.run(sides.old().dir, had, timeout);
        for (String f : files) {
            FileRun run = runs.get(f);
            if (!edited.contains(f)) out.put(f, Earlier.NONE);
            else if (run == null || run.problem() != null) out.put(f, new Earlier(List.of(), run == null ? "gave no result" : run.problem()));
            else out.put(f, new Earlier(run.cases(), null));
        }
        return out;
    }

    /**
     * How the tests of a file after the change line up with the tests of the file as it was.
     * was: for each test after the change (by identity), the test it was, when it was there.
     * regrouped: tests found again under a renamed group, as "old name is now new name".
     * renamed: tests whose name alone the change rewrote, the same way.
     * gone and fresh: what is left over on each side.
     */
    private record Lineup(Map<String, JUnitXml.Case> was, List<String> regrouped, List<String> renamed, List<JUnitXml.Case> gone, List<JUnitXml.Case> fresh) {}

    /**
     * A test of the same identity (suite and name) is the same test, one for one. Of the rest, a
     * test whose own title fits exactly one gone test and exactly one new test is that test under
     * a renamed group: renaming a describe block or a nested class renames every test in it, and
     * that alone makes no test new. Last, a gone test and a new test of one suite are one test
     * renamed when the file's text shows that nothing but the name changed (see swaps).
     */
    private static Lineup lineup(Earlier e, FileRun a, String oldText, String newText) {
        Map<String, JUnitXml.Case> was = new LinkedHashMap<>();
        List<JUnitXml.Case> left = new ArrayList<>(e.cases()), fresh = new ArrayList<>();
        for (JUnitXml.Case c : a.cases()) {
            JUnitXml.Case same = left.stream().filter(o -> key(o).equals(key(c))).findFirst().orElse(null);
            if (same == null) {
                fresh.add(c);
                continue;
            }
            left.remove(same);
            was.putIfAbsent(key(c), same);
        }
        List<String> regrouped = new ArrayList<>();
        for (JUnitXml.Case c : new ArrayList<>(fresh)) {
            List<JUnitXml.Case> went = left.stream().filter(o -> title(o).equals(title(c))).toList();
            if (went.size() != 1 || fresh.stream().filter(n -> title(n).equals(title(c))).count() != 1) continue;
            JUnitXml.Case o = went.get(0);
            left.remove(o);
            fresh.remove(c);
            was.putIfAbsent(key(c), o);
            regrouped.add(shown(o, c) + " is now " + shown(c, o));
        }
        List<String> renamed = new ArrayList<>();
        Set<Swap> swaps = left.isEmpty() || fresh.isEmpty() ? Set.of() : nameSwaps(left, fresh, oldText, newText);
        for (JUnitXml.Case g : new ArrayList<>(left)) {
            List<JUnitXml.Case> fits = fresh.stream().filter(n -> n.suite().equals(g.suite()) && swaps.contains(swap(g.name(), n.name()))).toList();
            if (fits.size() != 1) continue; // none, or more than one: which one cannot be told
            JUnitXml.Case n = fits.get(0);
            left.remove(g);
            fresh.remove(n);
            was.putIfAbsent(key(n), g);
            renamed.add(g.name() + " is now " + n.name());
        }
        return new Lineup(was, regrouped, renamed, left, fresh);
    }

    /** What a change put in place of what in a line: the two versions with their common start and common end cut off. */
    private record Swap(String from, String to) {}

    private static Swap swap(String a, String b) {
        int start = 0;
        while (start < a.length() && start < b.length() && a.charAt(start) == b.charAt(start)) start++;
        int endA = a.length(), endB = b.length();
        while (endA > start && endB > start && a.charAt(endA - 1) == b.charAt(endB - 1)) {
            endA--;
            endB--;
        }
        return new Swap(a.substring(start, endA), b.substring(start, endB));
    }

    /**
     * The swaps by which the change renamed tests and did nothing else to them, read from the
     * file's text line by line, without knowing the language. A place the change rewrote counts
     * when every line it took out comes back there, in order, as it was or with only the swap of a
     * gone test's name into a new test's name of the same suite; lines may be added around them
     * (a new test written next to the renamed one). A test whose title or method name alone was
     * rewritten leaves its swap here. A test deleted in one place and another added elsewhere does
     * not, nor does a title rewritten in the place where the test's body changed too: a line taken
     * out does not come back.
     */
    private static Set<Swap> nameSwaps(List<JUnitXml.Case> gone, List<JUnitXml.Case> fresh, String oldText, String newText) {
        Set<Swap> names = new LinkedHashSet<>();
        for (JUnitXml.Case g : gone) for (JUnitXml.Case n : fresh) if (g.suite().equals(n.suite())) names.add(swap(g.name(), n.name()));
        Set<Swap> out = new LinkedHashSet<>();
        String[] a = Lines.of(oldText), b = Lines.of(newText);
        for (Lines.Hunk h : Lines.changed(a, b)) {
            Set<Swap> here = new LinkedHashSet<>();
            int next = h.fromB();
            boolean kept = true;
            for (int i = h.fromA(); i < h.toA() && kept; i++) {
                int at = next;
                while (at < h.toB() && !a[i].equals(b[at]) && !names.contains(swap(a[i], b[at]))) at++;
                kept = at < h.toB();
                if (kept && !a[i].equals(b[at])) here.add(swap(a[i], b[at]));
                next = at + 1;
            }
            if (kept) out.addAll(here);
        }
        return out;
    }

    /** A test's own title, without the groups a report puts before it ("adds" of "refunds > partial > adds"). */
    private static String title(JUnitXml.Case c) {
        int at = c.name().lastIndexOf(" > ");
        return at < 0 ? c.name() : c.name().substring(at + 3);
    }

    /** A test's name, with its suite when that is what tells it from the other one (a nested class that was renamed). */
    private static String shown(JUnitXml.Case c, JUnitXml.Case other) {
        return c.name().equals(other.name()) ? c.suite() + "." + c.name() : c.name();
    }

    private static List<Verdict> judge(Change change, Runner runner, List<String> files, Set<String> edited, Sides sides, int runs, int timeout) {
        List<Verdict> out = new ArrayList<>();
        Scratch head = sides.head(), base = sides.base();
        Map<String, FileRun> after = runner.run(head.dir, files, timeout);
        Map<String, FileRun> before = new LinkedHashMap<>(runner.run(base.dir, files, timeout));
        Map<String, Earlier> earlier = earlier(runner, files, edited, sides, timeout);
        // One test of new code stops every test of its module from compiling before the change. Those files are
        // then run one at a time, each with the other changed tests put back as the base has them.
        Set<String> alone = new LinkedHashSet<>();
        for (String f : files) if (before.get(f) != null && before.get(f).broken()) alone.add(f);
        if (alone.size() < 2 || alone.size() > MAX_ALONE) alone.clear();
        for (String f : alone) before.put(f, runAlone(change, runner, files, f, base, timeout));
        // candidates: failed before by assertion, passed after. They are run again to rule out a test that fails at random.
        Map<String, Set<String>> candidates = new LinkedHashMap<>();
        Map<String, Lineup> lineups = new LinkedHashMap<>(); // files whose run after the change and run as they were both gave results
        for (String f : files) {
            FileRun a = after.get(f);
            if (a != null && a.problem() == null && earlier.get(f).problem() == null) lineups.put(f, lineup(earlier.get(f), a, change.before(f), change.after(f)));
        }
        for (String f : files) {
            String shared = runner.sharedBySides(change, head.dir, f);
            FileRun a = after.get(f), b = before.get(f);
            Earlier e = earlier.get(f);
            Lineup l = lineups.get(f);
            if (a == null || a.problem() != null) {
                // code that does not compile or load is the change's own failure; a missing build tool, a timeout or a name that cannot be passed on is not
                boolean broken = a != null && a.broken();
                out.add(new Verdict(f, "", broken ? Kind.FAILS_AFTER : Kind.UNKNOWN, (broken ? "after the change it " : "could not be run after the change: ") + (a == null ? "no result" : a.problem()), runner.name()));
                continue;
            }
            Map<String, JUnitXml.Case> was = new LinkedHashMap<>();
            Set<String> twice = new LinkedHashSet<>(); // two cases under one name cannot be told apart, on either side
            if (b != null) for (JUnitXml.Case c : b.cases()) if (was.put(key(c), c) != null) twice.add(key(c));
            Set<String> seen = new LinkedHashSet<>();
            for (JUnitXml.Case c : a.cases()) if (!seen.add(key(c))) twice.add(key(c));
            seen.clear();
            for (JUnitXml.Case c : a.cases()) {
                // what it was in the file as it was; new when it was not there (null: that cannot be told)
                JUnitXml.Case old = l == null ? null : l.was().get(key(c));
                Boolean isNew = l == null ? null : old == null;
                JUnitXml.Case p = was.get(key(c));
                if (twice.contains(key(c))) {
                    if (seen.add(key(c))) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "more than one test reports this name, so they cannot be told apart", runner.name()));
                } else if (c.status() == Status.FLAKY) out.add(new Verdict(f, c.name(), Kind.UNSTABLE, "the build ran it again and got another result", runner.name()));
                else if (c.status() == Status.SKIPPED) out.add(skipped(f, c, old, runner));
                else if (c.status() != Status.PASSED) out.add(new Verdict(f, c.name(), Kind.FAILS_AFTER, describe(c), runner.name()));
                else if (b == null || b.problem() != null) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "before the change the file " + (b == null ? "gave no result" : b.problem()), runner.name()));
                else if (p == null) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "was not found before the change", runner.name()));
                else if (p.status() == Status.PASSED && shared != null) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "passes before and after, but " + shared, runner.name()));
                else if (p.status() == Status.PASSED && isNew == null) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "passes before and after, but run as it was before the change, the file "
                        + e.problem() + ", so it cannot be told whether this test is new", runner.name()));
                else if (p.status() == Status.PASSED) out.add(new Verdict(f, c.name(), isNew ? Kind.PASSES_ON_BOTH : Kind.EXISTING_PASSES_ON_BOTH, "", runner.name()));
                else if (p.status() == Status.SKIPPED) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "was skipped before the change", runner.name()));
                else if (p.status() == Status.FLAKY) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "before the change the build ran it again and got another result", runner.name()));
                else if (p.status() == Status.ERRORED) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "before the change it threw " + shortType(p) + ", not an assertion", runner.name()));
                else candidates.computeIfAbsent(f, k -> new LinkedHashSet<>()).add(key(c));
            }
            if (e.problem() != null) out.add(new Verdict(f, "", Kind.UNKNOWN, "run as it was before the change, the file " + e.problem()
                    + ", so it cannot be told which of its tests are new, were switched off or were removed", runner.name()));
            // a file that ran and has no test, and had none: nothing to judge, and it is said so (a file that had tests has lost them: see gone)
            else if (a.cases().isEmpty() && e.cases().isEmpty()) out.add(new Verdict(f, "", Kind.UNKNOWN, "the file ran after the change and reports no tests", runner.name()));
        }
        out.addAll(gone(files, lineups, runner.name()));
        // A test that was run alone before the change may have failed there for want of another changed test file,
        // not for want of the change. It is run alone after the change too, and must pass there as well.
        for (String f : new ArrayList<>(candidates.keySet())) {
            if (!alone.contains(f)) continue;
            FileRun solo = runAlone(change, runner, files, f, head, timeout);
            for (String k : new ArrayList<>(candidates.get(f))) {
                if (status(solo, k) == Status.PASSED) continue;
                candidates.get(f).remove(k);
                out.add(new Verdict(f, k.substring(k.indexOf('#') + 1), Kind.UNKNOWN,
                        "after the change it passes only together with the other changed test files, so its failure before the change may come from them", runner.name()));
            }
            if (candidates.get(f).isEmpty()) candidates.remove(f);
        }
        if (!alone.isEmpty()) {
            restore(change, runner, head, files);
            restore(change, runner, base, files);
        }
        if (candidates.isEmpty()) return out;
        List<String> again = new ArrayList<>(candidates.keySet());
        Map<String, String> unstable = new LinkedHashMap<>(); // key -> why
        for (int i = 1; i < runs; i++) {
            Map<String, FileRun> a = runner.run(head.dir, again, timeout);
            List<String> together = again.stream().filter(f -> !alone.contains(f)).toList();
            Map<String, FileRun> b = new LinkedHashMap<>();
            if (!alone.isEmpty()) restore(change, runner, base, files);
            if (!together.isEmpty()) b.putAll(runner.run(base.dir, together, timeout));
            for (String f : again) if (alone.contains(f)) b.put(f, runAlone(change, runner, files, f, base, timeout));
            for (String f : again) {
                for (String k : candidates.get(f)) {
                    Status sa = status(a.get(f), k), sb = status(b.get(f), k);
                    if (sa != Status.PASSED) unstable.putIfAbsent(f + "\n" + k, "passed after the change in one run and not in another");
                    else if (sb != Status.FAILED) unstable.putIfAbsent(f + "\n" + k, "failed before the change in one run and not in another");
                }
            }
        }
        for (String f : again) {
            for (String k : candidates.get(f)) {
                String name = k.substring(k.indexOf('#') + 1);
                String why = unstable.get(f + "\n" + k);
                if (why != null) out.add(new Verdict(f, name, Kind.UNSTABLE, why, runner.name()));
                else out.add(new Verdict(f, name, Kind.SENSITIVE, "", runner.name()));
            }
        }
        if (!alone.isEmpty()) restore(change, runner, base, files); // as the caller's other checks expect it
        return out;
    }

    /** What a runner says when a file cannot be compiled; the Maven runner uses these words. */
    static final String DOES_NOT_COMPILE = "does not compile";
    /** Above this many files, running each alone costs more than it tells. */
    private static final int MAX_ALONE = 8;

    /** Runs one changed test file in a checkout (of either side) with every other changed test file as the base has it (or absent, when it is new). */
    private static FileRun runAlone(Change change, Runner runner, List<String> files, String file, Scratch base, int timeout) {
        try {
            for (String other : files) {
                if (other.equals(file)) continue;
                if (change.before(other) == null) java.nio.file.Files.deleteIfExists(base.dir.resolve(other));
                else base.write(change.base, List.of(other));
            }
            base.write(change.head, List.of(file));
            runner.forgetBuilt(base.dir, files); // a test file that was just taken away must not stay behind as a compiled class
            FileRun run = runner.run(base.dir, List.of(file), timeout).get(file);
            return run == null ? FileRun.failed("gave no result") : run;
        } catch (java.io.IOException e) {
            return FileRun.failed("could not be set up alone (" + e.getMessage() + ")");
        }
    }

    /** Puts every changed test file back into a checkout as the change has it. */
    private static void restore(Change change, Runner runner, Scratch base, List<String> files) {
        try {
            base.write(change.head, files);
            runner.forgetBuilt(base.dir, files);
        } catch (java.io.IOException e) {
            throw new sdlc.core.UsageException("cannot prepare the checkout for a repeat run: " + e.getMessage());
        }
    }

    /** A test skipped after the change, by what the file as it was said of it (old: null when it is new, or that is not known). */
    private static Verdict skipped(String file, JUnitXml.Case c, JUnitXml.Case old, Runner runner) {
        if (old == null) return new Verdict(file, c.name(), Kind.SKIPPED, "skipped after the change", runner.name());
        if (old.status() == Status.SKIPPED) return new Verdict(file, c.name(), Kind.EXISTING_SKIPPED, "skipped before the change too", runner.name());
        return new Verdict(file, c.name(), Kind.SKIPPED_NOW, "ran before the change and is skipped now", runner.name());
    }

    /**
     * The tests an edited file had before the change and does not report now, after the renamed
     * groups and the renamed tests (see lineup) are taken out. A gone test whose title another
     * changed file now reports as new has moved there. Every other one was removed, however many
     * new tests came: deleting a test and writing another is not a rename.
     */
    private static List<Verdict> gone(List<String> files, Map<String, Lineup> lineups, String runner) {
        List<Verdict> out = new ArrayList<>();
        Map<String, List<String>> arrived = new LinkedHashMap<>(); // per file: the titles of its new tests, each of which can account for one move
        for (Map.Entry<String, Lineup> l : lineups.entrySet()) arrived.put(l.getKey(), new ArrayList<>(l.getValue().fresh().stream().map(Witness::title).toList()));
        for (String f : files) {
            Lineup l = lineups.get(f);
            if (l == null) continue;
            int n = l.regrouped().size();
            if (n > 0) out.add(new Verdict(f, "", Kind.RENAMED, n + (n == 1 ? " test is" : " tests are") + " reported under another group, with "
                    + (n == 1 ? "its own title unchanged, and counts as the same test" : "their own titles unchanged, and count as the same tests") + ": " + shortList(l.regrouped()), runner));
            int r = l.renamed().size();
            if (r > 0) out.add(new Verdict(f, "", Kind.RENAMED, r + (r == 1 ? " test was" : " tests were") + " renamed (only the name changed in the file), and "
                    + (r == 1 ? "counts as the same test" : "count as the same tests") + ": " + shortList(l.renamed()), runner));
            List<String> moved = new ArrayList<>();
            List<JUnitXml.Case> lost = new ArrayList<>();
            for (JUnitXml.Case c : l.gone()) {
                String to = files.stream().filter(o -> !o.equals(f) && arrived.containsKey(o) && arrived.get(o).remove(title(c))).findFirst().orElse(null);
                if (to != null) moved.add(c.name() + " (now in " + to + ")");
                else lost.add(c);
            }
            if (!moved.isEmpty()) out.add(new Verdict(f, "", Kind.RENAMED, moved.size() + (moved.size() == 1 ? " test moved" : " tests moved") + " to another changed file: " + shortList(moved), runner));
            for (JUnitXml.Case c : lost) out.add(new Verdict(f, c.name(), Kind.REMOVED, "", runner));
        }
        return out;
    }

    /** Up to ten names, and how many more. */
    private static String shortList(List<String> names) {
        if (names.size() <= 10) return String.join(", ", names);
        return String.join(", ", names.subList(0, 10)) + " and " + (names.size() - 10) + " more";
    }

    private static Status status(FileRun run, String key) {
        if (run == null || run.problem() != null) return null;
        List<Status> found = run.cases().stream().filter(c -> key(c).equals(key)).map(JUnitXml.Case::status).toList();
        return found.size() == 1 ? found.get(0) : null; // none, or two under one name: not a result
    }

    private static String shortType(JUnitXml.Case c) {
        String t = c.type().isBlank() ? "an error" : c.type();
        return t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t;
    }

    private static String describe(JUnitXml.Case c) {
        String m = c.message().replaceAll("\\s+", " ").strip();
        if (m.length() > 140) m = m.substring(0, 140) + "...";
        return (c.status() == Status.FAILED ? "fails: " : "throws " + shortType(c) + ": ") + m;
    }
}
