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
 * and, only when a test is gone or may be switched off, a fourth way:
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
        /** A new test that passes on both sides: it does not test the change. Said of a file (name ""): at least so many of the tests it names (see newTests). */
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
        /** Nothing can be said: it could not run before the change, or threw something other than an assertion there, or the reports cannot tell two answers apart. */
        UNKNOWN,
        /** Runs disagreed with each other. */
        UNSTABLE,
        /** Skipped after the change: a new test, or one whose state before the change is not known. */
        SKIPPED,
        /**
         * A name that ran in the file as it was before the change, and is skipped after it, in a
         * file whose list of cases did not change otherwise: switched off. Said of a file (name
         * ""): fewer of its cases run and more are skipped, so at least so many tests that ran
         * before do not run now (see skips).
         */
        SKIPPED_NOW,
        /** Skipped under its name in the file as it was and after the change: not counted. */
        EXISTING_SKIPPED,
        /**
         * The edited file had this test before the change, and does not report it or any test it
         * did not have. Said of a file (name ""): it reports fewer tests than it had, so at least
         * so many were removed (see gone).
         */
        REMOVED
    }

    /**
     * name is "" when the verdict is about the whole file. tests is how many tests it counts: one
     * for a test, and for some verdicts about a file the least number the reports prove (atLeast).
     */
    record Verdict(String file, String name, Kind kind, String detail, String runner, int tests) {
        Verdict(String file, String name, Kind kind, String detail, String runner) {
            this(file, name, kind, detail, runner, 1);
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

    private static List<Verdict> judge(Change change, Runner runner, List<String> files, Set<String> edited, Sides sides, int runs, int timeout) {
        List<Verdict> out = new ArrayList<>();
        String by = runner.name();
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
        Map<String, Names> named = new LinkedHashMap<>(); // edited files whose run after the change and run as they were both gave results, lined up by name
        Map<String, List<Verdict>> skips = new LinkedHashMap<>(); // of those, the files whose skips may say a test is switched off: the first answer, said after the repeat runs
        Map<String, List<JUnitXml.Case>> rewrites = new LinkedHashMap<>(); // of those, the cases that pass after the change and do not pass on both sides (see broken)
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
            Set<String> fresh = new LinkedHashSet<>();
            if (n != null) for (JUnitXml.Case c : n.fresh()) fresh.add(key(c));
            Map<String, JUnitXml.Case> was = new LinkedHashMap<>();
            Set<String> twice = new LinkedHashSet<>(); // two cases under one name, before or after the change: the sides cannot be compared by that name
            if (b != null) for (JUnitXml.Case c : b.cases()) if (was.put(key(c), c) != null) twice.add(key(c));
            Set<String> seen = new LinkedHashSet<>();
            for (JUnitXml.Case c : a.cases()) if (!seen.add(key(c))) twice.add(key(c));
            seen.clear();
            List<JUnitXml.Case> passBoth = new ArrayList<>(); // cases under a name the file did not report before that pass on both sides (see newTests)
            long clean = 0; // cases that pass on both sides, under any name
            Set<String> passing = new LinkedHashSet<>(); // the names of those
            for (JUnitXml.Case c : a.cases()) {
                JUnitXml.Case p = was.get(key(c));
                // First what the run after the change says alone: two cases of one name on the side before the change do not matter to these.
                if (c.status() == Status.FLAKY) out.add(new Verdict(f, c.name(), Kind.UNSTABLE, "the build ran it again and got another result", by));
                else if (c.status() == Status.SKIPPED) continue; // see skips, below
                else if (c.status() != Status.PASSED) out.add(new Verdict(f, c.name(), Kind.FAILS_AFTER, describe(c), by));
                // what is left compares the two sides by name, which must then name one test on each side. The same file runs on
                // both sides, so its cases are named alike on both.
                else if (twice.contains(key(c))) {
                    if (seen.add(key(c))) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "more than one test reports this name, so they cannot be told apart", by));
                }
                else if (b == null || b.problem() != null) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "before the change the file " + (b == null ? "gave no result" : b.problem()), by));
                else if (p == null) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "was not found before the change", by));
                else if (p.status() == Status.PASSED && shared != null) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "passes before and after, but " + shared, by));
                else if (p.status() == Status.PASSED && e.problem() != null) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "passes before and after, but run as it was before the change, the file "
                        + e.problem() + ", so it cannot be told whether this test is new", by));
                else if (p.status() == Status.PASSED) {
                    clean++;
                    passing.add(key(c));
                    if (n == null) out.add(new Verdict(f, c.name(), Kind.PASSES_ON_BOTH, "", by));
                    else if (fresh.contains(key(c))) passBoth.add(c);
                    else out.add(new Verdict(f, c.name(), Kind.EXISTING_PASSES_ON_BOTH, "", by));
                }
                else if (p.status() == Status.SKIPPED) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "was skipped before the change", by));
                else if (p.status() == Status.FLAKY) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "before the change the build ran it again and got another result", by));
                else if (p.status() == Status.ERRORED) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "before the change it threw " + shortType(p) + ", not an assertion", by));
                else candidates.computeIfAbsent(f, k -> new LinkedHashSet<>()).add(key(c));
            }
            if (n == null) {
                // a file the change adds, or one whose earlier version gave no result: its skipped tests are listed
                for (JUnitXml.Case c : a.cases()) if (c.status() == Status.SKIPPED) out.add(new Verdict(f, c.name(), Kind.SKIPPED, "skipped after the change", by));
            } else {
                named.put(f, n);
                rewrites.put(f, a.cases().stream().filter(c -> (c.status() == Status.PASSED || c.status() == Status.FLAKY) && !passing.contains(key(c))).toList());
                Skips s = skips(f, n, by);
                out.addAll(s.listed());
                if (!s.answer().isEmpty()) skips.put(f, s.answer());
                // the cases that do not pass on both sides; skipped ones only where a skip may hide a test (see newTests)
                long skipped = a.cases().stream().filter(c -> c.status() == Status.SKIPPED).count();
                out.addAll(newTests(f, n, passBoth, a.cases().size() - clean - (s.answer().isEmpty() ? skipped : 0), by));
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
                out.add(new Verdict(f, k.substring(k.indexOf('#') + 1), Kind.UNKNOWN,
                        "after the change it passes only together with the other changed test files, so its failure before the change may come from them", by));
            }
            if (candidates.get(f).isEmpty()) candidates.remove(f);
        }
        if (!alone.isEmpty()) {
            restore(change, runner, head, files);
            restore(change, runner, base, files);
        }
        List<String> sensitive = new ArrayList<>(candidates.keySet());
        List<String> switched = new ArrayList<>(skips.keySet());
        Set<String> again = new LinkedHashSet<>(sensitive);
        again.addAll(switched);
        Map<String, String> unstable = new LinkedHashMap<>(); // key -> why
        Set<String> unsteady = new LinkedHashSet<>(); // files whose skips said another thing in a repeat run
        for (int i = 1; i < runs && !again.isEmpty(); i++) {
            Map<String, FileRun> a = runner.run(head.dir, new ArrayList<>(again), timeout);
            if (!sensitive.isEmpty()) {
                List<String> together = sensitive.stream().filter(f -> !alone.contains(f)).toList();
                Map<String, FileRun> b = new LinkedHashMap<>();
                if (!alone.isEmpty()) restore(change, runner, base, files);
                if (!together.isEmpty()) b.putAll(runner.run(base.dir, together, timeout));
                for (String f : sensitive) if (alone.contains(f)) b.put(f, runAlone(change, runner, files, f, base, timeout));
                for (String f : sensitive) {
                    for (String k : candidates.get(f)) {
                        Status sa = status(a.get(f), k), sb = status(b.get(f), k);
                        if (sa != Status.PASSED) unstable.putIfAbsent(f + "\n" + k, "passed after the change in one run and not in another");
                        else if (sb != Status.FAILED) unstable.putIfAbsent(f + "\n" + k, "failed before the change in one run and not in another");
                    }
                }
            }
            if (!switched.isEmpty()) {
                // the whole decision is made again from the complete reports of this run, on both sides
                Map<String, FileRun> o = runner.run(sides.old().dir, switched, timeout);
                for (String f : switched) {
                    if (!signature(skips.get(f)).equals(said(o.get(f), a.get(f), f, by))) unsteady.add(f);
                }
            }
        }
        // the fourth run, where a test is gone or may be switched off: every old test of such a file that passed as it was
        Map<String, Set<String>> wanted = new LinkedHashMap<>();
        for (Map.Entry<String, Names> e : named.entrySet()) {
            if (e.getValue().gone().isEmpty() && !skips.containsKey(e.getKey())) continue;
            Set<String> keys = new LinkedHashSet<>();
            for (JUnitXml.Case c : earlier.get(e.getKey()).cases()) if (c.status() == Status.PASSED) keys.add(key(c));
            if (!keys.isEmpty()) wanted.put(e.getKey(), keys);
        }
        Fourth fourth = fourth(change, runner, earlier, wanted, sides, runs, timeout);
        Set<String> taken = new LinkedHashSet<>(), judged = new LinkedHashSet<>(); // per file and name: old tests said on their own to be broken and taken out of the way; and those, or unstable in that
        for (Map.Entry<String, Names> e : named.entrySet()) {
            String f = e.getKey();
            out.addAll(broken(f, e.getValue(), after.get(f), rewrites.get(f), fourth, taken, judged, by));
        }
        Set<String> counted = new LinkedHashSet<>(); // files of which the counts say that fewer tests run
        for (Map.Entry<String, List<Verdict>> s : skips.entrySet()) if (s.getValue().stream().anyMatch(v -> v.name().isEmpty() && v.kind() == Kind.SKIPPED_NOW)) counted.add(s.getKey());
        out.addAll(gone(named, fourth, counted, taken, judged, by));
        for (Map.Entry<String, List<Verdict>> s : skips.entrySet()) {
            String f = s.getKey();
            for (Verdict v : s.getValue()) {
                if (unsteady.contains(f)) out.add(new Verdict(f, v.name(), Kind.UNSTABLE, v.name().isEmpty()
                        ? "whether tests that ran before the change are skipped now differs from run to run: what the file reports, after the change or as it was, is not the same in every run"
                        : "said to be switched off in one run and not in another: what the file reports, after the change or as it was, is not the same in every run", by, v.tests()));
                else if (!v.name().isEmpty() && taken.contains(f + "\n" + v.name())) continue; // said once, as broken and taken out of the way
                else out.add(v.kind() == Kind.UNKNOWN ? new Verdict(f, "", Kind.UNKNOWN, v.detail() + fourth.note(f), by, v.tests()) : v);
            }
        }
        for (String f : sensitive) {
            for (String k : candidates.get(f)) {
                String name = k.substring(k.indexOf('#') + 1);
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
     * per file, the old tests that passed in the file as it was and failed or threw here in every
     * run; wavered: those that did so in some runs only.
     */
    private record Fourth(Map<String, FileRun> first, Map<String, Set<String>> broke, Map<String, Set<String>> wavering) {
        static final Fourth NONE = new Fourth(Map.of(), Map.of(), Map.of());

        boolean broke(String file, java.util.Collection<String> keys) {
            return keys.stream().anyMatch(broke.getOrDefault(file, Set.of())::contains);
        }

        boolean wavered(String file, java.util.Collection<String> keys) {
            return keys.stream().anyMatch(wavering.getOrDefault(file, Set.of())::contains);
        }

        /** "passed before the change; as the test was, on the code after the change x fails: why", of those keys that broke. */
        String said(String file, java.util.Collection<String> keys) {
            List<String> which = keys.stream().filter(broke.getOrDefault(file, Set.of())::contains).toList();
            FileRun run = first.get(file);
            JUnitXml.Case one = run.cases().stream().filter(c -> key(c).equals(which.get(0))).findFirst().orElseThrow();
            String names = which.size() == 1 ? one.name() + " " + describe(one) : shortList(which.stream().map(k -> k.substring(k.indexOf('#') + 1)).toList()) + " fail or throw";
            return "passed before the change; as the test was, on the code after the change " + names;
        }

        /** For a verdict that could not be judged: why the fourth run of its file told nothing, or "". */
        String note(String file) {
            if (!first.containsKey(file)) return "";
            FileRun run = first.get(file);
            String problem = run == null ? "gave no result" : run.problem();
            return problem == null ? "" : ". The file as it was, run on the code after the change, " + problem + ", so whether the change broke these tests is not known";
        }
    }

    /** For a test whose old runs passed and failed on the code after the change in some runs only. */
    private static final String WAVERED = "passed before the change, and as the test was, failed on the code after the change in one run and not in another";

    /**
     * The fourth run: the edited test files that have a test gone, or whose skips may hide a test
     * switched off (wanted: per file, the identities of the old tests that passed in the third
     * run), as they were before the change, on the code after it. An old test that passed in the
     * third run (its file as it was, on the code as it was) and fails or throws here failed on what
     * the change wrote; what became of it decides what is said (see broken). Like a sensitive test
     * it must do so in every run, on both sides: else it wavered.
     *
     * The checkout is the tree after the change with only these test files put back as the base
     * has them (Sides.oldTests): production code, build files, fixtures, helpers and the other
     * changed test files stay as the change has them. So a test renamed and not otherwise edited
     * runs here exactly as its new name runs after the change, and cannot be accused for the
     * rename. A test whose fixture the change rewrote or deleted may fail here because of that
     * and not because of the production code; that is still a test that passed before, fails on
     * what the change wrote, and the report says no more than that. A test
     * file that does not compile or load here (the change renamed what it used), or gives no
     * result, tells nothing: its tests stay as they were judged without it, and the report says
     * why (note).
     */
    private static Fourth fourth(Change change, Runner runner, Map<String, Earlier> earlier, Map<String, Set<String>> wanted, Sides sides, int runs, int timeout) {
        if (wanted.isEmpty()) return Fourth.NONE;
        List<String> files = new ArrayList<>(wanted.keySet());
        Map<String, FileRun> first = new LinkedHashMap<>();
        Scratch mixed = sides.oldTests();
        try {
            mixed.write(change.base, files);
            runner.forgetBuilt(mixed.dir, files);
        } catch (java.io.IOException e) {
            for (String f : files) first.put(f, FileRun.failed("could not be set up (" + e.getMessage() + ")"));
            return new Fourth(first, Map.of(), Map.of());
        }
        Map<String, FileRun> ran = runner.run(mixed.dir, files, timeout);
        for (String f : files) first.put(f, ran.get(f));
        Map<String, Set<String>> broke = new LinkedHashMap<>(), wavering = new LinkedHashMap<>();
        for (String f : files) {
            FileRun was = new FileRun(earlier.get(f).cases(), null);
            for (String k : wanted.get(f)) if (status(was, k) == Status.PASSED && failed(status(first.get(f), k))) broke.computeIfAbsent(f, x -> new LinkedHashSet<>()).add(k);
        }
        List<String> again = new ArrayList<>(broke.keySet());
        for (int i = 1; i < runs && !again.isEmpty(); i++) {
            Map<String, FileRun> o = runner.run(sides.old().dir, again, timeout), m = runner.run(mixed.dir, again, timeout);
            for (String f : again) {
                for (String k : broke.get(f)) if (status(o.get(f), k) != Status.PASSED || !failed(status(m.get(f), k))) wavering.computeIfAbsent(f, x -> new LinkedHashSet<>()).add(k);
            }
        }
        for (Map.Entry<String, Set<String>> w : wavering.entrySet()) broke.get(w.getKey()).removeAll(w.getValue());
        return new Fourth(first, broke, wavering);
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
     * and after the change (1), over the whole file.
     */
    private record Names(List<JUnitXml.Case> gone, List<JUnitXml.Case> fresh, List<Flip> off, long on, List<Flip> asBefore, long r0, long s0, long r1, long s1) {
        List<JUnitXml.Case> freshSkipped() {
            return fresh.stream().filter(c -> c.status() == Status.SKIPPED).toList();
        }
    }

    /** Cases of one name (key: with its suite) that the reports say one thing of: how many, and in words when there are more than one. */
    private record Flip(String key, String name, long tests, String detail) {}

    private static Names names(List<JUnitXml.Case> old, List<JUnitXml.Case> now) {
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
            String name = n.get(0).name();
            if (ranO > ranN) off.add(new Flip(k, name, ranO - ranN, o.size() == 1 ? "" : o.size() + " tests report this name; " + ranO + " of them ran before the change, " + ranN + " run now"));
            else on += ranN - ranO;
            if (both > 0) asBefore.add(new Flip(k, name, both, ""));
        }
        return new Names(gone, fresh, off, on, asBefore, ran(old), old.size() - ran(old), ran(now), now.size() - ran(now));
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
     *   2. Off flips, and nothing else changed in the file's list of cases (no fresh or gone name,
     *      no on flip): each off flip is a test that ran and is skipped now. Sound under any
     *      naming: the file reports the same names as before, so r0 - r1 = s1 - s0 = the off
     *      flips. That many fewer cases run and that many more are skipped, which is rule 3 with
     *      every case it counts named; each name said ran before and is skipped now, whatever test
     *      stands behind it (a name that is a place: the case at that place).
     *   3. Fewer cases run and more are skipped (r1 < r0, s1 > s0), whatever the names: at least
     *      min(r0 - r1, s1 - s0) tests that ran do not run now. Sound under any naming: the counts
     *      are of the whole file, and the cases that ran and do not run now number r0 - r1 at least;
     *      of them, as many as there are more skipped cases may be among those (switched off), and
     *      the rest are not reported at all, which the gone tests (see gone) say: the two never
     *      count one case twice, as the file reports r0 + s0 - (r1 + s1) fewer cases.
     *   4. Anything else in which a test that ran may be skipped now: one could not be judged for
     *      the file, with the counts and the names, and the two stories they fit.
     */
    private static Skips skips(String f, Names n, String by) {
        List<Verdict> answer = new ArrayList<>(), listed = new ArrayList<>();
        for (Flip s : n.asBefore()) listed.add(new Verdict(f, s.name(), Kind.EXISTING_SKIPPED, "skipped before the change too", by, (int) s.tests()));
        List<JUnitXml.Case> freshOff = n.freshSkipped();
        if (n.off().isEmpty() && (freshOff.isEmpty() || n.r0() == 0)) {
            for (JUnitXml.Case c : freshOff) listed.add(new Verdict(f, c.name(), Kind.SKIPPED, "skipped after the change; no test of the file ran before it", by));
            return new Skips(answer, listed);
        }
        if (n.fresh().isEmpty() && n.gone().isEmpty() && n.on() == 0) {
            for (Flip s : n.off()) answer.add(new Verdict(f, s.name(), Kind.SKIPPED_NOW, s.detail(), by, (int) s.tests()));
            return new Skips(answer, listed);
        }
        String counts = "the file runs " + n.r1() + " of its tests where it ran " + n.r0() + " before the change, and skips " + n.s1() + " where it skipped " + n.s0();
        if (n.r1() < n.r0() && n.s1() > n.s0()) {
            long least = Math.min(n.r0() - n.r1(), n.s1() - n.s0());
            answer.add(new Verdict(f, "", Kind.SKIPPED_NOW, counts + skippedNow(n) + ": at least " + least + (least == 1 ? " test that ran does" : " tests that ran do")
                    + " not run now, switched off, or removed while skipped tests were added", by, (int) least));
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

    /** " (skipped now under a name that ran before: a, b; under a name the file did not report before: c)". */
    private static String skippedNow(Names n) {
        List<String> parts = new ArrayList<>();
        if (!n.off().isEmpty()) parts.add("skipped now under " + (n.off().size() == 1 ? "a name" : "names") + " that ran before: " + shortList(n.off().stream().map(Flip::name).toList()));
        List<JUnitXml.Case> freshOff = n.freshSkipped();
        if (!freshOff.isEmpty()) parts.add((parts.isEmpty() ? "skipped " : "") + "under " + (freshOff.size() == 1 ? "a name" : "names") + " the file did not report before: " + shortList(shown(freshOff)));
        return parts.isEmpty() ? "" : " (" + String.join("; ", parts) + ")";
    }

    /** The answer of a file's skips in another run, to compare with the first: kind, name and count of each verdict. Null when a run gave no result. */
    private static List<String> said(FileRun old, FileRun after, String f, String by) {
        if (old == null || old.problem() != null || after == null || after.problem() != null) return null;
        return signature(skips(f, names(old.cases(), after.cases()), by).answer());
    }

    private static List<String> signature(List<Verdict> answer) {
        return answer.stream().map(v -> v.kind() + "\n" + v.name() + "\n" + v.tests()).toList();
    }

    /** The cases' names, each with its suite where two of them share a name (nested classes with a method of one name). */
    private static List<String> shown(List<JUnitXml.Case> cases) {
        return shown(cases, cases);
    }

    /** As above, where a name is told by its suite when it has more than one among these cases (among). */
    private static List<String> shown(List<JUnitXml.Case> cases, List<JUnitXml.Case> among) {
        Map<String, Set<String>> suites = new LinkedHashMap<>();
        for (JUnitXml.Case c : among) suites.computeIfAbsent(c.name(), k -> new LinkedHashSet<>()).add(c.suite());
        return cases.stream().map(c -> suites.getOrDefault(c.name(), Set.of()).size() > 1 ? c.suite() + "." + c.name() : c.name()).toList();
    }

    /**
     * The new tests of an edited file that pass on both sides. A name the file did not report
     * before does not prove a new test: where cases are named by their place, a row put in among
     * others moves every later one to the next place, and the last one to a name the file did
     * not have. Counting does:
     *
     *   the file reports F names it did not report before and no longer reports G, so it has at
     *   least F - G tests it did not have, whatever the names (each name it reports on both sides
     *   stands for one case on each side, so the rest of the cases are counted by F and G alone).
     *   Of all its cases after the change, X do not pass on both sides (they fail before, fail
     *   after, could not be judged, or are skipped where a skip may hide a test). A new test is
     *   one of the cases after the change, so at least F - G - X new tests pass on both sides.
     *
     * Sound under any naming: neither count looks at what a name says. Skipped cases count in X
     * only where the file's skips may hide a test (see skips): where they cannot, every skipped
     * case is under a name that was skipped before, and a new test put in under such a name would
     * have moved a case to a name that ran before (an off flip) or to a skipped one the file did
     * not have, which the skips then say.
     *
     * P: the cases under names the file did not have that pass on both sides. The least number,
     * N = F - G - X, is never more than P (each of the F not counted in P is one of the X). N > 0:
     * the file has at least N new tests that do not test the change: each of the P when N = P,
     * else the file is named with the P. N = 0: each of the P could not be judged.
     */
    private static List<Verdict> newTests(String f, Names n, List<JUnitXml.Case> passBoth, long other, String by) {
        List<Verdict> out = new ArrayList<>();
        long p = passBoth.size(), g = n.gone().size(), fr = n.fresh().size(), least = Math.max(0, fr - g - other);
        if (p == 0) return out;
        if (least >= p) {
            for (JUnitXml.Case c : passBoth) out.add(new Verdict(f, c.name(), Kind.PASSES_ON_BOTH, "", by));
            return out;
        }
        String counts = "the file reports " + tests(fr) + " it did not have" + (g > 0 ? " and no longer reports " + g + " it had" : "")
                + (other > 0 ? ", and " + other + " of its tests " + (other == 1 ? "does" : "do") + " not pass on both sides" : "");
        if (least > 0) {
            out.add(new Verdict(f, "", Kind.PASSES_ON_BOTH, counts + ": at least " + least + (least == 1 ? " new test passes" : " new tests pass") + " before and after. Under names the file "
                    + "did not have, " + p + " pass before and after (" + shortList(shown(passBoth)) + "), and which " + (least == 1 ? "one is new" : least + " are new") + " cannot be told", by, (int) least));
            return out;
        }
        List<String> why = new ArrayList<>();
        if (g > 0) why.add(g == 1 ? "the test it no longer reports, under a new name" : "one of the " + g + " tests it no longer reports, under a new name");
        if (other > 0) why.add("a test moved to this name by one put in before it, where cases are named by their place (the new "
                + (fr - g == 1 ? "test" : "tests") + " then being among the " + other + " that do not pass on both sides)");
        List<String> names = shown(passBoth, n.fresh());
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
     *   F = 0       each gone test was removed
     *   G > F       the file lost tests: at least G - F were removed, and which cannot be told
     *   G <= F      it could not be judged: a rename looks exactly like a test deleted and
     *               another written. Unless the counts of the whole file say that fewer tests
     *               run (see skips): that is said there, once.
     *
     * Sound under any naming: the file reports G - F fewer cases than it did (each name it reports
     * on both sides has as many cases on each), so at least G - F cases it had are not there. A
     * test that is skipped is still reported, so these were taken out. With F = 0 every gone name
     * is one of them.
     *
     * Before all of these, the fourth run (see fourth): a gone test that passed before the change
     * and fails as it was on the code after it was broken by the change and taken out of the way,
     * whatever the counts say. A gone test of which the runs disagree on that is unstable.
     */
    private static List<Verdict> gone(Map<String, Names> named, Fourth fourth, Set<String> counted, Set<String> taken, Set<String> judged, String by) {
        List<Verdict> out = new ArrayList<>();
        for (Map.Entry<String, Names> e : named.entrySet()) {
            String f = e.getKey();
            List<JUnitXml.Case> lost = e.getValue().gone(), fresh = e.getValue().fresh();
            int g = lost.size(), nf = fresh.size();
            if (g == 0) continue;
            if (nf == 0) {
                for (JUnitXml.Case c : lost) if (!taken.contains(f + "\n" + c.name())) out.add(new Verdict(f, c.name(), Kind.REMOVED, "", by));
            } else if (g > nf) {
                out.add(new Verdict(f, "", Kind.REMOVED, had(lost, fresh) + ": at least " + (g - nf) + (g - nf == 1 ? " was" : " were") + " removed, and which cannot be told", by, g - nf));
            } else if (!counted.contains(f) && !lost.stream().allMatch(c -> judged.contains(f + "\n" + c.name()))) {
                out.add(new Verdict(f, "", Kind.UNKNOWN, had(lost, fresh) + ". The reports cannot tell a test renamed from a test deleted and another written" + fourth.note(f), by));
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
     * An old test whose name is gone or skipped, of which the runs disagree, is unstable (unless
     * the counts say on their own that it was removed: no new name). The names said broken on
     * their own go into taken, so that they are not also said removed or switched off; those and
     * the unstable ones into judged.
     */
    private static List<Verdict> broken(String f, Names n, FileRun a, List<JUnitXml.Case> rewrites, Fourth fourth, Set<String> taken, Set<String> judged, String by) {
        List<Verdict> out = new ArrayList<>();
        Map<String, List<JUnitXml.Case>> now = byKey(a.cases());
        List<String> out1 = new ArrayList<>(), running = new ArrayList<>(); // the old tests broken: not run now, and run now
        for (String k : fourth.broke.getOrDefault(f, Set.of())) {
            if (now.getOrDefault(k, List.of()).stream().allMatch(c -> c.status() == Status.SKIPPED)) out1.add(k);
            else running.add(k);
        }
        for (String k : fourth.wavering.getOrDefault(f, Set.of())) {
            // a gone test of a file with no new one was removed all the same, and one switched off by name is so all the same
            if (now.containsKey(k) ? !now.get(k).stream().allMatch(c -> c.status() == Status.SKIPPED) : n.fresh().isEmpty()) continue;
            out.add(new Verdict(f, name(k), Kind.UNSTABLE, WAVERED, by));
            judged.add(f + "\n" + name(k));
        }
        long b = out1.size(), m = rewrites.size();
        if (b > 0 && m == 0) {
            for (String k : out1) {
                out.add(new Verdict(f, name(k), Kind.OLD_FAILS_AFTER, fourth.said(f, List.of(k)) + (now.containsKey(k) ? ", and the change skips it now" : ". The edited file does not report it now"), by));
                taken.add(f + "\n" + name(k));
                judged.add(f + "\n" + name(k));
            }
        } else if (b > 0) {
            String what = shortList(out1.stream().map(Witness::name).toList()) + (b == 1 ? " passed" : " passed") + " before the change and " + (b == 1 ? "fails" : "fail")
                    + " on the code after it as the file was, and the file does not report " + (b == 1 ? "it now or skips it" : "them now or skips them") + ". It has "
                    + m + (m == 1 ? " test that passes" : " tests that pass") + " after the change and " + (m == 1 ? "does" : "do") + " not pass on both sides ("
                    + shortList(shown(rewrites)) + ")";
            if (b > m) out.add(new Verdict(f, "", Kind.OLD_FAILS_AFTER, what + ", and at most " + m + " of the " + b + " old tests can be among them, rewritten for the changed "
                    + "behaviour: at least " + (b - m) + (b - m == 1 ? " was" : " were") + " taken out of the way", by, (int) (b - m)));
            else out.add(new Verdict(f, "", Kind.UNKNOWN, what + ", so it cannot be told whether the old " + (b == 1 ? "test was" : "tests were") + " rewritten for the changed "
                    + "behaviour under other names, or taken out of the way and other tests written", by, (int) b));
        }
        if (!running.isEmpty()) out.add(new Verdict(f, "", Kind.UNKNOWN, shortList(running.stream().map(Witness::name).toList()) + (running.size() == 1 ? " passed" : " passed")
                + " before the change and " + (running.size() == 1 ? "fails" : "fail") + " on the code after it as the file was. A test of " + (running.size() == 1 ? "that name" : "each name")
                + " still runs after the change, so it cannot be told whether " + (running.size() == 1 ? "it was" : "each was") + " edited for the changed behaviour, or taken out and its "
                + "name given to another case, as a row taken out does to the rows after it where cases are named by their place"
                + (n.r1() < n.r0() ? ". The file runs fewer tests than before (" + n.r1() + " where it ran " + n.r0() + ")" : ""), by, running.size()));
        return out;
    }

    /** The name of a case by its identity (suite#name). */
    private static String name(String key) {
        return key.substring(key.indexOf('#') + 1);
    }

    /** "the file had 2 tests that it does not report now (a, b), and reports 1 that it did not have (c)". */
    private static String had(List<JUnitXml.Case> gone, List<JUnitXml.Case> fresh) {
        List<JUnitXml.Case> both = new ArrayList<>(gone);
        both.addAll(fresh);
        return "the file had " + tests(gone.size()) + " that it does not report now (" + shortList(shown(gone, both)) + "), and reports "
                + fresh.size() + " that it did not have (" + shortList(shown(fresh, both)) + ")";
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
