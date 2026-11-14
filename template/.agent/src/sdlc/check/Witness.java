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
 * and, only for an edited file whose list of tests changed, a fourth way:
 *
 *   old tests  the file as it was, on the code after the change (see fourth)
 *
 * A test that fails before and passes after is sensitive to the change; one that passes on both
 * sides does not test it. Which tests are new, which ran and are skipped now, and which are gone
 * is read from the reports of the old and the after run, not from the files' text: the test
 * framework knows which tests a file has. A case is known by its exact name (suite and name) and
 * by nothing else, and a name says only that the report gave it: a parameterized test's runs may
 * be named by their place, by their arguments or by display names, and a name of one shape may
 * be any of these. So where the names cannot tell, the counts of each file decide (see names),
 * and what the counts cannot prove is said to be not known, never passed in silence.
 *
 * What this says is narrow. The tests and the code come from the same author, so a pass here is
 * not evidence that the change is correct, only that these tests would notice its absence.
 */
final class Witness {
    private Witness() {}

    enum Kind {
        /** Fails before by assertion, passes after, in every run. */
        SENSITIVE,
        /**
         * A new test that passes on both sides: it does not test the change. Of a file the change
         * adds, said of each such test by name; of an edited file, said of the file (name ""): at
         * least so many new tests pass on both sides, whatever their names, where the report of
         * the file as it was lists every test, and in every run (see newTests).
         */
        PASSES_ON_BOTH,
        /** A test whose name the file reported before the change, that passes on both sides: not counted against the change. */
        EXISTING_PASSES_ON_BOTH,
        /** Fails after the change. */
        FAILS_AFTER,
        /**
         * An old test that passed before the change, fails or throws when its file as it was runs on
         * the code after the change, in every run, and the change took out of the way: the file
         * does not report its name now or skips it, and no case after the change can be it
         * rewritten (see broken). Said of a file (name ""): at least so many of the old tests it
         * names.
         */
        OLD_FAILS_AFTER,
        /**
         * Nothing can be said: it could not run before the change, or threw something other than an
         * assertion there, or the reports cannot tell two answers apart, or a report may not list
         * every test (see listing), or the fourth run did not judge an old test (see untold).
         */
        UNKNOWN,
        /** Runs disagreed with each other. */
        UNSTABLE,
        /** Skipped after the change: a new test, or one whose state before the change is not known. */
        SKIPPED,
        /**
         * Said of a file (name ""): fewer of its tests run than in the file as it was, and more are
         * skipped, so at least so many tests that ran before do not run now: switched off, or
         * removed while skipped tests were added (see skips).
         */
        SKIPPED_NOW,
        /** Skipped under its name in the file as it was and after the change: not counted. */
        EXISTING_SKIPPED,
        /**
         * Said of a file (name ""): it reports fewer tests than it had before the change, where both
         * its reports list every test, so at least so many were removed (see gone).
         */
        REMOVED
    }

    /**
     * name is "" when the verdict is about the whole file. tests is how many tests it counts: one
     * for a test, and for some verdicts about a file the least number the reports prove (atLeast).
     * newCode: could not be judged because before the change the test did not compile, was not
     * there, or threw: what a test of code the change adds does there.
     */
    record Verdict(String file, String name, Kind kind, String detail, String runner, int tests, boolean newCode) {
        Verdict(String file, String name, Kind kind, String detail, String runner) {
            this(file, name, kind, detail, runner, 1, false);
        }

        Verdict(String file, String name, Kind kind, String detail, String runner, int tests) {
            this(file, name, kind, detail, runner, tests, false);
        }

        /** Could not be judged: before the change the test did not compile, was not there, or threw. */
        static Verdict beforeFailed(String file, String name, String detail, String runner) {
            return new Verdict(file, name, Kind.UNKNOWN, detail, runner, 1, true);
        }

        /** True when it counts at least so many of the tests it names, and which of them the reports do not say. */
        boolean atLeast() {
            return name.isEmpty() && (kind == Kind.PASSES_ON_BOTH || kind == Kind.REMOVED || kind == Kind.SKIPPED_NOW || kind == Kind.OLD_FAILS_AFTER);
        }
    }

    record Result(List<Verdict> verdicts, List<String> unsupported, int runs) {
        /** The tests of a kind: for a verdict about a file, the least number it proves. */
        long count(Kind k) {
            return verdicts.stream().filter(v -> v.kind() == k).mapToLong(Verdict::tests).sum();
        }

