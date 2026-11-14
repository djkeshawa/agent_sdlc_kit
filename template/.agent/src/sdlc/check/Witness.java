package sdlc.check;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * sides does not test it. Which tests are new, how many that ran do not run now, and which are
 * gone is read from the reports of the old and the after run, not from the files' text: the test
 * framework knows which tests a file has. A case is known by its exact name (suite and name) and
 * by nothing else, and a name says only that the report gave it: a parameterized test's runs may
 * be named by their place, by their arguments or by display names, and a name of one shape may
 * be any of these. So where the names cannot tell, the counts of each file decide (see names),
 * and what the counts cannot prove is said to be not known, never passed in silence.
 *
 * Every verdict is decided once every run is made, from every report of every run (see Seen and
 * decide), and each says what it answers for (see Cover). Then every difference among a file's
 * reports, between the file as it was and after the change and from run to run, is listed apart
 * from the rules (see Ledger), and what no verdict answers for gets a line of its own (see
 * reconcile): a rule that says nothing of a difference leaves it said all the same.
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
         * the file as it was lists every test, in every combination of the runs made (see
         * newTests).
         */
        PASSES_ON_BOTH,
        /** A test whose name the file reported before the change, that passes on both sides in every run: not counted against the change. */
        EXISTING_PASSES_ON_BOTH,
        /** Fails after the change, in a run after the change (and says in how many where not in all). */
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
         * every test (see listing), or the fourth run did not judge an old test (see untold), or
         * the reports differ in a way no rule judged (see reconcile).
         */
        UNKNOWN,
        /** Runs disagreed with each other. */
        UNSTABLE,
        /** Skipped after the change: a new test, or one whose state before the change is not known. */
        SKIPPED,
        /** Skipped under its name in the file as it was and after the change, in every run: not counted. */
        EXISTING_SKIPPED,
        /**
         * Said of a file (name ""): at least so many tests that ran before the change do not run
         * now, in every run, whatever became of them: deleted, skipped, hidden in a skipped case or
         * an empty report, renamed while switched off (see lost).
         */
        NOT_RUN_NOW
    }

    /**
     * name is "" when the verdict is about the whole file. tests is how many tests it counts: one
     * for a test, and for some verdicts about a file the least number the reports prove (atLeast).
     * newCode: could not be judged because before the change the test did not compile, was not
     * there, or threw: what a test of code the change adds does there. cover: what it answers
     * for, of the differences among the file's reports (see reconcile). Every way to make one
     * asks for it: a rule that is added later says what it answers for, or what it does not
     * answer for is said again in its place.
     */
    record Verdict(String file, String name, Kind kind, String detail, String runner, int tests, boolean newCode, Cover cover) {
        Verdict(String file, String name, Kind kind, String detail, String runner, Cover cover) {
            this(file, name, kind, detail, runner, 1, false, cover);
        }

        Verdict(String file, String name, Kind kind, String detail, String runner, int tests, Cover cover) {
            this(file, name, kind, detail, runner, tests, false, cover);
        }

        /** Could not be judged: before the change the test did not compile, was not there, or threw. What it answers for is set where the runs are put together (see decide). */
        static Verdict beforeFailed(String file, String name, String detail, String runner) {
            return new Verdict(file, name, Kind.UNKNOWN, detail, runner, 1, true, Cover.NONE);
        }

        /** The same verdict, answering for this. */
        Verdict with(Cover c) {
            return new Verdict(file, name, kind, detail, runner, tests, newCode, c);
        }

        /** True when it counts at least so many of the tests it names, and which of them the reports do not say. */
        boolean atLeast() {
            return name.isEmpty() && (kind == Kind.PASSES_ON_BOTH || kind == Kind.NOT_RUN_NOW || kind == Kind.OLD_FAILS_AFTER);
        }
    }

    /**
     * What a verdict answers for, of the differences among its file's reports (see Ledger): the
     * identities whose difference between the file as it was and after the change it judges
     * (change), and per way, those whose difference from run to run it judges (repeat). ALL
     * stands for every identity of the file, and for the file itself (a run of a way that gave no
     * result where another gave one). A key is suite#name, so it is never ALL.
     */
    record Cover(Set<String> change, Map<Side, Set<String>> repeat) {
        static final String ALL = "*";
        /** Answers for nothing: what it says is said beside the differences, which are then answered elsewhere. */
        static final Cover NONE = new Cover(Set.of(), Map.of());
        /** Every difference of the file's list: a verdict said of the whole file from every run of it. */
        static final Cover FILE = every(Set.of(ALL), Side.values());
        /** Every difference between the file as it was and after the change, and none from run to run. */
        static final Cover LIST = new Cover(Set.of(ALL), Map.of());
        /** Every difference from run to run, in every way, and none between the sides. */
        static final Cover RUNS = new Cover(Set.of(), FILE.repeat());

        /** These identities, between the sides, and from run to run in these ways. */
        static Cover of(Collection<String> keys, Side... ways) {
            return every(new LinkedHashSet<>(keys), ways);
        }

        /** These identities between the sides only. */
        static Cover change(Collection<String> keys) {
            return new Cover(new LinkedHashSet<>(keys), Map.of());
        }

        private static Cover every(Set<String> keys, Side... ways) {
            Map<Side, Set<String>> r = new EnumMap<>(Side.class);
            for (Side s : ways) r.put(s, keys);
            return new Cover(keys, r);
        }

        Cover and(Cover o) {
            Set<String> c = new LinkedHashSet<>(change);
            c.addAll(o.change);
            Map<Side, Set<String>> r = new EnumMap<>(Side.class);
            for (Map<Side, Set<String>> m : List.of(repeat, o.repeat)) {
                for (Map.Entry<Side, Set<String>> e : m.entrySet()) r.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
            }
            return new Cover(c, r);
        }

        boolean change(String key) {
            return change.contains(ALL) || change.contains(key);
        }

        boolean repeat(Side way, String key) {
            Set<String> s = repeat.getOrDefault(way, Set.of());
            return s.contains(ALL) || s.contains(key);
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

    /**
     * For the self test only: rules that say nothing, by name, so that what answers in their
     * place can be seen (see reconcile): "per test", "new tests", "skips", "lost", "gone",
     * "broken", "untold".
     */
    static Set<String> muted = Set.of();

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
     * What one case says. A case the build ran again after it failed (Surefire's
     * rerunFailingTestsCount) is RETRIED where a rerun passed: it is not one result, and it need
     * not be a test either, as a set-up that failed and passed when run again is reported so too.
     * One that failed in every run is FAILED or ERRORED, as the build counts it: it failed.
     */
    enum Out {
        PASSED("passed"), RETRIED("passed only when the build ran it again"), FAILED("failed"), ERRORED("threw"), SKIPPED("skipped");

        final String word;

        Out(String word) {
            this.word = word;
        }
    }

    private static Out out(JUnitXml.Case c) {
        return switch (c.status()) {
            case PASSED -> c.retried() ? Out.RETRIED : Out.PASSED;
            case FAILED -> Out.FAILED;
            case ERRORED -> Out.ERRORED;
            case SKIPPED -> Out.SKIPPED;
        };
    }

    /** The ways a changed test file is run (see the class comment): after, before, old, and old tests (the fourth way). */
    enum Side {
        AFTER("after the change"), BEFORE("before the change"), OLD("as it was"), OLD_TESTS("as it was, on the code after the change");

        final String words;

        Side(String words) {
            this.words = words;
        }
    }

    /**
     * Every report of the changed test files that the check sees, per way and per file, in the
     * order made: the first run's first. A run that gave no result is kept too (null, or with its
     * problem). Every verdict is decided from all the reports kept here, after the last run,
     * whichever repeat made them: a run made is a run used, and a repeat added later cannot be
     * left out of a decision. A file run alone (see runAlone) is run so before the change in every
     * run, and those runs are its reports before the change; its one run alone after the change
     * only rules out a failure before that comes from another changed test file, and is not kept
     * with the reports after the change, which are of the files run together.
     */
    private static final class Seen {
        private final Map<Side, Map<String, List<FileRun>>> runs = new EnumMap<>(Side.class);

        /** Keeps the reports of a run of these files (null for a file the run gave nothing for). */
        void saw(Side side, Collection<String> files, Map<String, FileRun> got) {
            Map<String, List<FileRun>> of = runs.computeIfAbsent(side, s -> new LinkedHashMap<>());
            for (String f : files) of.computeIfAbsent(f, x -> new ArrayList<>()).add(got.get(f));
        }

        /** Every report of a file run one way, the first run's first. */
        List<FileRun> of(Side side, String file) {
            return runs.getOrDefault(side, Map.of()).getOrDefault(file, List.of());
        }
    }

    /** Runs the edited files as they were, on the code as it was: the third run. Added files need none. */
    private static void earlier(Runner runner, List<String> files, Set<String> edited, Sides sides, int timeout, Seen seen) {
        List<String> had = files.stream().filter(edited::contains).toList();
        Map<String, FileRun> runs = had.isEmpty() ? Map.of() : runner.run(sides.old().dir, had, timeout);
        seen.saw(Side.OLD, had, runs);
    }

    private static boolean muted(String rule) {
        return muted.contains(rule);
    }

    private static List<Verdict> judge(Change change, Runner runner, List<String> files, Set<String> edited, Sides sides, int runs, int timeout) {
        String by = runner.name();
        Scratch head = sides.head(), base = sides.base();
        Map<String, FileRun> after = runner.run(head.dir, files, timeout);
        Map<String, FileRun> before = new LinkedHashMap<>(runner.run(base.dir, files, timeout));
        Seen seen = new Seen(); // every report made from here on (see Seen)
        earlier(runner, files, edited, sides, timeout, seen);
        // One test of new code stops every test of its module from compiling before the change. Those files are
        // then run one at a time, each with the other changed tests put back as the base has them.
        Set<String> alone = new LinkedHashSet<>();
        for (String f : files) if (before.get(f) != null && before.get(f).broken()) alone.add(f);
        if (alone.size() < 2 || alone.size() > MAX_ALONE) alone.clear();
        for (String f : alone) before.put(f, runAlone(change, runner, files, f, base, timeout));
        seen.saw(Side.AFTER, files, after);
        seen.saw(Side.BEFORE, files, before);
        // The first reading. What the first runs say decides which files and tests are run again, and which old tests the fourth run asks
        // about. Nothing is said from it alone: every verdict is decided after the last run, from every report seen (see decide).
        Map<String, Verdict> ended = new LinkedHashMap<>(); // files whose first run after the change gave no result: that is what is said of them
        Map<String, Set<String>> candidates = new LinkedHashMap<>(); // failed before by assertion, passed after: run again to rule out a test that fails at random
        Map<String, Names> named = new LinkedHashMap<>(); // edited files whose first run after the change and first run as it was both gave results, lined up by name
        Map<String, Skips> skipsIn = new LinkedHashMap<>(); // what their skips say in those first runs (see skips)
        Map<String, String> sharedIn = new LinkedHashMap<>();
        Set<String> short1 = new LinkedHashSet<>(); // edited files whose first run after the change runs fewer tests than passed in the first run as it was (see lost)
        Set<String> counted = new LinkedHashSet<>(); // edited files whose first runs say by the counts that new tests pass on both sides (see newTests)
        for (String f : files) {
            FileRun a = after.get(f), b = before.get(f);
            if (!gave(a)) {
                // code that does not compile or load is the change's own failure; a missing build tool, a timeout or a name that cannot be passed on is not
                boolean broken = a != null && a.broken();
                ended.put(f, new Verdict(f, "", broken ? Kind.FAILS_AFTER : Kind.UNKNOWN, (broken ? "after the change it " : "could not be run after the change: ")
                        + (a == null ? "no result" : a.problem()), by, Cover.FILE));
                continue;
            }
            String shared = runner.sharedBySides(change, head.dir, f);
            sharedIn.put(f, shared);
            FileRun o = edited.contains(f) ? seen.of(Side.OLD, f).get(0) : null;
            Map<String, Object> r = read(f, edited.contains(f), o, b, a, shared, shown(gave(o) ? o.cases() : List.of(), a.cases()), by);
            for (Map.Entry<String, Object> x : r.entrySet()) if (x.getValue() instanceof Verdict v && v.kind() == Kind.SENSITIVE) candidates.computeIfAbsent(f, k -> new LinkedHashSet<>()).add(x.getKey());
            if (edited.contains(f) && gave(o)) {
                Names n = names(o.cases(), a.cases());
                named.put(f, n);
                skipsIn.put(f, skips(f, n, by));
                if (count(f, n, r, a, by).least() > 0) counted.add(f);
                // fewer tests run than passed as it was, in the first runs: run again, and as it was on the code after the change (see lost)
                if (passes(o.cases()) > ran(a.cases())) short1.add(f);
            }
        }
        // A test that was run alone before the change may have failed there for want of another changed test file,
        // not for want of the change. It is run alone after the change too, and must pass there as well.
        Set<String> notAlone = new LinkedHashSet<>(); // file and identity: a test that passes after the change only beside the other changed test files
        for (String f : new ArrayList<>(candidates.keySet())) {
            if (!alone.contains(f)) continue;
            FileRun solo = runAlone(change, runner, files, f, head, timeout);
            for (String k : new ArrayList<>(candidates.get(f))) {
                if (status(solo, k) == Out.PASSED) continue;
                candidates.get(f).remove(k);
                notAlone.add(f + "\n" + k);
            }
            if (candidates.get(f).isEmpty()) candidates.remove(f);
        }
        if (!alone.isEmpty()) {
            restore(change, runner, head, files);
            restore(change, runner, base, files);
        }
        List<String> sensitive = new ArrayList<>(candidates.keySet());
        // files whose skips may say a test is switched off, whose counts say tests that ran do not run now or tests are gone, or whose counts say
        // new tests pass on both sides: their old and after runs are made again, as what the counts say must be said by every run
        List<String> switched = new ArrayList<>();
        for (Map.Entry<String, Skips> e : skipsIn.entrySet()) if (!e.getValue().answer().isEmpty() || e.getValue().fewer()) switched.add(e.getKey());
        for (Map.Entry<String, Names> e : named.entrySet()) {
            boolean counts = short1.contains(e.getKey()) || e.getValue().gone().size() > e.getValue().fresh().size() || counted.contains(e.getKey());
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
        // the fourth run, for each edited file whose list of tests changed, and each that runs fewer tests than passed as it was (which a changed
        // list always is, said here so that it cannot be lost): every old test of such a file that passed as it was, at the first try
        Map<String, Set<String>> wanted = new LinkedHashMap<>();
        for (Map.Entry<String, Names> e : named.entrySet()) {
            if (!e.getValue().changed() && !short1.contains(e.getKey())) continue;
            Set<String> keys = new LinkedHashSet<>();
            for (JUnitXml.Case c : seen.of(Side.OLD, e.getKey()).get(0).cases()) if (out(c) == Out.PASSED) keys.add(key(c));
            if (!keys.isEmpty()) wanted.put(e.getKey(), keys);
        }
        Fourth fourth = fourth(change, runner, wanted, short1, sides, runs, timeout, seen);
        if (!alone.isEmpty() && runs > 1 && !sensitive.isEmpty()) restore(change, runner, base, files); // as the caller's other checks expect it
        return decide(files, edited, ended, candidates, notAlone, named, skipsIn, sharedIn, switched, fourth, seen, by);
    }

    /**
     * Every run is made: what is said is decided here, from every report seen (see Seen), and from
     * the first reading only which runs were made.
     *
     * Each case identity of a file is read in every combination of its reports: each run as it
     * was (for an edited file), with each run before the change, with each run after it (see
     * read). A test that failed in any run after the change fails after the change: that is said,
     * whatever the other runs say, with how many runs it failed in. Else a verdict of a test is said
     * only where every combination says the same; a test that failed before and passed after in
     * the first runs is sensitive only where every combination says so, and unstable otherwise;
     * and of any other test that the combinations do not agree on, no verdict is said here: its
     * difference from run to run is then answered by a rule of its file or by reconcile.
     *
     * The order in which verdicts are said is the order of the report: each file's tests, its
     * skips and its own lines first, then the rules of the edited files, then the sensitive tests,
     * and last what no rule answered for (see reconcile).
     */
    private static List<Verdict> decide(List<String> files, Set<String> edited, Map<String, Verdict> ended, Map<String, Set<String>> candidates, Set<String> notAlone,
            Map<String, Names> named, Map<String, Skips> skipsIn, Map<String, String> sharedIn, List<String> switched, Fourth fourth, Seen seen, String by) {
        List<Verdict> out = new ArrayList<>();
        Map<String, Map<String, String>> shownIn = new LinkedHashMap<>();
        Map<String, Set<String>> passingIn = new LinkedHashMap<>(); // per file: the cases that pass on both sides in every combination (see broken)
        Map<String, List<Verdict>> newIn = new LinkedHashMap<>(); // per edited file: what its counts say of its new tests, in every combination (see newTests)
        Map<String, Ledger> ledgers = new LinkedHashMap<>();
        List<Verdict> lone = new ArrayList<>(), last = new ArrayList<>(); // tests that pass after the change only beside other files; the first runs' sensitive tests
        for (String f : files) {
            if (ended.containsKey(f)) {
                out.add(ended.get(f));
                continue;
            }
            boolean ed = edited.contains(f);
            List<FileRun> olds = ed ? seen.of(Side.OLD, f) : java.util.Collections.singletonList(null), befores = seen.of(Side.BEFORE, f), afters = seen.of(Side.AFTER, f);
            Map<String, String> sh = shownAll(seen, f);
            shownIn.put(f, sh);
            Ledger ledger = ledger(f, ed, seen, sh);
            ledgers.put(f, ledger);
            List<Map<String, Object>> reads = new ArrayList<>();
            List<Count> counts = new ArrayList<>();
            for (FileRun o : olds) {
                for (FileRun b : befores) {
                    for (FileRun a : afters) {
                        Map<String, Object> r = gave(a) ? read(f, ed, o, b, a, sharedIn.get(f), sh, by) : Map.of();
                        reads.add(r);
                        if (ed) counts.add(gave(o) && gave(a) ? count(f, names(o.cases(), a.cases()), r, a, by) : null);
                    }
                }
            }
            Set<String> keys = new LinkedHashSet<>();
            for (Map<String, Object> r : reads) keys.addAll(r.keySet());
            Set<String> passing = new LinkedHashSet<>(), freshEvery = new LinkedHashSet<>();
            List<Verdict> mine = new ArrayList<>();
            for (String k : keys) {
                // a test's verdict reads the runs before and after the change for its result, and the runs as it was only for whether its name is new
                Cover c = Cover.of(List.of(k), Side.BEFORE, Side.AFTER);
                Object first = reads.get(0).get(k);
                List<Object> all = reads.stream().map(r -> r.get(k)).toList();
                boolean candidate = first instanceof Verdict v && v.kind() == Kind.SENSITIVE;
                Verdict fails = failsAfter(f, k, afters, sh, by);
                if (fails != null) {
                    (candidate ? last : mine).add(fails.with(c));
                } else if (candidate && notAlone.contains(f + "\n" + k)) {
                    lone.add(new Verdict(f, shown(sh, k), Kind.UNKNOWN, "after the change it passes only together with the other changed test files, so its failure before the change may "
                            + "come from them", by, c));
                } else if (candidate) {
                    if (all.stream().allMatch(x -> x instanceof Verdict v && v.kind() == Kind.SENSITIVE)) last.add(new Verdict(f, shown(sh, k), Kind.SENSITIVE, "", by, c));
                    else if (afters.stream().anyMatch(r -> status(r, k) != Out.PASSED)) last.add(new Verdict(f, shown(sh, k), Kind.UNSTABLE, "passed after the change in one run and not in another", by, c));
                    else last.add(new Verdict(f, shown(sh, k), Kind.UNSTABLE, "failed before the change in one run and not in another", by, c));
                } else if (all.stream().allMatch(x -> java.util.Objects.equals(x, first))) {
                    // the same in every combination; else not said here (see above)
                    if (first == Mark.FRESH) freshEvery.add(k);
                    if (first == Mark.FRESH || first instanceof Verdict v && (v.kind() == Kind.EXISTING_PASSES_ON_BOTH || v.kind() == Kind.PASSES_ON_BOTH)) passing.add(k);
                    if (first instanceof Verdict v) mine.add(v.with(c));
                }
            }
            passingIn.put(f, passing);
            if (!muted("per test")) out.addAll(mine);
            Skips s = skipsIn.get(f);
            // the skips the file reports as before, or of a file none of whose tests ran: only where the file reports each of them alike in every run
            if (s != null && !muted("skips")) for (Verdict v : s.listed()) if (v.cover().change().stream().noneMatch(ledger.unsteady()::contains)) out.add(v);
            if (named.containsKey(f) && !muted("new tests")) newIn.put(f, newTests(f, counts, freshEvery, by));
            FileRun o = ed ? olds.get(0) : null, a = afters.get(0);
            if (ed && !gave(o)) out.add(new Verdict(f, "", Kind.UNKNOWN, "run as it was before the change, the file " + (o == null ? "gave no result" : o.problem())
                    + ", so it cannot be told which of its tests are new, were switched off or were removed", by, Cover.LIST));
            // a file that ran and has no test, and had none: nothing to judge, and it is said so (a file that had tests has lost them: see lost)
            else if (a.cases().isEmpty() && (o == null || o.cases().isEmpty())) out.add(new Verdict(f, "", Kind.UNKNOWN, "the file ran after the change and reports no tests", by, Cover.FILE));
        }
        out.addAll(lone);
        Set<String> unsteady = new LinkedHashSet<>(), recounted = new LinkedHashSet<>(); // files whose skips, and whose counts of gone and fresh cases, said another thing in a repeat run
        for (String f : switched) {
            // the whole decision is made again from the complete reports of each run, on both sides: each report as it was against each after the change
            Names n1 = named.get(f);
            Skips s1 = skipsIn.get(f);
            List<String> first = signature(s1.answer(), s1.fewer(), n1);
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
        Map<String, Long> wavered = new LinkedHashMap<>(); // per file: how many old tests the change broke are said unstable in whether they run now
        for (Map.Entry<String, Names> e : named.entrySet()) {
            String f = e.getKey();
            List<Verdict> b = broken(f, e.getValue(), seen.of(Side.AFTER, f), passingIn.get(f), fourth, said, wavered, judged, by);
            if (!muted("broken")) out.addAll(b);
        }
        if (muted("broken")) {
            said.clear();
            wavered.clear();
            judged.clear();
        }
        // the one rule for tests that ran before and do not run now. spoke: files of which it says something (a row, or that all of it is said
        // above, or by the fourth run's own line), whose skips and gone tests are then not said again; rowed: those of which it says a row of its own;
        // deferred: those whose row the fourth run's own line says (see untold)
        Set<String> spoke = new LinkedHashSet<>(), rowed = new LinkedHashSet<>(), deferred = new LinkedHashSet<>();
        List<Verdict> lostRows = new ArrayList<>();
        for (Map.Entry<String, Names> e : named.entrySet()) {
            String f = e.getKey();
            Set<String> into = muted("lost") ? new LinkedHashSet<>() : spoke;
            List<Verdict> l = lost(f, e.getValue(), seen, fourth, said.getOrDefault(f, 0L), wavered.getOrDefault(f, 0L), into, deferred, by);
            if (muted("lost")) continue;
            if (!l.isEmpty()) rowed.add(f);
            lostRows.addAll(l);
        }
        for (List<Verdict> v : newIn.values()) out.addAll(v);
        out.addAll(lostRows);
        if (!muted("gone")) out.addAll(gone(named, spoke, rowed, said, judged, recounted, seen, by));
        // a file whose skips said nothing in the first run and something in another, where nothing above says that its reports differ from run to run
        for (String f : unsteady) {
            if (muted("skips")) break;
            Skips s = skipsIn.get(f);
            if (spoke.contains(f) || !s.answer().isEmpty() || s.fewer() || out.stream().anyMatch(v -> v.file().equals(f) && v.name().isEmpty() && v.kind() == Kind.UNSTABLE)) continue;
            out.add(new Verdict(f, "", Kind.UNSTABLE, "whether tests that ran before the change are skipped now differs from run to run: what the file reports, after the change or as it "
                    + "was, is not the same in every run", by, Cover.RUNS));
        }
        // what the skips could not judge, where fewer tests run nowhere (else the rule for tests that ran and do not run now said it, above)
        for (Map.Entry<String, Skips> s : skipsIn.entrySet()) {
            String f = s.getKey();
            if (spoke.contains(f) || muted("skips")) continue;
            for (Verdict v : s.getValue().answer()) {
                if (unsteady.contains(f)) out.add(new Verdict(f, "", Kind.UNSTABLE,
                        "whether tests that ran before the change are skipped now differs from run to run: what the file reports, after the change or as it was, is not the same in every run",
                        by, v.tests(), v.cover().and(Cover.RUNS)));
                else out.add(v);
            }
        }
        // the fourth run was asked for these files, and did not judge every old test: said of each such file, whatever else is said of it
        for (Map.Entry<String, Names> e : named.entrySet()) {
            if (fourth.unclear().containsKey(e.getKey()) && !muted("untold")) out.add(fourth.untold(e.getKey(), shownIn.get(e.getKey()), deferred.contains(e.getKey()) ? Cover.LIST : Cover.NONE, by));
        }
        out.addAll(last);
        // every difference among the reports of each file that no verdict above answers for
        for (Map.Entry<String, Ledger> e : ledgers.entrySet()) out.addAll(reconcile(e.getKey(), e.getValue(), out, by));
        return out;
    }

    /** What another rule decides of a case identity in one combination of reports (see read): its skips (see skips), or the counts of new tests (see newTests). */
    private enum Mark { SKIP, FRESH }

    /**
     * What one combination of a file's reports says of each case identity of the run after the
     * change (a): a verdict, or a Mark. o is the run of the file as it was (null for a file the
     * change adds), b the run before the change. This is the reading of one run on each side:
     * what is said is what every combination says (see decide).
     */
    private static Map<String, Object> read(String f, boolean edited, FileRun o, FileRun b, FileRun a, String shared, Map<String, String> sh, String by) {
        Map<String, Object> out = new LinkedHashMap<>();
        Names n = edited && gave(o) ? names(o.cases(), a.cases()) : null;
        Set<String> fresh = new LinkedHashSet<>();
        if (n != null) for (JUnitXml.Case c : n.fresh()) fresh.add(key(c));
        Map<String, JUnitXml.Case> was = new LinkedHashMap<>();
        Set<String> twice = new LinkedHashSet<>(); // two cases under one name, before or after the change: the sides cannot be compared by that name
        if (gave(b)) for (JUnitXml.Case c : b.cases()) if (was.put(key(c), c) != null) twice.add(key(c));
        for (Map.Entry<String, List<JUnitXml.Case>> e : byKey(a.cases()).entrySet()) {
            String k = e.getKey();
            List<JUnitXml.Case> cs = e.getValue();
            String name = shown(sh, k);
            if (cs.size() > 1) twice.add(k);
            // First what the run after the change says alone: two cases of one name on the side before the change do not matter to these.
            if (cs.stream().allMatch(c -> out(c) == Out.SKIPPED)) {
                // a file the change adds, or one whose earlier version gave no result: its skipped tests are listed; else see skips
                out.put(k, n == null ? new Verdict(f, name, Kind.SKIPPED, "skipped after the change", by, cs.size(), Cover.NONE) : Mark.SKIP);
                continue;
            }
            List<JUnitXml.Case> bad = cs.stream().filter(c -> failed(out(c))).toList();
            if (!bad.isEmpty()) {
                out.put(k, new Verdict(f, name, Kind.FAILS_AFTER, describe(bad.get(0)), by, bad.size(), Cover.NONE));
                continue;
            }
            if (cs.stream().anyMatch(c -> out(c) == Out.RETRIED)) {
                out.put(k, new Verdict(f, name, Kind.UNSTABLE, "the build ran it again and got another result", by, Cover.NONE));
                continue;
            }
            // what is left compares the two sides by name, which must then name one test on each side. The same file runs on
            // both sides, so its cases are named alike on both.
            if (twice.contains(k)) {
                out.put(k, new Verdict(f, name, Kind.UNKNOWN, "more than one test reports this name, so they cannot be told apart", by, Cover.NONE));
                continue;
            }
            JUnitXml.Case p = was.get(k);
            Object v;
            if (!gave(b)) v = b != null && b.broken() ? Verdict.beforeFailed(f, name, "before the change the file " + b.problem(), by)
                    : new Verdict(f, name, Kind.UNKNOWN, "before the change the file " + (b == null ? "gave no result" : b.problem()), by, Cover.NONE);
            else if (p == null) v = Verdict.beforeFailed(f, name, "was not found before the change", by);
            else if (out(p) == Out.PASSED && shared != null) v = new Verdict(f, name, Kind.UNKNOWN, "passes before and after, but " + shared, by, Cover.NONE);
            else if (out(p) == Out.PASSED && edited && !gave(o)) v = new Verdict(f, name, Kind.UNKNOWN, "passes before and after, but run as it was before the change, the file "
                    + (o == null ? "gave no result" : o.problem()) + ", so it cannot be told whether this test is new", by, Cover.NONE);
            else if (out(p) == Out.PASSED) v = n == null ? new Verdict(f, name, Kind.PASSES_ON_BOTH, "", by, Cover.NONE)
                    : fresh.contains(k) ? Mark.FRESH : new Verdict(f, name, Kind.EXISTING_PASSES_ON_BOTH, "", by, Cover.NONE);
            else if (out(p) == Out.SKIPPED) v = new Verdict(f, name, Kind.UNKNOWN, "was skipped before the change", by, Cover.NONE);
            else if (out(p) == Out.RETRIED) v = new Verdict(f, name, Kind.UNKNOWN, "before the change the build ran it again and got another result", by, Cover.NONE);
            else if (out(p) == Out.ERRORED) v = Verdict.beforeFailed(f, name, "before the change it threw " + shortType(p) + ", not an assertion", by);
            else v = new Verdict(f, name, Kind.SENSITIVE, "", by, Cover.NONE); // failed before by assertion, passes after: in this combination
            out.put(k, v);
        }
        return out;
    }

    /**
     * A test that failed or threw in a run after the change fails after the change: said, with how
     * many of the runs after the change it failed in where not in all of them. A failure after
     * the change is the build's own verdict on that run (a case that failed every time the build
     * ran it); a case that passed only when the build ran it again passed in that run, and is
     * unstable (see read). Null when it failed in no run.
     */
    private static Verdict failsAfter(String f, String k, List<FileRun> afters, Map<String, String> sh, String by) {
        int failedIn = 0, tests = 0;
        JUnitXml.Case first = null;
        for (FileRun a : afters) {
            if (!gave(a)) continue;
            List<JUnitXml.Case> bad = a.cases().stream().filter(c -> key(c).equals(k) && failed(out(c))).toList();
            if (bad.isEmpty()) continue;
            failedIn++;
            if (first == null) {
                first = bad.get(0);
                tests = bad.size();
            }
        }
        if (first == null) return null;
        return new Verdict(f, shown(sh, k), Kind.FAILS_AFTER, describe(first) + (failedIn < afters.size() ? " (in " + failedIn + " of the " + afters.size() + " runs after the change)" : ""),
                by, tests, Cover.NONE);
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
        String said(String file, Collection<String> keys, Map<String, String> shown) {
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
         * tests could not be judged. It answers for those old tests, and for the whole list where
         * the rule of lost left its line to it (also: see lost).
         */
        Verdict untold(String file, Map<String, String> shown, Cover also, String by) {
            List<String> keys = unclear.get(file);
            FileRun run = first.get(file);
            String problem = run == null ? "gave no result" : run.problem();
            int n = keys.size();
            String which = n == 1 ? "the old test that passed before it (" + shown(shown, keys.get(0)) + ")" : "any of the " + n + " old tests that passed before it ("
                    + shortList(keys.stream().map(k -> shown(shown, k)).toList()) + ")";
            String head = problem != null ? "the old tests could not be run on the code after the change: there the file as it was " + problem
                    : "the old tests could not all be judged on the code after the change: there the file as it was gave no clean result, passed or failed, for "
                            + (n == 1 ? "one" : n) + " of them (not reported, reported more than once, skipped, or run again by the build with another result)";
            // a case the file as it was does not report or skips on the code after the change may be one the change of the code took away (see lost)
            String code = problem != null ? "" : ", or, where it is not reported there, whether the change of the code, not of the tests, took " + (n == 1 ? "it" : "them")
                    + " away, as it does a row of a parameterized test whose source it shortened";
            return new Verdict(file, "", Kind.UNKNOWN, head + ", so it is not known whether the change broke " + which + " and took it out of the way or replaced it" + code, by, n,
                    Cover.change(keys).and(also));
        }
    }

    /** For a test whose old runs passed and failed on the code after the change in some runs only. */
    private static final String WAVERED = "passed before the change, and as the test was, failed on the code after the change in one run and not in another";

    /**
     * The fourth run: the edited test files whose list of tests changed (wanted: per file, the
     * identities of the old tests that passed in the third run at the first try), as they were
     * before the change, on the code after it. An old test that passed in the third run (its file
     * as it was, on the code as it was) and fails or throws here failed on what the change wrote;
     * what became of it decides what is said (see broken). Like a sensitive test it must do so in
     * every run, on both sides, of all the runs of the file as it was the check made (see Seen):
     * else it wavered. With each repeat run the file is also run as the change has it, as what
     * became of the old test must hold in every run too. A file that runs fewer tests than passed
     * as it was (fewer) is run again here with each repeat as well, as how many of its old tests
     * run on the code after the change must hold in every run (see lost).
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
     * cases skipped; and each that runs fewer tests than passed as it was (see lost), which is
     * always one whose list changed. Not only where a test is gone or may be switched off: a file can lose a
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
    private static Fourth fourth(Change change, Runner runner, Map<String, Set<String>> wanted, Set<String> fewer, Sides sides, int runs, int timeout, Seen seen) {
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
                Out s = status(first.get(f), k);
                if (failed(s)) failing.computeIfAbsent(f, x -> new LinkedHashSet<>()).add(k);
                else if (s != Out.PASSED) unclear.computeIfAbsent(f, x -> new ArrayList<>()).add(k);
            }
        }
        List<String> again = new ArrayList<>(failing.keySet());
        // a file that runs fewer tests than passed as it was is run again here too, as how many of its old tests run on the code after the change
        // must hold in every run (see lost); its other ways were run again with the skips
        List<String> counts = files.stream().filter(f -> fewer.contains(f) && !failing.containsKey(f)).toList();
        for (int i = 1; i < runs && !(again.isEmpty() && counts.isEmpty()); i++) {
            if (!again.isEmpty()) {
                seen.saw(Side.OLD, again, runner.run(sides.old().dir, again, timeout));
                seen.saw(Side.OLD_TESTS, again, runner.run(mixed.dir, again, timeout));
                seen.saw(Side.AFTER, again, runner.run(sides.head().dir, again, timeout));
            }
            if (!counts.isEmpty()) seen.saw(Side.OLD_TESTS, counts, runner.run(mixed.dir, counts, timeout));
        }
        // broken: passed in every run of the file as it was and failed or threw in every run of it on the change, of all the runs seen
        for (String f : again) {
            for (String k : failing.get(f)) {
                boolean every = seen.of(Side.OLD, f).stream().allMatch(r -> status(r, k) == Out.PASSED) && seen.of(Side.OLD_TESTS, f).stream().allMatch(r -> failed(status(r, k)));
                (every ? broke : wavering).computeIfAbsent(f, x -> new LinkedHashSet<>()).add(k);
            }
        }
        return new Fourth(first, broke, wavering, unclear);
    }

    private static boolean failed(Out s) {
        return s == Out.FAILED || s == Out.ERRORED;
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
            return fresh.stream().filter(c -> out(c) == Out.SKIPPED).toList();
        }

        /** True when the file's list of tests changed: a name gone or fresh, or a name with more or fewer of its cases skipped (see fourth). */
        boolean changed() {
            return !gone.isEmpty() || !fresh.isEmpty() || !off.isEmpty() || on > 0;
        }

        /** The identities of the names gone and fresh. */
        Set<String> moved() {
            Set<String> out = new LinkedHashSet<>();
            for (JUnitXml.Case c : gone) out.add(key(c));
            for (JUnitXml.Case c : fresh) out.add(key(c));
            return out;
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
        return cases.stream().filter(c -> out(c) != Out.SKIPPED).count();
    }

    /**
     * What an edited file's skipped cases say. answer: what could not be judged of a test that
     * ran before and may not run now, to be run again before it is said; listed: the rest. fewer:
     * fewer cases run and more are skipped, and no report holds a failure: what the rule for tests
     * that ran and do not run now decides (see lost), so the skips say nothing of it themselves.
     */
    private record Skips(List<Verdict> answer, List<Verdict> listed, boolean fewer) {}

    /**
     * The skips of an edited file, from its old run and its run after the change (n):
     *
     *   1. No name ran before and is skipped now (no off flip), and no case is skipped under a name
     *      the file did not report before, or none of its tests ran before: nothing can have been
     *      switched off. A case skipped on both sides under one name is skipped as before; a case
     *      skipped under a new name, in a file none of whose tests ran, is a skipped test (listed).
     *   2. Fewer cases run and more are skipped (r1 < r0, s1 > s0), whatever the names: tests that
     *      ran do not run now, which the rule of lost decides from every run (fewer). Where a
     *      report holds a failure, a case that failed or threw need not be a test that ran, and a
     *      failure after the change can skip the tests after it (see listing): could not be
     *      judged, where the rule of lost says nothing of the file (as when the tests that passed
     *      as it was all run now, and only the case that failed is skipped now).
     *   3. Anything else in which a test that ran may be skipped now: one could not be judged for
     *      the file, with the counts and the names, and the two stories they fit. Where fewer
     *      tests run than passed as it was, the rule of lost says so instead.
     *
     * Even where the file reports the very same names as before and the only change is off flips,
     * a name does not say which test was switched off: where tests are named by their place, two
     * rows that change places while the one moved to the front is switched off, [a, b] to [b
     * skipped, a], give one off flip, at the first name, while a still runs under the second. So
     * the names are said as what the reports show, never as the test switched off.
     *
     * What each answers for: a skip as before, or a skipped test listed, its own name, and the
     * runs of it as it was and after the change (it is said only where they agree, see decide);
     * a line that could not be judged, the names it shows.
     */
    private static Skips skips(String f, Names n, String by) {
        List<Verdict> answer = new ArrayList<>(), listed = new ArrayList<>();
        for (Flip s : n.asBefore()) listed.add(new Verdict(f, s.name(), Kind.EXISTING_SKIPPED, "skipped before the change too", by, (int) s.tests(), Cover.of(List.of(s.key()), Side.OLD, Side.AFTER)));
        List<JUnitXml.Case> freshOff = n.freshSkipped();
        if (n.off().isEmpty() && (freshOff.isEmpty() || n.r0() == 0)) {
            for (JUnitXml.Case c : freshOff) listed.add(new Verdict(f, shown(n.shown(), key(c)), Kind.SKIPPED, "skipped after the change; no test of the file ran before it", by,
                    Cover.of(List.of(key(c)), Side.OLD, Side.AFTER)));
            return new Skips(answer, listed, false);
        }
        Set<String> shows = new LinkedHashSet<>();
        for (Flip s : n.off()) shows.add(s.key());
        for (JUnitXml.Case c : freshOff) shows.add(key(c));
        Cover cover = Cover.change(shows);
        String counts = counts(n);
        if (n.r1() < n.r0() && n.s1() > n.s0()) {
            long least = Math.min(n.r0() - n.r1(), n.s1() - n.s0());
            // a case that failed or threw, under any name, need not be a test that ran, nor say why tests after it are skipped (see listing)
            if (n.listing().failures() != null) answer.add(new Verdict(f, "", Kind.UNKNOWN, counts + skippedNow(n) + ". But " + n.listing().failures()
                    + ": whether a test that ran does not run now is not known", by, (int) least, cover));
            return new Skips(answer, listed, n.listing().failures() == null);
        }
        String story;
        if (n.s1() > n.s0()) story = ". So more are skipped while as many or more run: it cannot be told whether skipped tests were added, or a test that ran was switched off and another added";
        else if (!n.off().isEmpty()) story = ", and its list of cases changed as well (" + n.fresh().size() + " names it did not report, " + n.gone().size() + " it no longer reports, "
                + n.on() + " skipped before and run now). It cannot be told whether a test that ran was switched off, or cases changed names, as cases named by their place "
                + "do when a row is put in or taken out before them";
        else story = ". The file had tests that ran, so it cannot be told whether " + (freshOff.size() == 1 ? "this is a new test that is skipped, or a test" : "these are new tests that are skipped, or tests")
                + " that ran before under another name and " + (freshOff.size() == 1 ? "is" : "are") + " switched off";
        long candidates = n.off().stream().mapToLong(Flip::tests).sum() + freshOff.size();
        answer.add(new Verdict(f, "", Kind.UNKNOWN, counts + skippedNow(n) + story, by, (int) candidates, cover));
        return new Skips(answer, listed, false);
    }

    /** "the file runs 3 of its tests where it ran 4 before the change, and skips 2 where it skipped 1". */
    private static String counts(Names n) {
        return "the file runs " + n.r1() + " of its tests where it ran " + n.r0() + " before the change, and skips " + n.s1() + " where it skipped " + n.s0();
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
        if (!gave(old) || !gave(after)) return null;
        Names n = names(old.cases(), after.cases());
        Skips s = skips(f, n, by);
        return signature(s.answer(), s.fewer(), n);
    }

    /**
     * What a repeat run must say again: each verdict of the skips (kind, count and words), each
     * name that ran before and is skipped now and each case skipped under a name the file did not
     * report, by its identity (suite and name: two nested classes may each have a test of one
     * name), and last, how many cases are gone and fresh (see gone). So a run in which another
     * test is skipped is another answer, though the counts be the same.
     */
    private static List<String> signature(List<Verdict> answer, boolean fewer, Names n) {
        List<String> out = new ArrayList<>(answer.stream().map(v -> v.kind() + "\n" + v.tests() + "\n" + v.detail()).toList());
        if (fewer) out.add("fewer run, more skipped\n" + counts(n));
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

    /** How the cases of a file are shown, from every report of it as it was and after the change. */
    private static Map<String, String> shownAll(Seen seen, String f) {
        List<JUnitXml.Case> old = new ArrayList<>(), now = new ArrayList<>();
        for (FileRun r : seen.of(Side.OLD, f)) if (gave(r)) old.addAll(r.cases());
        for (FileRun r : seen.of(Side.AFTER, f)) if (gave(r)) now.addAll(r.cases());
        for (FileRun r : seen.of(Side.OLD_TESTS, f)) if (gave(r)) old.addAll(r.cases());
        return shown(old, now);
    }

    /** How a case is shown, by its identity (see shown). */
    private static String shown(Map<String, String> shown, String key) {
        return shown.getOrDefault(key, name(key));
    }

    /** How these cases are shown (see shown). */
    private static List<String> shown(Map<String, String> shown, List<JUnitXml.Case> cases) {
        return cases.stream().map(c -> shown(shown, key(c))).toList();
    }

    /** What one combination of reports says of an edited file's new tests (see newTests): the names, the cases under new names that pass on both sides, X and the least number. */
    private record Count(Names n, List<JUnitXml.Case> passBoth, long other, long least) {}

    /** The count of one combination (see newTests), from what it says of each case (read). */
    private static Count count(String f, Names n, Map<String, Object> read, FileRun a, String by) {
        List<JUnitXml.Case> passBoth = new ArrayList<>(); // cases under a name the file did not report before that pass on both sides
        long clean = 0; // cases that pass on both sides, under any name
        for (JUnitXml.Case c : a.cases()) {
            Object x = read.get(key(c));
            if (x == Mark.FRESH) {
                clean++;
                passBoth.add(c);
            } else if (x instanceof Verdict v && v.kind() == Kind.EXISTING_PASSES_ON_BOTH) clean++;
        }
        Skips s = skips(f, n, by);
        // the cases that do not pass on both sides; of the skipped ones, those under a name skipped before too only where a skip may hide a test
        long asBefore = n.asBefore().stream().mapToLong(Flip::tests).sum();
        long other = a.cases().size() - clean - (s.answer().isEmpty() && !s.fewer() ? asBefore : 0);
        long least = n.listing().old() != null ? 0 : Math.max(0, n.fresh().size() - n.gone().size() - other);
        return new Count(n, passBoth, other, least);
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
     * test at all, a case the build ran again, or a case skipped there that is not skipped as
     * often after the change), a test it hid may come out under a new name, and F - G does not
     * count tests the file did not have: N is taken to be 0, and the reason is said. The report
     * after the change need not list every test: a test it hides does not pass on both sides, a
     * case of it that stands for more tests than one, or for none, is one of the X, and old
     * tests that one case after the change stands for take fewer cases after the change, not more.
     *
     * Every run: F, G, X, the skips and whether the report as it was lists every test are counted
     * again in every combination of the runs made (counts: each run as it was with each run
     * before and each run after the change; null where one of them gave no result). N > 0 in every
     * one: at least the fewest of them, said, with the counts of that combination. N > 0 in some
     * only: unstable. N = 0 in every one: each case under a new name that passes on both sides in
     * every combination (freshEvery) could not be judged; one that does not is not the same in
     * every run, and is answered as that (see reconcile).
     */
    private static List<Verdict> newTests(String f, List<Count> counts, Set<String> freshEvery, String by) {
        if (counts.stream().allMatch(c -> c == null || c.passBoth().isEmpty())) return List.of();
        Set<String> moved = new LinkedHashSet<>();
        for (Count c : counts) if (c != null) moved.addAll(c.n().moved());
        Cover cover = Cover.change(moved);
        long min = counts.stream().mapToLong(c -> c == null ? 0 : c.least()).min().orElse(0), max = counts.stream().mapToLong(c -> c == null ? 0 : c.least()).max().orElse(0);
        if (min > 0) {
            Count at = counts.stream().filter(c -> c.least() == min).findFirst().orElseThrow();
            return List.of(say(f, at, max > min ? " (of every run, the one that gives the fewest)" : "", cover, by).get(0));
        }
        if (max > 0) {
            Count at = counts.stream().filter(c -> c != null && c.least() > 0).findFirst().orElseThrow();
            Verdict v = say(f, at, "", cover, by).get(0);
            return List.of(new Verdict(f, "", Kind.UNSTABLE, v.detail().substring(0, v.detail().indexOf(". Under names"))
                    + ", but what the file reports, after the change or as it was, is not the same in every run", by, v.tests(), cover.and(Cover.RUNS)));
        }
        Count at = counts.stream().filter(c -> c != null && !c.passBoth().isEmpty()).findFirst().orElseThrow();
        return say(f, at, "", cover, by).stream().filter(v -> freshEvery.stream().anyMatch(k -> v.cover().change().contains(k))).toList();
    }

    /** What one combination's count says (see newTests): the file's line where N > 0, else a line for each case under a new name that passes on both sides. */
    private static List<Verdict> say(String f, Count c, String which, Cover cover, String by) {
        Names n = c.n();
        List<JUnitXml.Case> passBoth = c.passBoth();
        long other = c.other(), p = passBoth.size(), g = n.gone().size(), fr = n.fresh().size(), least = c.least();
        List<Verdict> out = new ArrayList<>();
        String counts = "the file reports " + tests(fr) + " it did not have" + (g > 0 ? " and no longer reports " + g + " it had" : "")
                + (other > 0 ? ", and " + other + " of its tests " + (other == 1 ? "does" : "do") + " not pass on both sides" : "");
        if (least > 0) {
            out.add(new Verdict(f, "", Kind.PASSES_ON_BOTH, counts + which + ": at least " + least + (least == 1 ? " new test passes" : " new tests pass") + " before and after. Under names "
                    + "the file did not have, " + p + (p == 1 ? " passes" : " pass") + " before and after (" + shortList(shown(n.shown(), passBoth)) + "); the names do not say which "
                    + (least == 1 ? "test is" : "tests are") + " new, as where tests are named by their place a row put in before the others moves an old one to a name the file did not have",
                    by, (int) least, cover));
            return out;
        }
        List<String> why = new ArrayList<>();
        if (g > 0) why.add(g == 1 ? "the test it no longer reports, under a new name" : "one of the " + g + " tests it no longer reports, under a new name");
        if (other > 0) why.add("a test moved to this name by one put in before it, where cases are named by their place (the new "
                + (fr - g == 1 ? "test" : "tests") + " then being among the " + other + " that do not pass on both sides)");
        if (n.listing().old() != null) why.add("a test the file had that its report as it was did not list: " + n.listing().old());
        List<String> names = shown(n.shown(), passBoth);
        for (int i = 0; i < passBoth.size(); i++) {
            out.add(new Verdict(f, names.get(i), Kind.UNKNOWN, "passes before and after, but it cannot be told whether it is a new test: " + counts
                    + ". It may be a new test that does not test the change, or a test the file had: " + String.join(", or ", why), by,
                    Cover.of(List.of(key(passBoth.get(i))), Side.BEFORE, Side.AFTER)));
        }
        return out;
    }

    /**
     * The one definite rule for lost tests: how many tests that ran before the change do not run
     * now. Per edited file, from every report the check made of it (see Seen):
     *
     *   r0  the cases that passed in a run of the file as it was, on the code as it was, at the
     *       first try
     *   r1  the cases that ran in a run after the change: any result but skipped
     *
     * Each case that passed at the first try is one test that ran: a set-up, a container or a
     * loading failure is never reported as passed. A test that runs now gives a case that is not
     * skipped, whatever its result, and a test hidden in a skipped case or behind an empty report
     * does not run. So r0 is never more than the tests that ran as it was, r1 never fewer than
     * the tests that run now, and at least r0 - r1 tests that ran before the change do not run
     * now: whatever is skipped, hidden, renamed or emptied, under any naming, and with no list of
     * the tests after the change. Whether they were deleted or switched off does not matter: they
     * ran, and they do not run now. A case that fails after the change counts in r1: it is said as
     * failing, not again as a test that does not run. This is the one thing the rule claims; it
     * never says which change, of the tests or of the code, took them.
     *
     * A case that passed only when the build ran it again is not counted in r0: a set-up that
     * failed and passed when run again (Surefire runs a class whose set-up failed again) is
     * reported so too, and it is not a test. Where only such cases make the count, so that more
     * cases passed as it was than run now only with them, it could not be judged, with why: a
     * flaky test switched off reads so, and so does a flaky set-up that stopped failing.
     *
     * The fourth run decides how sure the line is. The change of the code can take tests away
     * too (a row of a parameterized test whose enum the change shortened), and that is ordinary
     * work. r4 is how many cases run in a run of the file as it was on the code after the change
     * (the fourth run, see fourth), less those that passed only when run again: on that code,
     * if the change of the tests took nothing, the file as the change has it runs every test the
     * file as it was runs, so r1 is at least r4 (a count, true under any naming). So:
     *
     *   r4 > r1 in every pair of runs: the change of the tests itself runs fewer tests on the same
     *   code. Said, definite, with r4 where it is below r0 (the code may have taken some too).
     *   r4 <= r1 in some pair: on the code after the change the file as it was runs no more tests
     *   than it does now, so the change of the code may have taken all of them: could not be
     *   judged, with the counts. Where the fourth run's own line says what it did not report
     *   (see untold), that line says it, and this rule adds no line of its own.
     *   No usable fourth run (the old file does not compile or load on the code after the change,
     *   timed out, or a failure stands in place of its tests: a case that failed or threw that is
     *   not one of its old tests): definite, with a sentence that says the old file could not be
     *   run on the changed code and so whether the change of the tests or of the code took them
     *   is not known. This is a choice: that tests that ran do not run now is true either way,
     *   and a person must look; the alternative, calling it could not be judged, would let any
     *   change that also breaks the old file (a rename the tests had to follow) delete tests
     *   without failing.
     *
     * It must hold in every run: r0 - r1 > 0 for every pair (a run as it was with a run after the
     * change), and r4 > r1 for every pair of a fourth run and a run after the change. Where fewer
     * tests run than passed as it was in some pairs only, it is unstable. Where a run as it was
     * holds a case that failed or threw, nothing is said definite: the owner's choice, as a file
     * that did not pass as it was may be under repair, and a failure there may stand for a fault
     * whose tests are reported beside it; could not be judged, with the counts and the reason.
     *
     * The budget: old tests said to be taken out of the way (t, see broken) ran before and do not
     * run now too, so the row says at least r0 - r1 - t more; nothing else is taken off. An old
     * test the change broke that runs in some runs only is said unstable there (u), so the rule is
     * unstable only beyond t + u. Where all of it is said that way, it says no row, and spoke
     * still holds the file: the skips and the gone tests then say nothing of it either.
     *
     * One row for the file: with the counts, and what the reports show (names no longer
     * reported, names skipped now, names reported for the first time), never one name as the
     * test lost. It is said from every run of the file, so it answers for every difference of the
     * file's reports (see reconcile). The guide lists what moves the count: two tests merged into
     * one is one test that ran and does not run now, and is said so.
     */
    private static List<Verdict> lost(String f, Names n, Seen seen, Fourth fourth, long t, long u, Set<String> spoke, Set<String> deferred, String by) {
        List<FileRun> olds = seen.of(Side.OLD, f), afters = seen.of(Side.AFTER, f);
        List<FileRun> okOld = olds.stream().filter(Witness::gave).toList(), okAfter = afters.stream().filter(Witness::gave).toList();
        if (okOld.isEmpty() || okAfter.isEmpty()) return List.of();
        boolean whole = okOld.size() == olds.size() && okAfter.size() == afters.size();
        long minR0 = okOld.stream().mapToLong(r -> passed(r.cases())).min().orElse(0), maxR0 = okOld.stream().mapToLong(r -> passed(r.cases())).max().orElse(0);
        long minP0 = okOld.stream().mapToLong(r -> passes(r.cases())).min().orElse(0), maxP0 = okOld.stream().mapToLong(r -> passes(r.cases())).max().orElse(0);
        long minR1 = okAfter.stream().mapToLong(r -> ran(r.cases())).min().orElse(0), maxR1 = okAfter.stream().mapToLong(r -> ran(r.cases())).max().orElse(0);
        long most = maxP0 - minR1, total = minR0 - maxR1, withAgain = minP0 - maxR1;
        if (most <= 0) return List.of(); // in no run do fewer tests run than passed as it was
        spoke.add(f);
        String counts = "the file as it was passed " + minR0 + " of its tests, and after the change it runs " + maxR1
                + (maxR0 != minR0 || maxR1 != minR1 ? " (of every run as it was the one that passed the fewest, and of every run after the change the one that runs the most)" : "");
        String shows = shows(n);
        if (okOld.stream().anyMatch(r -> failing(r.cases()))) {
            long first = Math.max(1, passed(olds.get(0).cases()) - ran(afters.get(0).cases()));
            return List.of(new Verdict(f, "", Kind.UNKNOWN, counts + shows + ". But the file as it was reported failures" + FAILURES + ": a file that did not pass as it was is not "
                    + "said to have lost tests, so whether a test that ran does not run now is not known", by, (int) first, Cover.LIST));
        }
        // the fourth run, at its fewest, where usable
        Set<String> oldKeys = new LinkedHashSet<>();
        for (FileRun r : okOld) for (JUnitXml.Case c : r.cases()) if (out(c) == Out.PASSED || out(c) == Out.RETRIED) oldKeys.add(key(c));
        List<FileRun> fourths = seen.of(Side.OLD_TESTS, f);
        String why4 = null;
        long r4 = Long.MAX_VALUE;
        if (fourths.isEmpty()) {
            FileRun set = fourth.first().get(f);
            why4 = set != null && set.problem() != null ? set.problem() : "was not run, as the first runs lost no test";
        }
        for (FileRun r : fourths) {
            if (!gave(r)) {
                why4 = r == null ? "gave no result" : r.problem();
                break;
            }
            List<String> stand = r.cases().stream().filter(c -> failed(out(c)) && !oldKeys.contains(key(c))).map(c -> shown(n.shown(), key(c))).toList();
            if (!stand.isEmpty()) {
                why4 = "reported a failure that is not one of its old tests (" + shortList(stand) + ")" + FAILURES;
                break;
            }
            r4 = Math.min(r4, r.cases().stream().filter(c -> out(c) != Out.SKIPPED && out(c) != Out.RETRIED).count());
        }
        String t4 = t > 0 ? ". At most " + t + (t == 1 ? " of them is an old test" : " of them are old tests") + " said above to be taken out of the way, so at least " + (total - t) + " more" : "";
        if (whole && total - t > 0) {
            String head = counts + ": at least " + total + (total == 1 ? " test that ran before the change does" : " tests that ran before the change do") + " not run now" + shows;
            if (why4 == null && r4 <= maxR1) {
                // on the code after the change the file as it was runs no more tests than it does now: the change of the code may have taken all of them
                if (fourth.unclear().containsKey(f)) {
                    deferred.add(f);
                    return List.of();
                }
                return List.of(new Verdict(f, "", Kind.UNKNOWN, head + ". But as it was, on the code after the change, the file runs " + r4 + ", no more than it runs now, so the change "
                        + "of the code, not of the tests, may have taken " + (total == 1 ? "it" : "them") + t4, by, (int) (total - t), Cover.FILE));
            }
            StringBuilder d = new StringBuilder(head);
            if (most > total) d.append(", and up to ").append(most).append(" in some runs: what the file reports is not the same in every run");
            if (why4 == null && r4 < maxR0) d.append(". As it was, on the code after the change, the file runs ").append(r4).append(", and as the change has it ").append(maxR1)
                    .append(": the change of the code may have taken some of them, and on that code the change of the tests runs fewer tests than the file as it was");
            if (why4 != null) d.append(". The file as it was could not be run on the code after the change (there it ").append(why4)
                    .append("), so whether the change of the tests or of the code took them is not known; either way they ran before the change and do not run now");
            d.append(t4);
            if (okAfter.stream().anyMatch(r -> failing(r.cases()))) d.append(". After the change the file reports failures, and a build that stops after a failure "
                    + "(Surefire's skipAfterFailureCount) skips the tests after it, so some of them may not run for that");
            return List.of(new Verdict(f, "", Kind.NOT_RUN_NOW, d.toString(), by, (int) (total - t), Cover.FILE));
        }
        if (whole && withAgain - t > 0) {
            // fewer run than passed as it was in every run only where the cases that passed when the build ran them again count
            return List.of(new Verdict(f, "", Kind.UNKNOWN, counts + ", and passed " + (minP0 - minR0) + " more only when the build ran " + (minP0 - minR0 == 1 ? "it" : "them") + " again"
                    + shows + ": a case that passed only when run again may be a set-up that failed and then passed (Surefire runs a class whose set-up failed again), not a test, "
                    + "so whether a test that ran does not run now is not known", by, (int) (withAgain - t), Cover.FILE));
        }
        // fewer run than passed as it was in some runs only, beyond the old tests said: unstable
        if (!whole || most - t - u > 0) {
            String which = !whole ? ", and a run of the file " + (okOld.size() < olds.size() ? "as it was" : "after the change") + " gave no result" : "";
            String ranges = "the file as it was passed " + range(minR0, maxR0) + " of its tests, and after the change it runs " + range(minR1, maxR1)
                    + (minR0 != maxR0 || minR1 != maxR1 ? ", from run to run" : "");
            return List.of(new Verdict(f, "", Kind.UNSTABLE, ranges + shows + which + ": whether tests that ran before the change do not run now is not the same in every run: "
                    + "what the file reports, after the change or as it was, differs from run to run", by, (int) Math.max(1, most - t - u), Cover.FILE));
        }
        // all of it is said above as old tests taken out of the way or unstable
        return List.of();
    }

    /** " (no longer reported: a, b; skipped now under a name that ran before: c; reported for the first time: d)": what the first reports show. */
    private static String shows(Names n) {
        List<String> parts = new ArrayList<>();
        if (!n.gone().isEmpty()) parts.add("no longer reported: " + shortList(shown(n.shown(), n.gone())));
        if (!n.off().isEmpty()) parts.add("skipped now under " + (n.off().size() == 1 ? "a name" : "names") + " that ran before: " + shortList(n.off().stream().map(Flip::name).toList()));
        List<JUnitXml.Case> ran = n.fresh().stream().filter(c -> out(c) != Out.SKIPPED).toList(), off = n.freshSkipped();
        if (!ran.isEmpty()) parts.add("reported for the first time: " + shortList(shown(n.shown(), ran)));
        if (!off.isEmpty()) parts.add("skipped under " + (off.size() == 1 ? "a name" : "names") + " the file did not report before: " + shortList(shown(n.shown(), off)));
        return parts.isEmpty() ? "" : " (" + String.join("; ", parts) + ")";
    }

    /** "3", or "2 to 3". */
    private static String range(long least, long most) {
        return least == most ? String.valueOf(least) : least + " to " + most;
    }

    /** True when a run gave a report. */
    private static boolean gave(FileRun r) {
        return r != null && r.problem() == null;
    }

    /** How many of these cases passed at the first try. */
    private static long passed(List<JUnitXml.Case> cases) {
        return cases.stream().filter(c -> out(c) == Out.PASSED).count();
    }

    /** How many of these cases passed, at the first try or when the build ran them again. */
    private static long passes(List<JUnitXml.Case> cases) {
        return cases.stream().filter(c -> out(c) == Out.PASSED || out(c) == Out.RETRIED).count();
    }

    /**
     * What the reports say of the cases an edited file reported before the change and does not
     * report now, where the rule of lost (see lost) said no row of the file. A report says which
     * names a file has, not whether a gone test was deleted or renamed, so a name gone is never
     * said to be a test removed: what ran and does not run now is counted by lost. With G gone
     * and F fresh (see names):
     *
     *   G > F   the file reports fewer cases than it did. Where lost found fewer tests running
     *           than passed as it was and all of it is said by the old tests taken out of the way
     *           (spoke), nothing more. Else as many tests run as passed as it was, in every run,
     *           so the cases gone did not pass as it was: skipped cases, which may have stood for
     *           any number of tests or none, or cases of a failure, which may have stood for a
     *           fault (see listing). Could not be judged, with the counts and the reason (a
     *           skipped test deleted reads so); unstable where the counts of gone and fresh cases
     *           differ from run to run.
     *   G <= F  could not be judged: a rename looks exactly like a test deleted and another
     *           written. Not said where every name gone is an old test already judged.
     *
     * Where lost said a row of the file (rowed), that row says what the reports show, these names
     * among it, and nothing is said here. Each line answers for the names gone and fresh it shows.
     */
    private static List<Verdict> gone(Map<String, Names> named, Set<String> spoke, Set<String> rowed, Map<String, Long> said, Set<String> judged, Set<String> recounted, Seen seen,
            String by) {
        List<Verdict> out = new ArrayList<>();
        for (Map.Entry<String, Names> e : named.entrySet()) {
            String f = e.getKey();
            List<JUnitXml.Case> lost = e.getValue().gone(), fresh = e.getValue().fresh();
            int g = lost.size(), nf = fresh.size();
            if (g == 0 || rowed.contains(f) || g > nf && spoke.contains(f)) continue;
            Cover cover = Cover.change(e.getValue().moved());
            if (g > nf && recounted.contains(f)) {
                out.add(new Verdict(f, "", Kind.UNSTABLE, had(e.getValue()) + ", but what the file reports, after the change or as it was, is not the same in every run", by, g - nf,
                        cover.and(Cover.RUNS)));
            } else if (g > nf) {
                String why = e.getValue().listing().either();
                long r0 = passed(seen.of(Side.OLD, f).get(0).cases()), r1 = ran(seen.of(Side.AFTER, f).get(0).cases());
                out.add(new Verdict(f, "", Kind.UNKNOWN, had(e.getValue()) + ". As many tests run as passed as it was (" + r1 + " where " + r0 + " passed), so no test that ran is "
                        + "lost by count" + (why == null ? "" : ". But " + why) + ": whether tests that did not pass as it was were removed is not known", by, g - nf, cover));
            } else if (!lost.stream().allMatch(c -> judged.contains(f + "\n" + key(c)))) {
                out.add(new Verdict(f, "", Kind.UNKNOWN, had(e.getValue()) + ". The reports cannot tell a test renamed from a test deleted and another written", by, cover));
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
     *   not be judged. Where the file runs fewer tests than passed before, the counts already
     *   fail it (see lost), so nothing is lost by not accusing this name of what they prove.
     *
     * Where a run after the change has a case that failed or threw, its report may not list every
     * test (see listing), so it shows neither that an old name is gone nor how many cases can be
     * a rewrite: could not be judged, with the reason. The failure is said as failing anyway.
     *
     * All of this must hold in every run (runs: every report of the file as the change has it that
     * the check made, the first one first: the first run, the repeats of the sensitive tests and of
     * the skips, and those of the fourth run; see Seen): an old test said gone or skipped is gone or
     * skipped in each of them, M is the most cases that can be a rewrite in any one of them (a
     * case passes on both sides only where it does so in every combination, see decide), and
     * none holds a failure. An old test that is gone or skipped in one run and runs in another, or
     * of which a run gave no result, is unstable: whether the change took it out of the way is not
     * the same in every run.
     *
     * An old test of which the runs of the file as it was disagree, passing before and failing on
     * the change in some runs only, is unstable, whatever became of its name: gone, skipped or
     * still running (else a test whose name still runs would be said nowhere). How many old tests are said
     * to be taken out of the way goes into said, so that the count of tests that ran and do not
     * run now is not said of them again (see budget), and how many are unstable in whether they
     * run now into wavered; the names said broken, and the unstable ones, into judged. Each line
     * answers for the old tests it names, in every way it read them.
     *
     * The budget. The old tests taken out of the way (t of them said) and the tests that ran and
     * do not run now (see lost) may be the same tests: one broken test deleted is both. So the
     * count of tests that ran and do not run now is said only beyond t, and all said comes to
     * the larger of the two, never more than the tests that no longer run.
     */
    private static List<Verdict> broken(String f, Names n, List<FileRun> runs, Set<String> passing, Fourth fourth, Map<String, Long> said, Map<String, Long> wavered,
            Set<String> judged, String by) {
        List<Verdict> out = new ArrayList<>();
        FileRun a = runs.get(0); // the first run after the change, which gave a result (see named)
        Map<String, List<JUnitXml.Case>> now = byKey(a.cases());
        List<String> out1 = new ArrayList<>(), running = new ArrayList<>(), unsure = new ArrayList<>(); // the old tests broken: not run now, run now, and not the same in every run
        for (String k : fourth.broke().getOrDefault(f, Set.of())) {
            boolean gone = notRun(a, k);
            if (runs.stream().anyMatch(r -> !gave(r) || notRun(r, k) != gone)) unsure.add(k);
            else if (gone) out1.add(k);
            else running.add(k);
        }
        List<JUnitXml.Case> rewrites = List.of();
        for (FileRun r : runs) {
            // the most cases that can be a rewrite in any one run
            List<JUnitXml.Case> can = !gave(r) ? List.of() : r.cases().stream().filter(c -> (out(c) == Out.PASSED || out(c) == Out.RETRIED) && !passing.contains(key(c))).toList();
            if (can.size() > rewrites.size()) rewrites = can;
        }
        Side[] read = {Side.OLD, Side.AFTER, Side.OLD_TESTS};
        for (String k : unsure) {
            out.add(new Verdict(f, shown(n.shown(), k), Kind.UNSTABLE, fourth.said(f, List.of(k), n.shown()) + ", and the change skips it, or the file does not report it, in one run "
                    + "after the change and runs it in another", by, Cover.of(List.of(k), read)));
            judged.add(f + "\n" + k);
        }
        if (!unsure.isEmpty()) wavered.put(f, (long) unsure.size());
        for (String k : fourth.wavering().getOrDefault(f, Set.of())) {
            // whatever became of it: a run that did not say what the others said is said, gone, skipped or running
            out.add(new Verdict(f, shown(n.shown(), k), Kind.UNSTABLE, WAVERED, by, Cover.of(List.of(k), Side.OLD, Side.OLD_TESTS)));
            judged.add(f + "\n" + k);
        }
        long b = out1.size(), m = rewrites.size();
        Cover gone = Cover.of(out1, read);
        // a report after the change that may not list every test cannot show that no case can be a rewrite (see listing)
        String unlisted = runs.stream().anyMatch(r -> gave(r) && failing(r.cases())) ? "after the change the file reports failures" + FAILURES + ", so that report may not list every test" : null;
        if (b > 0 && unlisted != null) {
            out.add(new Verdict(f, "", Kind.UNKNOWN, shortList(out1.stream().map(k -> shown(n.shown(), k)).toList()) + " passed before the change and " + (b == 1 ? "fails" : "fail")
                    + " on the code after it as the file was, and the file does not report " + (b == 1 ? "it now or skips it" : "them now or skips them") + ". But " + unlisted
                    + ": whether the change took " + (b == 1 ? "it" : "them") + " out of the way is not known", by, (int) b, gone));
        } else if (b > 0 && m == 0) {
            for (String k : out1) {
                out.add(new Verdict(f, shown(n.shown(), k), Kind.OLD_FAILS_AFTER, fourth.said(f, List.of(k), n.shown()) + (now.containsKey(k) ? ", and the change skips it now" : ". The edited file does not report it now"),
                        by, Cover.of(List.of(k), read)));
                judged.add(f + "\n" + k);
            }
            said.put(f, b);
        } else if (b > 0) {
            String what = shortList(out1.stream().map(k -> shown(n.shown(), k)).toList()) + " passed before the change and " + (b == 1 ? "fails" : "fail")
                    + " on the code after it as the file was, and the file does not report " + (b == 1 ? "it now or skips it" : "them now or skips them") + ". It has "
                    + m + (m == 1 ? " test that passes" : " tests that pass") + " after the change and " + (m == 1 ? "does" : "do") + " not pass on both sides ("
                    + shortList(shown(n.shown(), rewrites)) + ")";
            if (b > m) {
                out.add(new Verdict(f, "", Kind.OLD_FAILS_AFTER, what + ", and at most " + m + " of the " + b + " old tests can be among them, rewritten for the changed "
                        + "behaviour: at least " + (b - m) + (b - m == 1 ? " was" : " were") + " taken out of the way", by, (int) (b - m), gone));
                said.put(f, b - m);
            } else out.add(new Verdict(f, "", Kind.UNKNOWN, what + ", so it cannot be told whether the old " + (b == 1 ? "test was" : "tests were") + " rewritten for the changed "
                    + "behaviour under other names, or taken out of the way and other tests written", by, (int) b, gone));
        }
        if (!running.isEmpty()) out.add(new Verdict(f, "", Kind.UNKNOWN, shortList(running.stream().map(k -> shown(n.shown(), k)).toList()) + " passed"
                + " before the change and " + (running.size() == 1 ? "fails" : "fail") + " on the code after it as the file was. A test of " + (running.size() == 1 ? "that name" : "each name")
                + " still runs after the change, so it cannot be told whether " + (running.size() == 1 ? "it was" : "each was") + " edited for the changed behaviour, or taken out and its "
                + "name given to another case, as a row taken out does to the rows after it where cases are named by their place"
                + (n.r1() < n.r0() ? ". The file runs fewer tests than before (" + n.r1() + " where it ran " + n.r0() + ")" : ""), by, running.size(), Cover.of(running, read)));
        return out;
    }

    /** Why a case that failed or threw need not be a test. */
    private static final String FAILURES = " (a failure outside a test's body, in its set-up, tear-down or loading, may be reported in place of the file's tests or as a case of its own)";
    /** Why an empty report need not be a file without tests. */
    private static final String NONE = " (a class that stops before its tests, as one does whose set-up meets a failed assumption, may report no case at all)";
    /** Why a skipped case need not be one test. */
    private static final String HIDES = " (a skipped class, factory or template is reported as one case, whatever number of tests it holds)";
    /** Why a case the build ran again need not be one test. */
    private static final String AGAIN = " (the build runs again a set-up that failed, as Surefire does a class, and reports it as a case of its own beside or in place of the tests)";

    /**
     * Why an edited file's reports may not list every test it has: before, of the report of the
     * file as it was, and after, of the report after the change, each a list of reasons, empty
     * where nothing says so; failedBefore, failedAfter: whether each holds a failure.
     *
     * Every count of a file takes a report for its whole list of tests, one case for each. A case
     * that passed at the first try is one test that ran. The others need not be:
     *
     *   A case that failed or threw may stand for a fault outside a test's body, in place of the
     *   file's tests (JUnit 4 reports a class it cannot set up as one case, initializationError)
     *   or beside them (Vitest reports a failed afterAll as a case of its own). So may one that
     *   failed every time the build ran it again.
     *   A case the build ran again may stand for a set-up that failed and was run again; where it
     *   passed then, the report says only that it passed in the end.
     *   A skipped case may stand for any number of tests, none or many: JUnit 4 reports an
     *   ignored class as one skipped case, Surefire a disabled JUnit 5 factory or template as one,
     *   whose tests it reports one by one once it runs them; JUnit 4 reports a class whose set-up
     *   meets a failed assumption as one skipped case too.
     *   An empty report may stand for any number of tests: a JUnit 5 class that stops in its
     *   set-up on an assumption can report no case at all.
     *
     * Which case stands for what a report does not say, and its name is not read to guess it. So a
     * report is taken for the whole list only where it is not empty, holds no case that failed or
     * threw and none the build ran again, and each case it skips is skipped under its name, as
     * many times, on the other side: whatever a skipped case hides is then hidden on both sides
     * alike. A name skipped on one side only (gone, new, or a name that ran on the other side), or
     * skipped more times on one side, may stand for any number of tests: it is a reason of the
     * side that skips it more.
     *
     * What each count needs of it, and only that (a count that needs less says more):
     *
     *   Tests that ran and do not run now, r0 - r1 (see lost): none of this. It counts cases that
     *   passed as it was at the first try and cases that run after the change, and a test hidden
     *   anywhere does not run. Only a failure as it was keeps it from being said, by choice (see
     *   lost); a case that passed only when run again is not counted in r0.
     *   New tests that pass on both sides, F - G - X (see newTests): the report as it was. A test
     *   it hid may come out under a new name. A test hidden after the change does not pass, and a
     *   case after the change that stands for many tests or none is one of the X.
     *   What the skips and the gone names could not judge, where fewer tests run nowhere (see
     *   skips and gone): said with the reason a report may not list every test.
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

        /** Why the reports may not count the tests that ran and that are skipped: one holds a failure. Null when neither does. */
        String failures() {
            if (!failedBefore && !failedAfter) return null;
            return say(List.of((failedBefore && failedAfter ? "the file as it was and after the change reported failures" : failedBefore ? "the file as it was reported failures"
                    : "after the change the file reports failures") + FAILURES), failedBefore && failedAfter);
        }

        private static String say(List<String> why, boolean both) {
            if (why.isEmpty()) return null;
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < why.size(); i++) {
                String w = why.get(i);
                // each explanation once, at its last use
                for (String x : List.of(FAILURES, NONE, HIDES, AGAIN)) {
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
        List<String> againBefore = retried(was, shown), againAfter = retried(is, shown);
        if (!againBefore.isEmpty()) old.add("the file as it was reported " + (againBefore.size() == 1 ? "a case" : "cases") + " the build ran again (" + shortList(againBefore) + ")" + AGAIN);
        if (is.isEmpty()) now.add("after the change the file reports no tests" + NONE);
        if (failedAfter) now.add("after the change the file reports failures" + FAILURES);
        if (!againAfter.isEmpty()) now.add("after the change the file reports " + (againAfter.size() == 1 ? "a case" : "cases") + " the build ran again (" + shortList(againAfter) + ")" + AGAIN);
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

    /** The names under which a case passed only when the build ran it again (one that failed every time is a failure: see failing). */
    private static List<String> retried(Map<String, List<JUnitXml.Case>> cases, Map<String, String> shown) {
        return cases.entrySet().stream().filter(e -> e.getValue().stream().anyMatch(c -> out(c) == Out.RETRIED)).map(e -> shown(shown, e.getKey())).toList();
    }

    /** How many of these cases are skipped. */
    private static long skipped(List<JUnitXml.Case> cases) {
        return cases.size() - ran(cases);
    }

    /** True when one of these cases failed or threw. */
    private static boolean failing(List<JUnitXml.Case> cases) {
        return cases.stream().anyMatch(c -> failed(out(c)));
    }

    /** True when a run does not report a case of this identity, or skips every one. */
    private static boolean notRun(FileRun run, String key) {
        return run.cases().stream().filter(c -> key(c).equals(key)).allMatch(c -> out(c) == Out.SKIPPED);
    }

    /**
     * The differences among a file's reports, found apart from every rule (see reconcile).
     * change: per identity whose cases had other results in a run of the file as it was and a run
     * after the change, how: passed, passed only when run again, failed, threw, skipped, or not
     * reported, each case of the name (a name gone, a name new, a test switched off or on, and
     * another result are all such a difference). repeat: per way (see Side), per identity whose
     * cases had other results in two runs of that way, how; "" for the file, where one run of
     * the way gave no result and another did. A case that passes in every run as it was and after
     * the change is no difference.
     */
    private record Ledger(Map<String, String> change, Map<Side, Map<String, String>> repeat) {
        /** The identities that the file as it was, or after the change, reported otherwise from run to run. */
        Set<String> unsteady() {
            Set<String> out = new LinkedHashSet<>();
            for (Side s : List.of(Side.OLD, Side.AFTER)) out.addAll(repeat.getOrDefault(s, Map.of()).keySet());
            return out;
        }
    }

    private static Ledger ledger(String f, boolean edited, Seen seen, Map<String, String> sh) {
        Map<String, String> change = new LinkedHashMap<>();
        if (edited) {
            for (FileRun o : seen.of(Side.OLD, f)) {
                if (!gave(o)) continue;
                for (FileRun a : seen.of(Side.AFTER, f)) {
                    if (!gave(a)) continue;
                    Map<String, List<Out>> was = outs(o), is = outs(a);
                    Set<String> keys = new LinkedHashSet<>(was.keySet());
                    keys.addAll(is.keySet());
                    for (String k : keys) {
                        if (was.getOrDefault(k, List.of()).equals(is.getOrDefault(k, List.of()))) continue;
                        change.putIfAbsent(k, shown(sh, k) + " (as it was: " + words(was.get(k)) + "; after the change: " + words(is.get(k)) + ")");
                    }
                }
            }
        }
        Map<Side, Map<String, String>> repeat = new EnumMap<>(Side.class);
        for (Side side : Side.values()) {
            List<FileRun> all = seen.of(side, f);
            if (all.size() < 2) continue;
            Map<String, String> here = new LinkedHashMap<>();
            List<FileRun> ok = all.stream().filter(Witness::gave).toList();
            if (!ok.isEmpty() && ok.size() < all.size()) here.put("", "the file " + side.words + " (one run gave no result and another gave one)");
            List<Map<String, List<Out>>> each = ok.stream().map(Witness::outs).toList();
            Set<String> keys = new LinkedHashSet<>();
            for (Map<String, List<Out>> m : each) keys.addAll(m.keySet());
            for (String k : keys) {
                List<List<Out>> seq = each.stream().map(m -> m.getOrDefault(k, List.of())).toList();
                if (seq.stream().distinct().count() > 1) here.put(k, shown(sh, k) + " (" + side.words + ": " + String.join(", ", seq.stream().map(Witness::words).toList()) + ")");
            }
            if (!here.isEmpty()) repeat.put(side, here);
        }
        return new Ledger(change, repeat);
    }

    /** Per identity, what each of its cases in a run said, in a fixed order. */
    private static Map<String, List<Out>> outs(FileRun run) {
        Map<String, List<Out>> out = new LinkedHashMap<>();
        for (JUnitXml.Case c : run.cases()) out.computeIfAbsent(key(c), k -> new ArrayList<>()).add(out(c));
        for (List<Out> l : out.values()) l.sort(null);
        return out;
    }

    private static String words(List<Out> outs) {
        return outs == null || outs.isEmpty() ? "not reported" : String.join(" and ", outs.stream().map(o -> o.word).toList());
    }

    /**
     * No silence by construction. Every rule above says what it answers for (see Cover); here,
     * every difference among a file's reports (see Ledger) that no verdict of the file answers for
     * is said, whatever the rules made of it: one line could not be judged for the differences
     * between the file as it was and after the change, with the names and what each run reported,
     * and one line unstable for those from run to run. A name whose reports differ from run to run
     * as it was or after the change is said among the second only: its difference between the
     * sides is not the same in every run either.
     *
     * So a case that falls between two rules, a repeat whose report no rule read, or a rule added
     * later that does not say what it answers for, leaves a line here, not silence. Neither line
     * fails the check: what is certain is said by the rules.
     */
    private static List<Verdict> reconcile(String f, Ledger l, List<Verdict> said, String by) {
        List<Cover> covers = said.stream().filter(v -> v.file().equals(f)).map(Verdict::cover).toList();
        Set<String> unsteady = l.unsteady();
        List<String> change = new ArrayList<>();
        for (Map.Entry<String, String> e : l.change().entrySet()) {
            if (!unsteady.contains(e.getKey()) && covers.stream().noneMatch(c -> c.change(e.getKey()))) change.add(e.getValue());
        }
        List<String> repeat = new ArrayList<>();
        Set<String> which = new LinkedHashSet<>();
        for (Map.Entry<Side, Map<String, String>> s : l.repeat().entrySet()) {
            for (Map.Entry<String, String> e : s.getValue().entrySet()) {
                if (covers.stream().anyMatch(c -> c.repeat(s.getKey(), e.getKey()))) continue;
                repeat.add(e.getValue());
                which.add(e.getKey());
            }
        }
        List<Verdict> out = new ArrayList<>();
        if (!change.isEmpty()) out.add(new Verdict(f, "", Kind.UNKNOWN, "the reports of this file as it was and after the change differ in ways no rule of the check judged: "
                + shortList(change) + ". Whether " + (change.size() == 1 ? "this test was" : "these tests were") + " lost, switched off, broken or replaced is not known", by,
                change.size(), Cover.NONE));
        if (!repeat.isEmpty()) out.add(new Verdict(f, "", Kind.UNSTABLE, "the file was reported differently from run to run, in ways no rule of the check judged: " + shortList(repeat), by,
                which.size(), Cover.NONE));
        return out;
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

    /** What a run says of one identity: null where it gave no result, or reports no case or more than one case of it. */
    private static Out status(FileRun run, String key) {
        if (!gave(run)) return null;
        List<Out> found = run.cases().stream().filter(c -> key(c).equals(key)).map(Witness::out).toList();
        return found.size() == 1 ? found.get(0) : null; // none, or two under one name: not a result
    }

    private static String shortType(JUnitXml.Case c) {
        String t = c.type().isBlank() ? "an error" : c.type();
        return t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t;
    }

    private static String describe(JUnitXml.Case c) {
        String m = c.message().replaceAll("\\s+", " ").strip();
        if (m.length() > 140) m = m.substring(0, 140) + "...";
        return (c.status() == JUnitXml.Status.FAILED ? "fails: " : "throws " + shortType(c) + ": ") + m;
    }
}