        /** True when a count of this kind is a least number, not an exact one. */
        boolean atLeast(Kind k) {
            return verdicts.stream().anyMatch(v -> v.kind() == k && v.atLeast());
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

    /** The ways a changed test file is run (see the class comment): after, before, old, and old tests (the fourth way). */
    private enum Side { AFTER, BEFORE, OLD, OLD_TESTS }

    /**
     * Every report of the changed test files that the check sees, per way and per file, in the
     * order made: the first run's first. A run that gave no result is kept too (null, or with its
     * problem). What must hold in every run (a sensitive test fails before and passes after; a
     * skip says the same; an old test broken passed as it was, failed on the change, and is gone
     * or runs) is decided from all the reports of a way kept here, after the last run, whichever
     * repeat made them: a run made is a run used, and a repeat added later cannot be left out of a
     * decision. A file run alone (see runAlone) is run so before the change in every run, and those
     * runs are its reports before the change; its one run alone after the change only rules out a
     * failure before that comes from another changed test file, and is not kept with the reports
     * after the change, which are of the files run together.
     */
    private static final class Seen {
        private final Map<Side, Map<String, List<FileRun>>> runs = new java.util.EnumMap<>(Side.class);

        /** Keeps the reports of a run of these files (null for a file the run gave nothing for). */
        void saw(Side side, java.util.Collection<String> files, Map<String, FileRun> got) {
            Map<String, List<FileRun>> of = runs.computeIfAbsent(side, s -> new LinkedHashMap<>());
            for (String f : files) of.computeIfAbsent(f, x -> new ArrayList<>()).add(got.get(f));
        }

        /** Every report of a file run one way, the first run's first. */
        List<FileRun> of(Side side, String file) {
            return runs.getOrDefault(side, Map.of()).getOrDefault(file, List.of());
        }
    }

    /** Runs the edited files as they were, on the code as it was: the third run. Added files need none. */
    private static Map<String, Earlier> earlier(Runner runner, List<String> files, Set<String> edited, Sides sides, int timeout, Seen seen) {
        Map<String, Earlier> out = new LinkedHashMap<>();
        List<String> had = files.stream().filter(edited::contains).toList();
        Map<String, FileRun> runs = had.isEmpty() ? Map.of() : runner.run(sides.old().dir, had, timeout);
        seen.saw(Side.OLD, had, runs);
        for (String f : files) {
            FileRun run = runs.get(f);
            if (!edited.contains(f)) out.put(f, Earlier.NONE);
            else if (run == null || run.problem() != null) out.put(f, new Earlier(List.of(), run == null ? "gave no result" : run.problem()));
            else out.put(f, new Earlier(run.cases(), null));
        }
        return out;
    }

    private static List<Verdict> judge(Change change, Runner runner, List<String> files, Set<String> edited, Sides sides, int runs, int timeout) {
        List<Verdict> out = new ArrayList<>();
        String by = runner.name();
        Scratch head = sides.head(), base = sides.base();
        Map<String, FileRun> after = runner.run(head.dir, files, timeout);
        Map<String, FileRun> before = new LinkedHashMap<>(runner.run(base.dir, files, timeout));
        Seen seen = new Seen(); // every report made from here on (see Seen)
        Map<String, Earlier> earlier = earlier(runner, files, edited, sides, timeout, seen);
        // One test of new code stops every test of its module from compiling before the change. Those files are
        // then run one at a time, each with the other changed tests put back as the base has them.
        Set<String> alone = new LinkedHashSet<>();
        for (String f : files) if (before.get(f) != null && before.get(f).broken()) alone.add(f);
        if (alone.size() < 2 || alone.size() > MAX_ALONE) alone.clear();
        for (String f : alone) before.put(f, runAlone(change, runner, files, f, base, timeout));
        seen.saw(Side.AFTER, files, after);
        seen.saw(Side.BEFORE, files, before);
        // candidates: failed before by assertion, passed after. They are run again to rule out a test that fails at random.
        Map<String, Set<String>> candidates = new LinkedHashMap<>();
        Map<String, Names> named = new LinkedHashMap<>(); // edited files whose run after the change and run as they were both gave results, lined up by name
        Map<String, List<Verdict>> skips = new LinkedHashMap<>(); // of those, the files whose skips may say a test is switched off: the first answer, said after the repeat runs
        Map<String, Map<String, String>> shownIn = new LinkedHashMap<>(); // per file: how its cases are shown (see shown)
        Map<String, Set<String>> passingIn = new LinkedHashMap<>(); // per file: the cases that pass on both sides
        Map<String, List<Verdict>> newIn = new LinkedHashMap<>(); // per edited file: what its counts say of its new tests (see newTests), said after the repeat runs
        for (String f : files) {
            String shared = runner.sharedBySides(change, head.dir, f);
            FileRun a = after.get(f), b = before.get(f);
            Earlier e = earlier.get(f);
            if (a == null || a.problem() != null) {
                // code that does not compile or load is the change's own failure; a missing build tool, a timeout or a name that cannot be passed on is not
                boolean broken = a != null && a.broken();
                out.add(new Verdict(f, "", broken ? Kind.FAILS_AFTER : Kind.UNKNOWN, (broken ? "after the change it " : "could not be run after the change: ") + (a == null ? "no result" : a.problem()), by));
                continue;
            }
            // an added file has no earlier version: every test of it is new. An edited one is lined up with the file as it was, when that gave a result.
            Names n = edited.contains(f) && e.problem() == null ? names(e.cases(), a.cases()) : null;
            Map<String, String> sh = n != null ? n.shown() : shown(e.cases(), a.cases());
            shownIn.put(f, sh);
            Set<String> fresh = new LinkedHashSet<>();
            if (n != null) for (JUnitXml.Case c : n.fresh()) fresh.add(key(c));
            Map<String, JUnitXml.Case> was = new LinkedHashMap<>();
            Set<String> twice = new LinkedHashSet<>(); // two cases under one name, before or after the change: the sides cannot be compared by that name
            if (b != null) for (JUnitXml.Case c : b.cases()) if (was.put(key(c), c) != null) twice.add(key(c));
            Set<String> once = new LinkedHashSet<>();
            for (JUnitXml.Case c : a.cases()) if (!once.add(key(c))) twice.add(key(c));
            once.clear();
            List<JUnitXml.Case> passBoth = new ArrayList<>(); // cases under a name the file did not report before that pass on both sides (see newTests)
            long clean = 0; // cases that pass on both sides, under any name
            Set<String> passing = new LinkedHashSet<>(); // the names of those
            passingIn.put(f, passing);
            for (JUnitXml.Case c : a.cases()) {
                JUnitXml.Case p = was.get(key(c));
                // First what the run after the change says alone: two cases of one name on the side before the change do not matter to these.
                if (c.status() == Status.FLAKY) out.add(new Verdict(f, shown(sh, key(c)), Kind.UNSTABLE, "the build ran it again and got another result", by));
                else if (c.status() == Status.SKIPPED) continue; // see skips, below
                else if (c.status() != Status.PASSED) out.add(new Verdict(f, shown(sh, key(c)), Kind.FAILS_AFTER, describe(c), by));
                // what is left compares the two sides by name, which must then name one test on each side. The same file runs on
                // both sides, so its cases are named alike on both.
                else if (twice.contains(key(c))) {
                    if (once.add(key(c))) out.add(new Verdict(f, shown(sh, key(c)), Kind.UNKNOWN, "more than one test reports this name, so they cannot be told apart", by));
                }
                else if (b == null || b.problem() != null) out.add(b != null && b.broken() ? Verdict.beforeFailed(f, shown(sh, key(c)), "before the change the file " + b.problem(), by)
                        : new Verdict(f, shown(sh, key(c)), Kind.UNKNOWN, "before the change the file " + (b == null ? "gave no result" : b.problem()), by));
                else if (p == null) out.add(Verdict.beforeFailed(f, shown(sh, key(c)), "was not found before the change", by));
                else if (p.status() == Status.PASSED && shared != null) out.add(new Verdict(f, shown(sh, key(c)), Kind.UNKNOWN, "passes before and after, but " + shared, by));
                else if (p.status() == Status.PASSED && e.problem() != null) out.add(new Verdict(f, shown(sh, key(c)), Kind.UNKNOWN, "passes before and after, but run as it was before the change, the file "
                        + e.problem() + ", so it cannot be told whether this test is new", by));
                else if (p.status() == Status.PASSED) {
                    clean++;
                    passing.add(key(c));
                    if (n == null) out.add(new Verdict(f, shown(sh, key(c)), Kind.PASSES_ON_BOTH, "", by));
                    else if (fresh.contains(key(c))) passBoth.add(c);
                    else out.add(new Verdict(f, shown(sh, key(c)), Kind.EXISTING_PASSES_ON_BOTH, "", by));
                }
                else if (p.status() == Status.SKIPPED) out.add(new Verdict(f, shown(sh, key(c)), Kind.UNKNOWN, "was skipped before the change", by));
                else if (p.status() == Status.FLAKY) out.add(new Verdict(f, shown(sh, key(c)), Kind.UNKNOWN, "before the change the build ran it again and got another result", by));
                else if (p.status() == Status.ERRORED) out.add(Verdict.beforeFailed(f, shown(sh, key(c)), "before the change it threw " + shortType(p) + ", not an assertion", by));
                else candidates.computeIfAbsent(f, k -> new LinkedHashSet<>()).add(key(c));
            }
            if (n == null) {
                // a file the change adds, or one whose earlier version gave no result: its skipped tests are listed
                for (JUnitXml.Case c : a.cases()) if (c.status() == Status.SKIPPED) out.add(new Verdict(f, shown(sh, key(c)), Kind.SKIPPED, "skipped after the change", by));
            } else {
                named.put(f, n);
                Skips s = skips(f, n, by);
                out.addAll(s.listed());
                if (!s.answer().isEmpty()) skips.put(f, s.answer());
                // the cases that do not pass on both sides; of the skipped ones, those under a name skipped before too only where a skip may hide a test (see newTests)
                long asBefore = n.asBefore().stream().mapToLong(Flip::tests).sum();
                newIn.put(f, newTests(f, n, passBoth, a.cases().size() - clean - (s.answer().isEmpty() ? asBefore : 0), by));
            }
            if (e.problem() != null) out.add(new Verdict(f, "", Kind.UNKNOWN, "run as it was before the change, the file " + e.problem()
                    + ", so it cannot be told which of its tests are new, were switched off or were removed", by));
            // a file that ran and has no test, and had none: nothing to judge, and it is said so (a file that had tests has lost them: see gone)
            else if (a.cases().isEmpty() && e.cases().isEmpty()) out.add(new Verdict(f, "", Kind.UNKNOWN, "the file ran after the change and reports no tests", by));
        }
        // A test that was run alone before the change may have failed there for want of another changed test file,
        // not for want of the change. It is run alone after the change too, and must pass there as well.
        for (String f : new ArrayList<>(candidates.keySet())) {
            if (!alone.contains(f)) continue;
            FileRun solo = runAlone(change, runner, files, f, head, timeout);
            for (String k : new ArrayList<>(candidates.get(f))) {
                if (status(solo, k) == Status.PASSED) continue;
                candidates.get(f).remove(k);
                out.add(new Verdict(f, shown(shownIn.get(f), k), Kind.UNKNOWN,
                        "after the change it passes only together with the other changed test files, so its failure before the change may come from them", by));
            }
            if (candidates.get(f).isEmpty()) candidates.remove(f);
        }
        if (!alone.isEmpty()) {
            restore(change, runner, head, files);
            restore(change, runner, base, files);
        }
        List<String> sensitive = new ArrayList<>(candidates.keySet());
        // files whose skips may say a test is switched off, or whose counts say tests were removed or new tests pass on both sides: their old and
        // after runs are made again, as what the counts say must be said by every run
        List<String> switched = new ArrayList<>(skips.keySet());
        for (Map.Entry<String, Names> e : named.entrySet()) {
            boolean counts = e.getValue().gone().size() > e.getValue().fresh().size() || newIn.get(e.getKey()).stream().anyMatch(v -> v.kind() == Kind.PASSES_ON_BOTH);
            if (counts && !switched.contains(e.getKey())) switched.add(e.getKey());
        }
        Set<String> again = new LinkedHashSet<>(sensitive);
        again.addAll(switched);
        for (int i = 1; i < runs && !again.isEmpty(); i++) {
            seen.saw(Side.AFTER, again, runner.run(head.dir, new ArrayList<>(again), timeout));
            if (!sensitive.isEmpty()) {
                List<String> together = sensitive.stream().filter(f -> !alone.contains(f)).toList();
                Map<String, FileRun> b = new LinkedHashMap<>();
                if (!alone.isEmpty()) restore(change, runner, base, files);
                if (!together.isEmpty()) b.putAll(runner.run(base.dir, together, timeout));
                for (String f : sensitive) if (alone.contains(f)) b.put(f, runAlone(change, runner, files, f, base, timeout));
                seen.saw(Side.BEFORE, sensitive, b);
            }
            if (!switched.isEmpty()) seen.saw(Side.OLD, switched, runner.run(sides.old().dir, switched, timeout));
        }
        // the fourth run, for each edited file whose list of tests changed: every old test of such a file that passed as it was
        Map<String, Set<String>> wanted = new LinkedHashMap<>();
        for (Map.Entry<String, Names> e : named.entrySet()) {
            if (!e.getValue().changed()) continue;
            Set<String> keys = new LinkedHashSet<>();
            for (JUnitXml.Case c : earlier.get(e.getKey()).cases()) if (c.status() == Status.PASSED) keys.add(key(c));
            if (!keys.isEmpty()) wanted.put(e.getKey(), keys);
        }
        Fourth fourth = fourth(change, runner, wanted, sides, runs, timeout, seen);
        // Every run is made. What must hold in every run is decided from all the reports seen, whichever repeat made them.
        Map<String, String> unstable = new LinkedHashMap<>(); // key -> why
        for (String f : sensitive) {
            for (String k : candidates.get(f)) {
                if (seen.of(Side.AFTER, f).stream().anyMatch(r -> status(r, k) != Status.PASSED)) unstable.put(f + "\n" + k, "passed after the change in one run and not in another");
                else if (seen.of(Side.BEFORE, f).stream().anyMatch(r -> status(r, k) != Status.FAILED)) unstable.put(f + "\n" + k, "failed before the change in one run and not in another");
            }
        }
        Set<String> unsteady = new LinkedHashSet<>(), recounted = new LinkedHashSet<>(); // files whose skips, and whose counts of gone and fresh cases, said another thing in a repeat run
        for (String f : switched) {
            // the whole decision is made again from the complete reports of each run, on both sides: each report as it was against each after the change
            List<String> first = signature(skips.getOrDefault(f, List.of()), named.get(f));
            for (FileRun o : seen.of(Side.OLD, f)) {
                for (FileRun a : seen.of(Side.AFTER, f)) {
                    List<String> now = said(o, a, f, by);
                    if (now == null || !first.subList(0, first.size() - 1).equals(now.subList(0, now.size() - 1))) unsteady.add(f);
                    if (now == null || !first.get(first.size() - 1).equals(now.get(now.size() - 1))) recounted.add(f);
                }
            }
        }
        Set<String> judged = new LinkedHashSet<>(); // per file and identity: old tests said to be broken and taken out of the way, or unstable in that
        Map<String, Long> said = new LinkedHashMap<>(); // per file: how many old tests are said to be taken out of the way (see budget)
        for (Map.Entry<String, Names> e : named.entrySet()) {
            String f = e.getKey();
            out.addAll(broken(f, e.getValue(), seen.of(Side.AFTER, f), passingIn.get(f), fourth, said, judged, by));
        }
        Set<String> counted = new LinkedHashSet<>(); // files of which the counts say, below, that fewer tests run: said in every run, and not all of it said above
        for (Map.Entry<String, List<Verdict>> s : skips.entrySet()) {
            String f = s.getKey();
            if (!unsteady.contains(f) && s.getValue().stream().anyMatch(v -> v.kind() == Kind.SKIPPED_NOW && v.tests() > said.getOrDefault(f, 0L))) counted.add(f);
        }
        for (Map.Entry<String, List<Verdict>> e : newIn.entrySet()) {
            String f = e.getKey();
            for (Verdict v : e.getValue()) {
                // new tests that pass on both sides, by the counts of the file: the counts must be the same in every run
                if (v.kind() == Kind.PASSES_ON_BOTH && recounted.contains(f)) out.add(new Verdict(f, "", Kind.UNSTABLE, v.detail().substring(0, v.detail().indexOf(". Under names"))
                        + ", but what the file reports, after the change or as it was, is not the same in every run", by, v.tests()));
                else out.add(v);
            }
        }
        out.addAll(gone(named, counted, said, judged, recounted, by));
        // a file whose skips said nothing in the first run and something in another, where nothing above says that its reports differ from run to run
        for (String f : unsteady) {
            if (skips.containsKey(f) || out.stream().anyMatch(v -> v.file().equals(f) && v.name().isEmpty() && v.kind() == Kind.UNSTABLE)) continue;
            out.add(new Verdict(f, "", Kind.UNSTABLE, "whether tests that ran before the change are skipped now differs from run to run: what the file reports, after the change or as it "
                    + "was, is not the same in every run", by));
        }
        for (Map.Entry<String, List<Verdict>> s : skips.entrySet()) {
            String f = s.getKey();
            for (Verdict v : s.getValue()) {
                if (unsteady.contains(f)) out.add(new Verdict(f, "", Kind.UNSTABLE,
                        "whether tests that ran before the change are skipped now differs from run to run: what the file reports, after the change or as it was, is not the same in every run", by, v.tests()));
                else if (v.kind() == Kind.UNKNOWN) out.add(v);
                else {
                    // what the old tests said to be taken out of the way did not already say (see budget)
                    long left = said.getOrDefault(f, 0L), least = v.tests() - left;
                    if (least > 0) out.add(left == 0 ? v : new Verdict(f, "", Kind.SKIPPED_NOW, fewerRun(named.get(f), v.tests(), left), by, (int) least));
                }
            }
        }
        // the fourth run was asked for these files, and did not judge every old test: said of each such file, whatever else is said of it
        for (Map.Entry<String, Names> e : named.entrySet()) if (fourth.unclear().containsKey(e.getKey())) out.add(fourth.untold(e.getKey(), e.getValue().shown(), by));
        for (String f : sensitive) {
            for (String k : candidates.get(f)) {
                String name = shown(shownIn.get(f), k);
                String why = unstable.get(f + "\n" + k);
                if (why != null) out.add(new Verdict(f, name, Kind.UNSTABLE, why, by));
                else out.add(new Verdict(f, name, Kind.SENSITIVE, "", by));
            }
        }
        if (!alone.isEmpty() && runs > 1 && !sensitive.isEmpty()) restore(change, runner, base, files); // as the caller's other checks expect it
        return out;
    }

    /**
     * What the fourth run said. first: each file's first run, null when it gave no result. broke:
     * per file, the old tests that passed in every run of the file as it was and failed or threw
     * here in every run, of all the runs seen (see Seen); wavering: those that failed or threw
     * here in the first run and did not do both in every run. unclear: per file, the old tests of
     * which the first run gave no clean result, passed or failed (see untold).
     */
    private record Fourth(Map<String, FileRun> first, Map<String, Set<String>> broke, Map<String, Set<String>> wavering, Map<String, List<String>> unclear) {
        static final Fourth NONE = new Fourth(Map.of(), Map.of(), Map.of(), Map.of());

        /** "passed before the change; as the test was, on the code after the change x fails: why", of those keys that broke. */
        String said(String file, java.util.Collection<String> keys, Map<String, String> shown) {
            List<String> which = keys.stream().filter(broke.getOrDefault(file, Set.of())::contains).toList();
            FileRun run = first.get(file);
            JUnitXml.Case one = run.cases().stream().filter(c -> key(c).equals(which.get(0))).findFirst().orElseThrow();
            String names = which.size() == 1 ? shown(shown, which.get(0)) + " " + describe(one) : shortList(which.stream().map(k -> shown(shown, k)).toList()) + " fail or throw";
            return "passed before the change; as the test was, on the code after the change " + names;
        }

        /**
         * The verdict of a file of which the fourth run did not judge every old test it was asked
         * about: could not be judged, a verdict of its own, as no other verdict need be there to
         * carry it. A file whose list grew while a test the change broke was taken out, [a, b] to
         * [b, d, c] named by place, has nothing gone, skipped or short; when its old version does
         * not compile or load on the code after the change, this is all that says that the old
         * tests could not be judged.
         */
        Verdict untold(String file, Map<String, String> shown, String by) {
            List<String> keys = unclear.get(file);
            FileRun run = first.get(file);
            String problem = run == null ? "gave no result" : run.problem();
            int n = keys.size();
            String which = n == 1 ? "the old test that passed before it (" + shown(shown, keys.get(0)) + ")" : "any of the " + n + " old tests that passed before it ("
                    + shortList(keys.stream().map(k -> shown(shown, k)).toList()) + ")";
            String head = problem != null ? "the old tests could not be run on the code after the change: there the file as it was " + problem
                    : "the old tests could not all be judged on the code after the change: there the file as it was gave no clean result, passed or failed, for "
                            + (n == 1 ? "one" : n) + " of them (not reported, reported more than once, skipped, or run again by the build with another result)";
            return new Verdict(file, "", Kind.UNKNOWN, head + ", so it is not known whether the change broke " + which + " and took it out of the way or replaced it", by, n);
        }
    }

    /** For a test whose old runs passed and failed on the code after the change in some runs only. */
    private static final String WAVERED = "passed before the change, and as the test was, failed on the code after the change in one run and not in another";

    /**
     * The fourth run: the edited test files whose list of tests changed (wanted: per file, the
     * identities of the old tests that passed in the third run), as they were before the change,
     * on the code after it. An old test that passed in the
     * third run (its file as it was, on the code as it was) and fails or throws here failed on what
     * the change wrote; what became of it decides what is said (see broken). Like a sensitive test
     * it must do so in every run, on both sides, of all the runs of the file as it was the check
     * made (see Seen): else it wavered. With each repeat run the file is also run as the change
     * has it, as what became of the old test must hold in every run too.
     *
     * The checkout is the tree after the change with only these test files put back as the base
     * has them (Sides.oldTests, one checkout for each runner): production code, build files,
     * fixtures, helpers and the other changed test files stay as the change has them. The files of
     * one runner that this run needs go back together, as they ran together in the third run. So a test renamed and not otherwise edited
     * runs here exactly as its new name runs after the change, and cannot be accused for the
     * rename. A test whose fixture the change rewrote or deleted may fail here because of that
     * and not because of the production code; that is still a test that passed before, fails on
     * what the change wrote, and the report says no more than that. A test
     * file that does not compile or load here (the change renamed what it used), or gives no
     * result, tells nothing of its old tests; nor does a run that does not report an old test
     * once, as passed or as failed (it is not there, is there twice, is skipped, or the build ran
     * it again and got another result). Such an old test is not judged, and its file gets a
     * verdict of its own, could not be judged, with why (untold): the run was asked for, so
     * what it did not say is said, and no other verdict of the file need be there to carry it.
     *
     * Which files: each edited file whose list of tests changed, in any way (Names.changed): a
     * name it did not report, a name it no longer reports, or a name with more or fewer of its
     * cases skipped. Not only where a test is gone or may be switched off: a file can lose a
     * broken test while it grows, [a, b] to [b, d, c] named by place with a broken and c new
     * and sensitive, and then nothing is gone, nothing is skipped and no count is short; here the
     * old a fails under a name that still runs, which could not be judged, and is said.
     *
     * Not every edited file. Where a file reports the very same list as before (the same names,
     * as many cases under each, as many of them skipped), no name of an old test that passed is
     * gone or skipped now, so no old test can be said to be taken out of the way (B = 0, see
     * broken): all this run could add is "an old test whose name still runs fails as it was on
     * the change, so it may have been edited or replaced", could not be judged. That is exactly
     * what it would say of every test whose expectation is changed under its own name, the
     * ordinary way to change what a test expects: the line would stand on every such change,
     * tell the two apart in none, and cost one more build of the module each time. So a test
     * replaced by another under its name, in a file whose list of tests stayed the same (a broken
     * row taken out and its name given to a new one; rows that change places; a test rewritten
     * to expect something else), is not seen by the runs. The reports are the same as for a test
     * edited in place; the guide says so among the limits.
     */
    private static Fourth fourth(Change change, Runner runner, Map<String, Set<String>> wanted, Sides sides, int runs, int timeout, Seen seen) {
        if (wanted.isEmpty()) return Fourth.NONE;
        List<String> files = new ArrayList<>(wanted.keySet());
        Map<String, FileRun> first = new LinkedHashMap<>();
        Scratch mixed = sides.oldTests(runner);
        try {
            mixed.write(change.base, files);
            runner.forgetBuilt(mixed.dir, files);
        } catch (java.io.IOException e) {
            Map<String, List<String>> unclear = new LinkedHashMap<>();
            for (String f : files) {
                first.put(f, FileRun.failed("could not be set up (" + e.getMessage() + ")"));
                unclear.put(f, new ArrayList<>(wanted.get(f)));
            }
            return new Fourth(first, Map.of(), Map.of(), unclear);
        }
        Map<String, FileRun> ran = runner.run(mixed.dir, files, timeout);
        seen.saw(Side.OLD_TESTS, files, ran);
        for (String f : files) first.put(f, ran.get(f));
        Map<String, Set<String>> failing = new LinkedHashMap<>(), broke = new LinkedHashMap<>(), wavering = new LinkedHashMap<>();
        Map<String, List<String>> unclear = new LinkedHashMap<>();
        for (String f : files) {
            // each old test asked about passes here, fails or throws here, or is told of (untold): no result, not reported, reported twice, skipped, run again
            for (String k : wanted.get(f)) {
                Status s = status(first.get(f), k);
                if (failed(s)) failing.computeIfAbsent(f, x -> new LinkedHashSet<>()).add(k);
                else if (s != Status.PASSED) unclear.computeIfAbsent(f, x -> new ArrayList<>()).add(k);
            }
        }
        List<String> again = new ArrayList<>(failing.keySet());
        for (int i = 1; i < runs && !again.isEmpty(); i++) {
            seen.saw(Side.OLD, again, runner.run(sides.old().dir, again, timeout));
            seen.saw(Side.OLD_TESTS, again, runner.run(mixed.dir, again, timeout));
            seen.saw(Side.AFTER, again, runner.run(sides.head().dir, again, timeout));
        }
        // broken: passed in every run of the file as it was and failed or threw in every run of it on the change, of all the runs seen
        for (String f : again) {
            for (String k : failing.get(f)) {
                boolean every = seen.of(Side.OLD, f).stream().allMatch(r -> status(r, k) == Status.PASSED) && seen.of(Side.OLD_TESTS, f).stream().allMatch(r -> failed(status(r, k)));
                (every ? broke : wavering).computeIfAbsent(f, x -> new LinkedHashSet<>()).add(k);
            }
        }
        return new Fourth(first, broke, wavering, unclear);
    }

    private static boolean failed(Status s) {
        return s == Status.FAILED || s == Status.ERRORED;
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

    /**
     * An edited file's run as it was (old) and its run after the change, lined up by the cases'
     * exact names (suite and name) and by nothing else: a report does not hold what a case is.
     * Surefire names the runs of a parameterized test by their place, or by their arguments or
     * display names; JUnit 4 may write a value where the place would be; a dynamic test is named
     * by its place in its factory. So a name says only that the report gave it, and a case under
     * one name on both sides may be another test on each side. What the names cannot tell, the
     * counts below decide: they are true however cases are named.
     *
     * Of each name, the cases reported under it in the old run and after the change: when both
     * sides have as many, they are matched, and the ones that ran on one side and are skipped on
     * the other are flips (off: ran before, skipped now; on: the other way). A name with another
     * number of cases on each side matches nothing: its old cases are gone, its cases now are
     * fresh. "Ran" is any result but skipped: a case that failed or threw ran too.
     *
     * gone, fresh: the cases left over on each side. off: per name, how many ran before and are
     * skipped now. on: how many were skipped before and run now. asBefore: per name, how many are
     * skipped on both sides. r0, s0, r1, s1: how many ran and were skipped in the old run (0)
     * and after the change (1), over the whole file. shown: how each case is shown (see shown).
     * listing: why the reports may not list every test of the file (see listing).
     */
    private record Names(List<JUnitXml.Case> gone, List<JUnitXml.Case> fresh, List<Flip> off, long on, List<Flip> asBefore, long r0, long s0, long r1, long s1, Map<String, String> shown,
            Listing listing) {
        List<JUnitXml.Case> freshSkipped() {
            return fresh.stream().filter(c -> c.status() == Status.SKIPPED).toList();
        }

        /** True when the file's list of tests changed: a name gone or fresh, or a name with more or fewer of its cases skipped (see fourth). */
        boolean changed() {
            return !gone.isEmpty() || !fresh.isEmpty() || !off.isEmpty() || on > 0;
        }
    }

    /** Cases of one name (key: with its suite) that the reports say one thing of, and how many. */
    private record Flip(String key, String name, long tests) {}

    private static Names names(List<JUnitXml.Case> old, List<JUnitXml.Case> now) {
        Map<String, String> shown = shown(old, now);
        Map<String, List<JUnitXml.Case>> was = byKey(old), is = byKey(now);
        Set<String> keys = new LinkedHashSet<>(was.keySet());
        keys.addAll(is.keySet());
        List<JUnitXml.Case> gone = new ArrayList<>(), fresh = new ArrayList<>();
        List<Flip> off = new ArrayList<>(), asBefore = new ArrayList<>();
        long on = 0;
        for (String k : keys) {
            List<JUnitXml.Case> o = was.getOrDefault(k, List.of()), n = is.getOrDefault(k, List.of());
            if (o.size() != n.size()) {
                gone.addAll(o);
                fresh.addAll(n);
                continue;
            }
            long ranO = ran(o), ranN = ran(n), both = Math.min(o.size() - ranO, n.size() - ranN);
            String name = shown.get(k);
            if (ranO > ranN) off.add(new Flip(k, name, ranO - ranN));
            else on += ranN - ranO;
            if (both > 0) asBefore.add(new Flip(k, name, both));
        }
        return new Names(gone, fresh, off, on, asBefore, ran(old), old.size() - ran(old), ran(now), now.size() - ran(now), shown, listing(was, is, shown));
    }

    private static Map<String, List<JUnitXml.Case>> byKey(List<JUnitXml.Case> cases) {
        Map<String, List<JUnitXml.Case>> out = new LinkedHashMap<>();
        for (JUnitXml.Case c : cases) out.computeIfAbsent(key(c), k -> new ArrayList<>()).add(c);
        return out;
    }

    /** How many of these cases ran: any result but skipped. */
    private static long ran(List<JUnitXml.Case> cases) {
        return cases.stream().filter(c -> c.status() != Status.SKIPPED).count();
    }

    /**
     * What an edited file's skipped cases say. answer: what may say that a test that ran before is
     * not run now, to be run again before it is said; empty when nothing can be. listed: the rest.
     */
    private record Skips(List<Verdict> answer, List<Verdict> listed) {}

    /**
     * The skips of an edited file, from its old run and its run after the change (n):
     *
     *   1. No name ran before and is skipped now (no off flip), and no case is skipped under a name
     *      the file did not report before, or none of its tests ran before: nothing can have been
     *      switched off. A case skipped on both sides under one name is skipped as before; a case
     *      skipped under a new name, in a file none of whose tests ran, is a skipped test (listed).
     *   2. Fewer cases run and more are skipped (r1 < r0, s1 > s0), whatever the names: at least
     *      min(r0 - r1, s1 - s0) tests that ran do not run now; could not be judged where the
     *      report as it was holds a failure, as a case that failed or threw need not be a test that
     *      ran (see listing). Sound under any naming and whatever a skipped case or an empty
     *      report hides: the counts are of the whole file; r0 counts tests that ran (each case that
     *      passed is one), r1 is never fewer than the tests that run now (a hidden test does not
     *      run), so at least r0 - r1 tests that ran do not run now, and the least said is never
     *      more. Of them, as many as there are more skipped cases may be among those (switched
     *      off), and the rest are not reported at all, which the gone tests (see gone) say where
     *      they can. The two are never said of one file together: tests removed are said only
     *      where each name is skipped as many times on both sides (see listing), so s1 = s0.
     *   3. Anything else in which a test that ran may be skipped now: one could not be judged for
     *      the file, with the counts and the names, and the two stories they fit.
     *
     * Rule 2 is said of the file, with the names that ran before and are skipped now as the
     * reports give them, never of a name alone. Even where the file reports the very same names
     * as before and the only change is off flips, a name does not say which test was switched
     * off: where tests are named by their place, two rows that change places while the one moved
     * to the front is switched off, [a, b] to [b skipped, a], give one off flip, at the first
     * name, while a still runs under the second. And a row taken out while a skipped one is put
     * in at the end gives an off flip too, at the last name: a test removed, not switched off.
     * The count holds in both.
     */
    private static Skips skips(String f, Names n, String by) {
        List<Verdict> answer = new ArrayList<>(), listed = new ArrayList<>();
        for (Flip s : n.asBefore()) listed.add(new Verdict(f, s.name(), Kind.EXISTING_SKIPPED, "skipped before the change too", by, (int) s.tests()));
        List<JUnitXml.Case> freshOff = n.freshSkipped();
        if (n.off().isEmpty() && (freshOff.isEmpty() || n.r0() == 0)) {
            for (JUnitXml.Case c : freshOff) listed.add(new Verdict(f, shown(n.shown(), key(c)), Kind.SKIPPED, "skipped after the change; no test of the file ran before it", by));
            return new Skips(answer, listed);
        }
        String counts = counts(n);
        if (n.r1() < n.r0() && n.s1() > n.s0()) {
            long least = Math.min(n.r0() - n.r1(), n.s1() - n.s0());
            // a case that failed or threw as it was, under any name, need not be a test that ran (see listing)
            if (n.listing().failedOld() != null) answer.add(new Verdict(f, "", Kind.UNKNOWN, counts + skippedNow(n) + ". But " + n.listing().failedOld()
                    + ": whether a test that ran does not run now is not known", by, (int) least));
            else answer.add(new Verdict(f, "", Kind.SKIPPED_NOW, fewerRun(n, least, 0), by, (int) least));
            return new Skips(answer, listed);
        }
        String story;
        if (n.s1() > n.s0()) story = ". So more are skipped while as many or more run: it cannot be told whether skipped tests were added, or a test that ran was switched off and another added";
        else if (!n.off().isEmpty()) story = ", and its list of cases changed as well (" + n.fresh().size() + " names it did not report, " + n.gone().size() + " it no longer reports, "
                + n.on() + " skipped before and run now). It cannot be told whether a test that ran was switched off, or cases changed names, as cases named by their place "
                + "do when a row is put in or taken out before them";
        else story = ". The file had tests that ran, so it cannot be told whether " + (freshOff.size() == 1 ? "this is a new test that is skipped, or a test" : "these are new tests that are skipped, or tests")
                + " that ran before under another name and " + (freshOff.size() == 1 ? "is" : "are") + " switched off";
        long candidates = n.off().stream().mapToLong(Flip::tests).sum() + freshOff.size();
        answer.add(new Verdict(f, "", Kind.UNKNOWN, counts + skippedNow(n) + story, by, (int) candidates));
        return new Skips(answer, listed);
    }

    /** "the file runs 3 of its tests where it ran 4 before the change, and skips 2 where it skipped 1". */
    private static String counts(Names n) {
        return "the file runs " + n.r1() + " of its tests where it ran " + n.r0() + " before the change, and skips " + n.s1() + " where it skipped " + n.s0();
    }

    /**
     * What rule 2 of the skips says of a file: at least so many tests that ran do not run now. Of
     * them, besides were already said to be old tests taken out of the way (see budget): the
     * file is then said to have at least least - besides more.
     */
    private static String fewerRun(Names n, long least, long besides) {
        String head = counts(n) + skippedNow(n) + ": at least " + least + (least == 1 ? " test that ran does" : " tests that ran do") + " not run now";
        if (besides == 0) return head + ", switched off, or removed while skipped tests were added";
        long more = least - besides;
        return head + ". At most " + besides + (besides == 1 ? " of them is an old test" : " of them are old tests") + " said above to be taken out of the way, so at least "
                + more + (more == 1 ? " more test that ran does" : " more tests that ran do") + " not run now, switched off, or removed while skipped tests were added";
    }

    /** " (skipped now under a name that ran before: a, b; under a name the file did not report before: c)". */
    private static String skippedNow(Names n) {
        List<String> parts = new ArrayList<>();
        if (!n.off().isEmpty()) parts.add("skipped now under " + (n.off().size() == 1 ? "a name" : "names") + " that ran before: " + shortList(n.off().stream().map(Flip::name).toList()));
        List<JUnitXml.Case> freshOff = n.freshSkipped();
        if (!freshOff.isEmpty()) parts.add((parts.isEmpty() ? "skipped " : "") + "under " + (freshOff.size() == 1 ? "a name" : "names") + " the file did not report before: " + shortList(shown(n.shown(), freshOff)));
        return parts.isEmpty() ? "" : " (" + String.join("; ", parts) + ")";
    }

    /** What another run says of a file, to compare with the first (see signature). Null when a run gave no result. */
    private static List<String> said(FileRun old, FileRun after, String f, String by) {
        if (old == null || old.problem() != null || after == null || after.problem() != null) return null;
        Names n = names(old.cases(), after.cases());
        return signature(skips(f, n, by).answer(), n);
    }

    /**
     * What a repeat run must say again: each verdict of the skips (kind, count and words), each
     * name that ran before and is skipped now and each case skipped under a name the file did not
     * report, by its identity (suite and name: two nested classes may each have a test of one
     * name), and last, how many cases are gone and fresh (see gone). So a run in which another
     * test is skipped is another answer, though the counts be the same.
     */
    private static List<String> signature(List<Verdict> answer, Names n) {
        List<String> out = new ArrayList<>(answer.stream().map(v -> v.kind() + "\n" + v.tests() + "\n" + v.detail()).toList());
        for (Flip s : n.off()) out.add("off\n" + s.key() + "\n" + s.tests());
        for (JUnitXml.Case c : n.freshSkipped()) out.add("skipped under a new name\n" + key(c));
        out.add(n.gone().size() + " gone, " + n.fresh().size() + " fresh");
        return out;
    }

    /**
     * How each case of a file is shown, by its identity (suite#name): by its name, and with its
     * suite where cases of two suites of the file have that name, in the file as it was or after
     * the change (nested classes with a method of one name). The identity is what is compared and
     * kept; this is only what is printed.
     */
    private static Map<String, String> shown(List<JUnitXml.Case> old, List<JUnitXml.Case> now) {
        List<JUnitXml.Case> all = new ArrayList<>(old);
        all.addAll(now);
        Map<String, Set<String>> suites = new LinkedHashMap<>();
        for (JUnitXml.Case c : all) suites.computeIfAbsent(c.name(), k -> new LinkedHashSet<>()).add(c.suite());
        Map<String, String> out = new LinkedHashMap<>();
        for (JUnitXml.Case c : all) out.putIfAbsent(key(c), suites.get(c.name()).size() > 1 ? c.suite() + "." + c.name() : c.name());
        return out;
    }

    /** How a case is shown, by its identity (see shown). */
    private static String shown(Map<String, String> shown, String key) {
        return shown.getOrDefault(key, name(key));
    }

    /** How these cases are shown (see shown). */
    private static List<String> shown(Map<String, String> shown, List<JUnitXml.Case> cases) {
        return cases.stream().map(c -> shown(shown, key(c))).toList();
    }

    /**
     * The new tests of an edited file that pass on both sides. A name the file did not report
     * before does not prove a new test: where cases are named by their place, a row put in among
     * others moves every later one to the next place, and the last one to a name the file did
     * not have. Counting does:
     *
     *   the file reports F names it did not report before and no longer reports G, so it has at
     *   least F - G tests it did not have, whatever the names (each name it reports on both sides
     *   has as many cases on each side, so the rest of the cases are counted by F and G alone).
     *   Of all its cases after the change, X do not pass on both sides (they fail before, fail
     *   after, could not be judged, or are skipped where a skip may hide a test). A new test is
     *   one of the cases after the change, so at least F - G - X new tests pass on both sides.
     *
     * Sound under any naming: neither count looks at what a name says. One assumption is made,
     * the one the skips make where they say nothing (see skips, 1): a case skipped under a name
     * that was skipped before too, in a file whose skips say nothing, is the test that was
     * skipped there. Such cases do not count in X. Without it, a skipped test put in where cases
     * are named by their place, with the skipped test it pushed on switched on, gives the same
     * reports as a test that passes on both sides added beside a test skipped all along; a test
     * skipped all along would then keep every file that has one from ever being said to have a
     * new test that does not test the change. Everywhere else skipped cases count in X: where
     * the skips may hide a test, and for a case skipped under a name the file did not report.
     *
     * P: the cases under names the file did not have that pass on both sides. The least number,
     * N = F - G - X, is never more than P (each of the F not counted in P is one of the X). N > 0:
     * the file has at least N new tests that do not test the change, said of the file, with the P
     * named as what the reports show, never as the new tests: where tests are named by their
     * place, a row put in before the others, [a, b] to [c, a, b], is reported under the first
     * name, which the file had, and the last name, which it did not have, stands for b. So even
     * when N = P the names are not said to be the new tests. N = 0: each of the P could not be
     * judged. Where the report as it was may not list every test (see listing: a failure, no
     * test at all, or a case skipped there that is not skipped as often after the change), a test
     * it hid may come out under a new name, and F - G does not count tests the file did not have:
     * N is taken to be 0, and the reason is said. The report after the change need not list every
     * test: a test it hides does not pass on both sides, a case of it that stands for more tests
     * than one, or for none, is one of the X, and old tests that one case after the change stands
     * for take fewer cases after the change, not more.
     */
    private static List<Verdict> newTests(String f, Names n, List<JUnitXml.Case> passBoth, long other, String by) {
        List<Verdict> out = new ArrayList<>();
        long p = passBoth.size(), g = n.gone().size(), fr = n.fresh().size(), least = n.listing().old() != null ? 0 : Math.max(0, fr - g - other);
        if (p == 0) return out;
        String counts = "the file reports " + tests(fr) + " it did not have" + (g > 0 ? " and no longer reports " + g + " it had" : "")
                + (other > 0 ? ", and " + other + " of its tests " + (other == 1 ? "does" : "do") + " not pass on both sides" : "");
        if (least > 0) {
            out.add(new Verdict(f, "", Kind.PASSES_ON_BOTH, counts + ": at least " + least + (least == 1 ? " new test passes" : " new tests pass") + " before and after. Under names the file "
                    + "did not have, " + p + (p == 1 ? " passes" : " pass") + " before and after (" + shortList(shown(n.shown(), passBoth)) + "); the names do not say which "
                    + (least == 1 ? "test is" : "tests are") + " new, as where tests are named by their place a row put in before the others moves an old one to a name the file did not have",
                    by, (int) least));
            return out;
        }
        List<String> why = new ArrayList<>();
        if (g > 0) why.add(g == 1 ? "the test it no longer reports, under a new name" : "one of the " + g + " tests it no longer reports, under a new name");
        if (other > 0) why.add("a test moved to this name by one put in before it, where cases are named by their place (the new "
                + (fr - g == 1 ? "test" : "tests") + " then being among the " + other + " that do not pass on both sides)");
        if (n.listing().old() != null) why.add("a test the file had that its report as it was did not list: " + n.listing().old());
        List<String> names = shown(n.shown(), passBoth);
        for (int i = 0; i < passBoth.size(); i++) out.add(new Verdict(f, names.get(i), Kind.UNKNOWN, "passes before and after, but it cannot be told whether it is a new test: " + counts
                + ". It may be a new test that does not test the change, or a test the file had: " + String.join(", or ", why), by));
        return out;
    }

    /**
     * What the reports say of the cases an edited file reported before the change and does not
     * report now. A report says which names a file has, not whether a gone test was deleted or
     * renamed, so a test is not called removed for being gone; only counts prove a removal. With
     * G gone and F fresh (see names):
     *
     *   G > F       the file lost tests: at least G - F were removed; could not be judged
     *               where a report may not list every test (see listing): a test that seems
     *               gone may be inside a case skipped after the change or behind a failure, and
     *               a skipped case gone may have stood for none
     *   G <= F      it could not be judged: a rename looks exactly like a test deleted and
     *               another written. Unless the counts of the whole file say that fewer tests
     *               run (see skips): that is said there, once.
     *
     * Sound under any naming: the file reports G - F fewer cases than it did (each name it reports
     * on both sides has as many cases on each), so at least G - F cases it had are not there. A
     * test that is skipped is still reported, so these were taken out. It is said of the file,
     * with the names as the reports give them, and never of a gone name alone, even with F = 0:
     * where tests are named by their place, the first row taken out of [a, b] leaves the name of
     * the last one gone, and b under the first name. A file whose cases come from a source that
     * gives another number each time would say so too: such a file is run again (both ways, with
     * the repeat runs of the skips), and where the counts differ it is unstable instead.
     *
     * Before all of these, the fourth run (see fourth): a gone test that passed before the change
     * and fails as it was on the code after it was broken by the change and taken out of the way,
     * whatever the counts say. A gone test of which the runs disagree on that is unstable. What
     * those said is not said again (see budget).
     */
    private static List<Verdict> gone(Map<String, Names> named, Set<String> counted, Map<String, Long> said, Set<String> judged, Set<String> recounted, String by) {
        List<Verdict> out = new ArrayList<>();
        for (Map.Entry<String, Names> e : named.entrySet()) {
            String f = e.getKey();
            List<JUnitXml.Case> lost = e.getValue().gone(), fresh = e.getValue().fresh();
            int g = lost.size(), nf = fresh.size();
            if (g == 0) continue;
            if (g > nf && recounted.contains(f)) {
                out.add(new Verdict(f, "", Kind.UNSTABLE, had(e.getValue()) + ", but what the file reports, after the change or as it was, is not the same in every run", by, g - nf));
            } else if (g > nf && e.getValue().listing().either() != null) {
                out.add(new Verdict(f, "", Kind.UNKNOWN, had(e.getValue()) + ". But " + e.getValue().listing().either() + ": whether tests were removed is not known", by, g - nf));
            } else if (g > nf) {
                long t = said.getOrDefault(f, 0L), least = g - nf - t;
                if (least <= 0) continue;
                String which = ". The names do not say which: where tests are named by their place, one taken out leaves its name to the next";
                out.add(new Verdict(f, "", Kind.REMOVED, had(e.getValue()) + ": at least " + (g - nf) + (g - nf == 1 ? " test was" : " tests were") + " removed"
                        + (t == 0 ? "" : ", of which at most " + t + (t == 1 ? " is an old test" : " are old tests") + " said above to be taken out of the way, so at least "
                                + least + " more") + which, by, (int) least));
            } else if (!counted.contains(f) && !lost.stream().allMatch(c -> judged.contains(f + "\n" + key(c)))) {
                out.add(new Verdict(f, "", Kind.UNKNOWN, had(e.getValue()) + ". The reports cannot tell a test renamed from a test deleted and another written", by));
            }
        }
        return out;
    }

    /**
     * What the fourth run says of an edited file (see fourth). An old test that passed in the
     * file as it was and fails or throws in the fourth run, in every run, failed on what the
     * change wrote. Whether the change took it out of the way is a question of what became of it,
     * which the reports do not say; the counts say what they can:
     *
     *   Its name is not reported after the change, or is skipped (B of them): it may have been
     *   taken out (deleted, switched off, or rewritten to pass on both sides, which is the same
     *   in effect), or rewritten for the changed behaviour under another name. Such a rewrite
     *   passes after the change and does not pass on both sides: it fails before, or could not
     *   be judged. The file has M such cases. When B > M, at least B - M of these old tests were
     *   taken out of the way; with M = 0, each of them, by name. Sound under any naming: each
     *   old test rewritten is one case after the change, a different one for each, and only M
     *   cases can be one, whatever their names (an old name is no proof that a case is the old
     *   test, nor a new name that it is not). B <= M: could not be judged, said with both
     *   stories, as a test whose expectations change on purpose is rewritten exactly so.
     *   Its name still runs after the change: it may have been edited for the changed behaviour
     *   (ordinary work, and never accused), or taken out and its name given to another case, as
     *   a row taken out does to the rows after it where cases are named by their place. Could
     *   not be judged. Where the file runs fewer tests than before, the counts already fail it
     *   (see skips and gone), so nothing is lost by not accusing this name of what they prove.
     *
     * Where a run after the change has a case that failed or threw, its report may not list every
     * test (see listing), so it shows neither that an old name is gone nor how many cases can be
     * a rewrite: could not be judged, with the reason. The failure is said as failing anyway.
     *
     * All of this must hold in every run (runs: every report of the file as the change has it that
     * the check made, the first one first: the first run, the repeats of the sensitive tests and of
     * the skips, and those of the fourth run; see Seen): an old test said gone or skipped is gone or
     * skipped in each of them, M is the most cases that can be a rewrite in any one of them, and
     * none holds a failure. An old test that is gone or skipped in one run and runs in another, or
     * of which a run gave no result, is unstable: whether the change took it out of the way is not
     * the same in every run.
     *
     * An old test of which the runs of the file as it was disagree, passing before and failing on
     * the change in some runs only, is unstable, whatever became of its name: gone, skipped or
     * still running (else a test whose name still runs would be said nowhere). How many old tests are said
     * to be taken out of the way goes into said, so that the counts of tests removed and switched
     * off are not said of them again (see budget); the names said broken, and the unstable ones,
     * into judged.
     *
     * The budget. The old tests taken out of the way (T, at least t of them said), the tests
     * removed (at least G - F, see gone) and the tests that ran and do not run now (at least
     * min(r0 - r1, s1 - s0), see skips) may be the same tests: one broken test deleted is two of
     * them. So the count of the other one said of the file is said only beyond t: removed at
     * least max(0, G - F - t), or switched off at least max(0, min(r0 - r1, s1 - s0) - t). Never
     * both: tests removed are said only where each name is skipped as many times on both sides
     * (see listing), and then s1 = s0 and no switch-off is counted. All said then comes to max(t,
     * what the counts prove), never more than the tests that are taken out, removed or no longer
     * run.
     */
    private static List<Verdict> broken(String f, Names n, List<FileRun> runs, Set<String> passing, Fourth fourth, Map<String, Long> said, Set<String> judged, String by) {
        List<Verdict> out = new ArrayList<>();
        FileRun a = runs.get(0); // the first run after the change, which gave a result (see named)
        Map<String, List<JUnitXml.Case>> now = byKey(a.cases());
        List<String> out1 = new ArrayList<>(), running = new ArrayList<>(), unsure = new ArrayList<>(); // the old tests broken: not run now, run now, and not the same in every run
        for (String k : fourth.broke.getOrDefault(f, Set.of())) {
            boolean gone = notRun(a, k);
            if (runs.stream().anyMatch(r -> r == null || r.problem() != null || notRun(r, k) != gone)) unsure.add(k);
            else if (gone) out1.add(k);
            else running.add(k);
        }
        List<JUnitXml.Case> rewrites = List.of();
        for (FileRun r : runs) {
            // the most cases that can be a rewrite in any one run
            List<JUnitXml.Case> can = r == null || r.problem() != null ? List.of()
                    : r.cases().stream().filter(c -> (c.status() == Status.PASSED || c.status() == Status.FLAKY) && !passing.contains(key(c))).toList();
            if (can.size() > rewrites.size()) rewrites = can;
        }
        for (String k : unsure) {
            out.add(new Verdict(f, shown(n.shown(), k), Kind.UNSTABLE, fourth.said(f, List.of(k), n.shown()) + ", and the change skips it, or the file does not report it, in one run "
                    + "after the change and runs it in another", by));
            judged.add(f + "\n" + k);
        }
        for (String k : fourth.wavering.getOrDefault(f, Set.of())) {
            // whatever became of it: a run that did not say what the others said is said, gone, skipped or running
            out.add(new Verdict(f, shown(n.shown(), k), Kind.UNSTABLE, WAVERED, by));
            judged.add(f + "\n" + k);
        }
        long b = out1.size(), m = rewrites.size();
        // a report after the change that may not list every test cannot show that no case can be a rewrite (see listing)
        String unlisted = runs.stream().anyMatch(r -> r != null && r.problem() == null && failing(r.cases())) ? "after the change the file reports failures" + FAILURES + ", so that report may not list every test" : null;
        if (b > 0 && unlisted != null) {
            out.add(new Verdict(f, "", Kind.UNKNOWN, shortList(out1.stream().map(k -> shown(n.shown(), k)).toList()) + " passed before the change and " + (b == 1 ? "fails" : "fail")
                    + " on the code after it as the file was, and the file does not report " + (b == 1 ? "it now or skips it" : "them now or skips them") + ". But " + unlisted
                    + ": whether the change took " + (b == 1 ? "it" : "them") + " out of the way is not known", by, (int) b));
        } else if (b > 0 && m == 0) {
            for (String k : out1) {
                out.add(new Verdict(f, shown(n.shown(), k), Kind.OLD_FAILS_AFTER, fourth.said(f, List.of(k), n.shown()) + (now.containsKey(k) ? ", and the change skips it now" : ". The edited file does not report it now"), by));
                judged.add(f + "\n" + k);
            }
            said.put(f, b);
        } else if (b > 0) {
            String what = shortList(out1.stream().map(k -> shown(n.shown(), k)).toList()) + (b == 1 ? " passed" : " passed") + " before the change and " + (b == 1 ? "fails" : "fail")
                    + " on the code after it as the file was, and the file does not report " + (b == 1 ? "it now or skips it" : "them now or skips them") + ". It has "
                    + m + (m == 1 ? " test that passes" : " tests that pass") + " after the change and " + (m == 1 ? "does" : "do") + " not pass on both sides ("
                    + shortList(shown(n.shown(), rewrites)) + ")";
            if (b > m) {
                out.add(new Verdict(f, "", Kind.OLD_FAILS_AFTER, what + ", and at most " + m + " of the " + b + " old tests can be among them, rewritten for the changed "
                        + "behaviour: at least " + (b - m) + (b - m == 1 ? " was" : " were") + " taken out of the way", by, (int) (b - m)));
                said.put(f, b - m);
            } else out.add(new Verdict(f, "", Kind.UNKNOWN, what + ", so it cannot be told whether the old " + (b == 1 ? "test was" : "tests were") + " rewritten for the changed "
                    + "behaviour under other names, or taken out of the way and other tests written", by, (int) b));
        }
        if (!running.isEmpty()) out.add(new Verdict(f, "", Kind.UNKNOWN, shortList(running.stream().map(k -> shown(n.shown(), k)).toList()) + (running.size() == 1 ? " passed" : " passed")
                + " before the change and " + (running.size() == 1 ? "fails" : "fail") + " on the code after it as the file was. A test of " + (running.size() == 1 ? "that name" : "each name")
                + " still runs after the change, so it cannot be told whether " + (running.size() == 1 ? "it was" : "each was") + " edited for the changed behaviour, or taken out and its "
                + "name given to another case, as a row taken out does to the rows after it where cases are named by their place"
                + (n.r1() < n.r0() ? ". The file runs fewer tests than before (" + n.r1() + " where it ran " + n.r0() + ")" : ""), by, running.size()));
        return out;
    }

    /** Why a case that failed or threw need not be a test. */
    private static final String FAILURES = " (a failure outside a test's body, in its set-up, tear-down or loading, may be reported in place of the file's tests or as a case of its own)";
    /** Why an empty report need not be a file without tests. */
    private static final String NONE = " (a class that stops before its tests, as one does whose set-up meets a failed assumption, may report no case at all)";
    /** Why a skipped case need not be one test. */
    private static final String HIDES = " (a skipped class, factory or template is reported as one case, whatever number of tests it holds)";

    /**
     * Why an edited file's reports may not list every test it has: before, of the report of the
     * file as it was, and after, of the report after the change, each a list of reasons, empty
     * where nothing says so; failedBefore, failedAfter: whether each holds a failure.
     *
     * Every count of a file takes a report for its whole list of tests, one case for each. A case
     * that passed is one test that ran. The others need not be:
     *
     *   A case that failed or threw may stand for a fault outside a test's body, in place of the
     *   file's tests (JUnit 4 reports a class it cannot set up as one case, initializationError)
     *   or beside them (Vitest reports a failed afterAll as a case of its own).
     *   A skipped case may stand for any number of tests, none or many: JUnit 4 reports an
     *   ignored class as one skipped case, Surefire a disabled JUnit 5 factory or template as one,
     *   whose tests it reports one by one once it runs them; JUnit 4 reports a class whose set-up
     *   meets a failed assumption as one skipped case too.
     *   An empty report may stand for any number of tests: a JUnit 5 class that stops in its
     *   set-up on an assumption can report no case at all.
     *
     * Which case stands for what a report does not say, and its name is not read to guess it. So a
     * report is taken for the whole list only where it is not empty, holds no case that failed or
     * threw, and each case it skips is skipped under its name, as many times, on the other side:
     * whatever a skipped case hides is then hidden on both sides alike. A name skipped on one side
     * only (gone, new, or a name that ran on the other side), or skipped more times on one side,
     * may stand for any number of tests: it is a reason of the side that skips it more.
     *
     * What each count needs of it, and only that (a count that needs less says more):
     *
     *   Tests removed, G - F (see gone): both reports. A test that seems gone may be inside a case
     *   skipped after the change, behind a failure there, or in an empty report; a skipped case
     *   gone may have stood for none, a failed one for a fault.
     *   New tests that pass on both sides, F - G - X (see newTests): the report as it was. A test
     *   it hid may come out under a new name. A test hidden after the change does not pass, and a
     *   case after the change that stands for many tests or none is one of the X.
     *   Tests that ran and do not run now, min(r0 - r1, s1 - s0) (see skips, rule 2): only that
     *   the report as it was holds no failure (failedOld), so that r0 counts tests that ran. A test
     *   hidden anywhere does not run, so r1 is never fewer than the tests that run now, and
     *   skipped cases only make the least smaller. So a switch-off stays a definite verdict where
     *   the change skips a class, a factory or a template as one case, and is said so, by count;
     *   what it may hide besides is not counted.
     *   Old tests taken out of the way, B - M (see broken): only that no report after the change
     *   holds a failure. B counts old tests by cases that passed as the file was, one test each;
     *   M counts cases that pass after the change, and a hidden test does not pass, so it is no
     *   rewrite. An old test hidden in a case skipped after the change, or in an empty report,
     *   does not run: it was taken out of the way, as said. Only a failure after the change, which
     *   may stand in place of the old test, so that it fails and is not gone, keeps it unsaid.
     *
     * A failure after the change is said all the same, as failing.
     */
    private record Listing(List<String> before, List<String> after, boolean failedBefore, boolean failedAfter) {
        /** Why the report as it was may not list every test, or null. */
        String old() {
            return say(before, false);
        }

        /** Why the report as it was or the one after the change may not list every test, or null. */
        String either() {
            List<String> all = new ArrayList<>();
            if (failedBefore && failedAfter) {
                // one reason for both sides
                all.add("the file as it was and after the change reported failures" + FAILURES);
                for (String w : before) if (!w.endsWith(FAILURES)) all.add(w);
                for (String w : after) if (!w.endsWith(FAILURES)) all.add(w);
            } else {
                all.addAll(before);
                all.addAll(after);
            }
            return say(all, !before.isEmpty() && !after.isEmpty());
        }

        /** Why the report as it was may not count tests that ran: it holds a failure. Null when it does not. */
        String failedOld() {
            return failedBefore ? say(List.of("the file as it was reported failures" + FAILURES), false) : null;
        }

        private static String say(List<String> why, boolean both) {
            if (why.isEmpty()) return null;
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < why.size(); i++) {
                String w = why.get(i);
                // each explanation once, at its last use
                for (String x : List.of(FAILURES, NONE, HIDES)) {
                    final int at = i;
                    if (w.endsWith(x) && why.subList(at + 1, why.size()).stream().anyMatch(later -> later.endsWith(x))) w = w.substring(0, w.length() - x.length());
                }
                parts.add(w);
            }
            return String.join(", and ", parts) + ", so " + (both ? "its reports" : "that report") + " may not list every test";
        }
    }

    /** The reasons a file's reports may not list every test, from its cases as it was (was) and after the change (is), by identity (see Listing). */
    private static Listing listing(Map<String, List<JUnitXml.Case>> was, Map<String, List<JUnitXml.Case>> is, Map<String, String> shown) {
        List<String> old = new ArrayList<>(), now = new ArrayList<>();
        boolean failedBefore = was.values().stream().anyMatch(Witness::failing), failedAfter = is.values().stream().anyMatch(Witness::failing);
        if (was.isEmpty()) old.add("the file as it was reported no tests" + NONE);
        if (failedBefore) old.add("the file as it was reported failures" + FAILURES);
        if (is.isEmpty()) now.add("after the change the file reports no tests" + NONE);
        if (failedAfter) now.add("after the change the file reports failures" + FAILURES);
        Set<String> keys = new LinkedHashSet<>(was.keySet());
        keys.addAll(is.keySet());
        List<String> fewer = new ArrayList<>(), more = new ArrayList<>(); // names skipped fewer times after the change than as it was, and more times
        for (String k : keys) {
            long o = skipped(was.getOrDefault(k, List.of())), n = skipped(is.getOrDefault(k, List.of()));
            if (o > n) fewer.add(shown(shown, k));
            else if (n > o) more.add(shown(shown, k));
        }
        if (!fewer.isEmpty()) old.add("the file as it was skipped a case under " + (fewer.size() == 1 ? "a name" : "names") + " it does not skip as often after the change ("
                + shortList(fewer) + ")" + HIDES);
        if (!more.isEmpty()) now.add("after the change the file skips a case under " + (more.size() == 1 ? "a name" : "names") + " it did not skip as often before ("
                + shortList(more) + ")" + HIDES);
        return new Listing(old, now, failedBefore, failedAfter);
    }

    /** How many of these cases are skipped. */
    private static long skipped(List<JUnitXml.Case> cases) {
        return cases.size() - ran(cases);
    }

    /** True when one of these cases failed or threw. */
    private static boolean failing(List<JUnitXml.Case> cases) {
        return cases.stream().anyMatch(c -> failed(c.status()));
    }

    /** True when a run does not report a case of this identity, or skips every one. */
    private static boolean notRun(FileRun run, String key) {
        return run.cases().stream().filter(c -> key(c).equals(key)).allMatch(c -> c.status() == Status.SKIPPED);
    }

    /** The name of a case by its identity (suite#name). */
    private static String name(String key) {
        return key.substring(key.indexOf('#') + 1);
    }

    /** "the file no longer reports 2 tests under the names it had (a, b), and reports 1 under a name it did not have (c)": what the reports show, not which tests these are. */
    private static String had(Names n) {
        List<JUnitXml.Case> gone = n.gone(), fresh = n.fresh();
        return "the file no longer reports " + tests(gone.size()) + " under the " + (gone.size() == 1 ? "name" : "names") + " it had (" + shortList(shown(n.shown(), gone)) + "), and reports "
                + (fresh.isEmpty() ? "none under a name it did not have" : fresh.size() + " under " + (fresh.size() == 1 ? "a name" : "names") + " it did not have (" + shortList(shown(n.shown(), fresh)) + ")");
    }

    private static String tests(long n) {
        return n + (n == 1 ? " test" : " tests");
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
