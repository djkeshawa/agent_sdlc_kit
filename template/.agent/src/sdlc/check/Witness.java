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

final class Witness {
    private Witness() {}

    enum Kind {
        SENSITIVE,
        SENSITIVE_BY_ERROR,
        PASSES_ON_BOTH,
        EXISTING_PASSES_ON_BOTH,
        FAILS_AFTER,
        OLD_FAILS_AFTER,
        UNKNOWN,
        UNSTABLE,
        SKIPPED,
        EXISTING_SKIPPED,
        NOT_RUN_NOW,
        MOVED
    }

    // alone: judged from single runs on both sides, as its file did not end before the change within these seconds (0: not); the detail says so and
    // is always printed. bare: the detail without that note.
    record Verdict(String file, String name, Kind kind, String detail, String runner, int tests, boolean newCode, Cover cover, int alone, String bare) {
        Verdict(String file, String name, Kind kind, String detail, String runner, int tests, boolean newCode, Cover cover, int alone) {
            this(file, name, kind, detail, runner, tests, newCode, cover, alone, detail);
        }

        Verdict(String file, String name, Kind kind, String detail, String runner, int tests, boolean newCode, Cover cover) {
            this(file, name, kind, detail, runner, tests, newCode, cover, 0);
        }

        Verdict(String file, String name, Kind kind, String detail, String runner, Cover cover) {
            this(file, name, kind, detail, runner, 1, false, cover);
        }

        Verdict(String file, String name, Kind kind, String detail, String runner, int tests, Cover cover) {
            this(file, name, kind, detail, runner, tests, false, cover);
        }

        static Verdict beforeFailed(String file, String name, String detail, String runner) {
            return new Verdict(file, name, Kind.UNKNOWN, detail, runner, 1, true, Cover.NONE);
        }

        Verdict with(Cover c) {
            return new Verdict(file, name, kind, detail, runner, tests, newCode, c, alone, bare);
        }

        Verdict aloneBecause(int seconds, String lead) {
            String why = lead + aloneWords(seconds);
            return new Verdict(file, name, kind, detail.isEmpty() ? why : detail + "; " + why, runner, tests, newCode, cover, seconds, bare);
        }

        Verdict aloneBecause(int seconds) {
            return seconds == 0 ? this : aloneBecause(seconds, "");
        }

        boolean atLeast() {
            return name.isEmpty() && (kind == Kind.PASSES_ON_BOTH || kind == Kind.NOT_RUN_NOW || kind == Kind.OLD_FAILS_AFTER);
        }
    }

    record Cover(Set<String> change, Map<Side, Set<String>> repeat) {
        static final String ALL = "*";
        static final Cover NONE = new Cover(Set.of(), Map.of());
        static final Cover FILE = every(Set.of(ALL), Side.values());

        static Cover of(Collection<String> keys, Side... ways) {
            return every(new LinkedHashSet<>(keys), ways);
        }

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
        long count(Kind k) {
            return verdicts.stream().filter(v -> v.kind() == k).mapToLong(Verdict::tests).sum();
        }

        boolean atLeast(Kind k) {
            return verdicts.stream().anyMatch(v -> v.kind() == k && v.atLeast());
        }
    }

    static Set<String> muted = Set.of();

    static String aloneWords(int seconds) {
        return "ran alone on both sides, as before the change its file did not end within " + seconds + " s";
    }

    // Of the tests these verdicts speak of, how many were judged from single runs, and why; null when none was.
    static String aloneSaid(Collection<Verdict> said) {
        List<Verdict> alone = said.stream().filter(v -> v.alone() > 0).toList();
        if (alone.isEmpty()) return null;
        long n = alone.stream().mapToLong(Verdict::tests).sum(), all = said.stream().mapToLong(Verdict::tests).sum();
        long files = alone.stream().map(Verdict::file).distinct().count();
        int seconds = alone.stream().mapToInt(Verdict::alone).max().orElse(0);
        String file = files > 1 ? "their test files" : n == 1 ? "its test file" : "their test file";
        return (all == 1 ? "it ran" : "of them, " + n + " ran") + " alone on both sides, as before the change " + file + " did not end within " + seconds + " s";
    }

    static Result run(Change change, List<Runner> runners, int runs, int timeoutSeconds) {
        try (Sides sides = new Sides(change, runners)) {
            return run(change, runners, runs, timeoutSeconds, sides);
        }
    }

    static Result run(Change change, List<Runner> runners, int runs, int timeoutSeconds, Sides sides) {
        Budget budget = new Budget(timeoutSeconds);
        List<Verdict> verdicts = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        Scratch head = sides.head();
        Map<Runner, List<String>> owned = new LinkedHashMap<>();
        for (String path : change.paths("AM")) {
            Runner owner = runners.stream().filter(r -> r.runs(head.dir, path)).findFirst().orElse(null);
            if (owner != null) owned.computeIfAbsent(owner, k -> new ArrayList<>()).add(path);
            else if (Integrity.testNamed(path)) unsupported.add(path);
        }
        verdicts.addAll(movedAway(change, runners, owned, sides, timeoutSeconds));
        if (owned.isEmpty()) return new Result(verdicts, unsupported, verdicts.isEmpty() ? 0 : 1);
        Set<String> edited = new LinkedHashSet<>(change.paths("M"));
        Map<String, Integrity.Move> moved = moved(change, owned, sides);
        for (Map.Entry<Runner, List<String>> e : owned.entrySet()) {
            verdicts.addAll(judge(change, e.getKey(), e.getValue(), edited, moved, sides, Math.max(1, runs), timeoutSeconds, budget));
        }
        return new Result(verdicts, unsupported, Math.max(1, runs));
    }

    private static Map<String, Integrity.Move> moved(Change change, Map<Runner, List<String>> owned, Sides sides) {
        Map<String, Integrity.Move> out = new LinkedHashMap<>();
        Map<String, Integrity.Move> moves = Integrity.moves(change);
        if (moves.isEmpty()) return out;
        for (Integrity.Move m : moves.values()) {
            Runner owner = owned.entrySet().stream().filter(e -> e.getValue().contains(m.to())).map(Map.Entry::getKey).findFirst().orElse(null);
            if (owner != null && owner.runs(sides.old().dir, m.from())) out.put(m.to(), m);
        }
        return out;
    }

    private static List<Verdict> movedAway(Change change, List<Runner> runners, Map<Runner, List<String>> owned, Sides sides, int timeout) {
        List<Verdict> out = new ArrayList<>();
        List<String> deleted = change.paths("D");
        if (deleted.isEmpty()) return out;
        Map<String, Integrity.Move> moves = Integrity.moves(change);
        Map<Runner, List<String>> gone = new LinkedHashMap<>();
        for (String path : deleted) {
            Integrity.Move m = moves.get(path);
            if (m != null && owned.values().stream().anyMatch(files -> files.contains(m.to()))) continue;
            Runner was = runners.stream().filter(r -> r.runs(sides.old().dir, path)).findFirst().orElse(null);
            if (was != null) gone.computeIfAbsent(was, k -> new ArrayList<>()).add(path);
        }
        for (Map.Entry<Runner, List<String>> e : gone.entrySet()) {
            Runner was = e.getKey();
            Map<String, FileRun> ran = run(was, sides.old().dir, e.getValue(), timeout);
            for (String path : e.getValue()) {
                FileRun old = ran.get(path);
                Integrity.Move m = moves.get(path);
                String to = m == null ? "deleted" : "moved to " + m.to() + ", where no runner of the check runs it";
                if (gave(old) && old.retriesUnknown() != null) {
                    out.add(new Verdict(path, "", Kind.UNKNOWN, "the file was " + to + ". Run as it was before the change, " + old.retriesUnknown() + ": so it is not known "
                            + "whether a test of the file passed at the first try or only when it was run again, and whether tests that ran before the change do not run now is not known",
                            was.name(), Cover.NONE));
                    continue;
                }
                long passed = gave(old) ? passed(old.cases()) : 0;
                if (passed > 0) out.add(new Verdict(path, "", Kind.NOT_RUN_NOW, "the file passed " + passed + " of its tests before the change and was " + to + ": at least " + passed
                        + (passed == 1 ? " test that ran before the change does" : " tests that ran before the change do") + " not run now", was.name(), (int) passed, Cover.FILE));
                else out.add(new Verdict(path, "", Kind.UNKNOWN, "the file was " + to + ". Run as it was before the change, it "
                        + (old == null ? "gave no result" : old.problem() != null ? old.problem() : "passed no test") + ", so whether tests that ran before the change do not run now is not known", was.name(), Cover.NONE));
            }
        }
        return out;
    }

    private static String key(JUnitXml.Case c) {
        return c.suite() + "#" + c.name();
    }

    // Surefire writes cases in the order they finish, which differs from run to run where tests run concurrently: a report's order is never read.
    private static final java.util.Comparator<JUnitXml.Case> ORDER = java.util.Comparator.comparing(JUnitXml.Case::suite).thenComparing(JUnitXml.Case::name)
            .thenComparing(JUnitXml.Case::status).thenComparing(JUnitXml.Case::retried).thenComparing(JUnitXml.Case::type).thenComparing(JUnitXml.Case::message);

    private static Map<String, FileRun> run(Runner runner, java.nio.file.Path dir, List<String> files, int timeout) {
        Map<String, FileRun> out = new LinkedHashMap<>();
        for (Map.Entry<String, FileRun> e : runner.run(dir, files, timeout).entrySet()) {
            FileRun r = e.getValue();
            out.put(e.getKey(), r == null ? null : new FileRun(r.cases().stream().sorted(ORDER).toList(), r.problem(), r.broken(), r.retriesUnknown(), r.timedOut(), r.alone()));
        }
        return out;
    }

    enum Out {
        PASSED("passed"), RETRIED("passed only when the build ran it again"), FAILED("failed"), ERRORED("threw"), SKIPPED("skipped"), NO_RESULT("gave no result");

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
            case NO_RESULT -> Out.NO_RESULT;
        };
    }

    enum Side {
        AFTER("after the change"), BEFORE("before the change"), OLD("as it was"), OLD_TESTS("as it was, on the code after the change");

        final String words;

        Side(String words) {
            this.words = words;
        }
    }

    private static final class Seen {
        private final Map<Side, Map<String, List<FileRun>>> runs = new EnumMap<>(Side.class);

        void saw(Side side, Collection<String> files, Map<String, FileRun> got) {
            Map<String, List<FileRun>> of = runs.computeIfAbsent(side, s -> new LinkedHashMap<>());
            for (String f : files) of.computeIfAbsent(f, x -> new ArrayList<>()).add(got.get(f));
        }

        List<FileRun> of(Side side, String file) {
            return runs.getOrDefault(side, Map.of()).getOrDefault(file, List.of());
        }
    }

    private static void earlier(Runner runner, List<String> files, Set<String> edited, Map<String, Integrity.Move> moved, Sides sides, int timeout, Seen seen) {
        List<String> had = files.stream().filter(f -> edited.contains(f) || moved.containsKey(f)).toList();
        seen.saw(Side.OLD, had, runOld(runner, sides, had, moved, timeout));
    }

    private static Map<String, FileRun> runOld(Runner runner, Sides sides, List<String> files, Map<String, Integrity.Move> moved, int timeout) {
        if (files.isEmpty()) return Map.of();
        List<String> paths = files.stream().map(f -> moved.containsKey(f) ? moved.get(f).from() : f).toList();
        Map<String, FileRun> ran = run(runner, sides.old().dir, paths, timeout), out = new LinkedHashMap<>();
        for (int i = 0; i < files.size(); i++) out.put(files.get(i), ran.get(paths.get(i)));
        return out;
    }

    private static boolean muted(String rule) {
        return muted.contains(rule);
    }

    private static List<Verdict> judge(Change change, Runner runner, List<String> files, Set<String> edited, Map<String, Integrity.Move> moved, Sides sides, int runs, int timeout,
            Budget budget) {
        String by = runner.name();
        Scratch head = sides.head(), base = sides.base();
        Map<String, FileRun> after = run(runner, head.dir, files, timeout);
        Map<String, FileRun> before = new LinkedHashMap<>(run(runner, base.dir, files, timeout));
        Map<String, FileRun> timedOut = new LinkedHashMap<>();
        for (String f : files) {
            FileRun b = before.get(f), a = after.get(f);
            if (b == null || b.timedOut() == 0 || !gave(a) || a.cases().isEmpty() || !runner.runsAlone()) continue;
            FileRun n = narrowed(runner, f, a, a, b, head, base, timeout, budget);
            if (n == b) continue;
            timedOut.put(f, b);
            before.put(f, n);
        }
        Seen seen = new Seen();
        earlier(runner, files, edited, moved, sides, timeout, seen);
        Set<String> alone = new LinkedHashSet<>();
        for (String f : files) if (before.get(f) != null && before.get(f).broken()) alone.add(f);
        if (alone.size() < 2 || alone.size() > MAX_ALONE) alone.clear();
        for (String f : alone) before.put(f, runAlone(change, runner, files, f, base, timeout));
        Map<String, Set<String>> lonely = new LinkedHashMap<>();
        for (String f : alone) {
            FileRun a = after.get(f);
            if (!gave(a)) continue;
            FileRun solo = runAlone(change, runner, files, f, head, timeout);
            for (JUnitXml.Case c : a.cases()) {
                if (out(c) == Out.PASSED && (status(solo, key(c)) != Out.PASSED || solo.retriesUnknown() != null)) lonely.computeIfAbsent(f, k -> new LinkedHashSet<>()).add(key(c));
            }
        }
        if (!alone.isEmpty()) {
            restore(change, runner, head, files);
            restore(change, runner, base, files);
        }
        seen.saw(Side.AFTER, files, after);
        seen.saw(Side.BEFORE, files, before);
        Map<String, Verdict> ended = new LinkedHashMap<>();
        Map<String, Set<String>> candidates = new LinkedHashMap<>();
        Map<String, Names> named = new LinkedHashMap<>();
        Map<String, Skips> skipsIn = new LinkedHashMap<>();
        Map<String, String> sharedIn = new LinkedHashMap<>();
        Set<String> short1 = new LinkedHashSet<>();
        Set<String> counted = new LinkedHashSet<>();
        List<String> movedShort = new ArrayList<>();
        List<String> movedGrew = new ArrayList<>();
        for (String f : files) {
            FileRun a = after.get(f), b = before.get(f);
            if (!gave(a)) {
                boolean broken = a != null && a.broken();
                ended.put(f, new Verdict(f, "", broken ? Kind.FAILS_AFTER : Kind.UNKNOWN, (broken ? "after the change it " : "could not be run after the change: ")
                        + (a == null ? "no result" : a.problem()), by, broken ? Cover.FILE : Cover.NONE));
                continue;
            }
            String shared = runner.sharedBySides(change, head.dir, f);
            sharedIn.put(f, shared);
            boolean mv = moved.containsKey(f);
            FileRun o = edited.contains(f) || mv ? seen.of(Side.OLD, f).get(0) : null;
            if (blind(seen, f) != null) continue;
            Map<String, String> sh = shown(gave(o) && !mv ? o.cases() : List.of(), a.cases());
            Map<String, Object> r = read(f, edited.contains(f), mv, o, b, a, shared, sh, lonely.getOrDefault(f, Set.of()), by);
            for (Map.Entry<String, Object> x : r.entrySet()) if (x.getValue() instanceof Verdict v && sensitive(v.kind())) candidates.computeIfAbsent(f, k -> new LinkedHashSet<>()).add(x.getKey());
            if (mv && gave(o) && passes(o.cases()) > ran(a.cases())) movedShort.add(f);
            if (mv && gave(o) && count(f, movedNames(o.cases(), a.cases(), sh), r, a, b, by).least() > 0) movedGrew.add(f);
            if (edited.contains(f) && gave(o)) {
                Names n = names(o.cases(), a.cases());
                named.put(f, n);
                skipsIn.put(f, skips(f, n, by));
                if (count(f, n, r, a, b, by).least() > 0) counted.add(f);
                if (passes(o.cases()) > ran(a.cases())) short1.add(f);
            }
        }
        List<String> sensitive = new ArrayList<>(candidates.keySet());
        List<String> switched = new ArrayList<>();
        for (Map.Entry<String, Skips> e : skipsIn.entrySet()) if (!e.getValue().answer().isEmpty() || e.getValue().fewer()) switched.add(e.getKey());
        for (Map.Entry<String, Names> e : named.entrySet()) {
            boolean counts = short1.contains(e.getKey()) || e.getValue().gone().size() > e.getValue().fresh().size() || counted.contains(e.getKey());
            if (counts && !switched.contains(e.getKey())) switched.add(e.getKey());
        }
        Set<String> again = new LinkedHashSet<>(sensitive);
        again.addAll(switched);
        again.addAll(movedShort);
        again.addAll(movedGrew);
        List<String> rerunOld = new ArrayList<>(switched);
        for (String f : movedShort) if (!rerunOld.contains(f)) rerunOld.add(f);
        for (String f : movedGrew) if (!rerunOld.contains(f)) rerunOld.add(f);
        for (int i = 1; i < runs && !again.isEmpty(); i++) {
            Map<String, FileRun> now = run(runner, head.dir, new ArrayList<>(again), timeout);
            seen.saw(Side.AFTER, again, now);
            if (!sensitive.isEmpty()) {
                List<String> together = sensitive.stream().filter(f -> !alone.contains(f) && !timedOut.containsKey(f)).toList();
                Map<String, FileRun> b = new LinkedHashMap<>();
                if (!alone.isEmpty()) restore(change, runner, base, files);
                if (!together.isEmpty()) b.putAll(run(runner, base.dir, together, timeout));
                // before the runs of a file alone, which put the other test files of the change back as the base has them
                for (String f : sensitive) if (timedOut.containsKey(f)) b.put(f, narrowed(runner, f, now.get(f), after.get(f), timedOut.get(f), head, base, timeout, budget));
                for (String f : sensitive) if (alone.contains(f)) b.put(f, runAlone(change, runner, files, f, base, timeout));
                seen.saw(Side.BEFORE, sensitive, b);
            }
            if (!rerunOld.isEmpty()) seen.saw(Side.OLD, rerunOld, runOld(runner, sides, rerunOld, moved, timeout));
        }
        Map<String, Set<String>> wanted = new LinkedHashMap<>();
        for (Map.Entry<String, Names> e : named.entrySet()) {
            if (!e.getValue().changed() && !short1.contains(e.getKey())) continue;
            Set<String> keys = new LinkedHashSet<>();
            for (JUnitXml.Case c : seen.of(Side.OLD, e.getKey()).get(0).cases()) if (out(c) == Out.PASSED) keys.add(key(c));
            if (!keys.isEmpty()) wanted.put(e.getKey(), keys);
        }
        Fourth fourth = fourth(change, runner, wanted, short1, sides, runs, timeout, seen);
        if (!alone.isEmpty() && runs > 1 && !sensitive.isEmpty()) restore(change, runner, base, files);
        Set<String> blinded = new LinkedHashSet<>();
        for (String f : files) if (!ended.containsKey(f) && blind(seen, f) != null) blinded.add(f);
        named.keySet().removeAll(blinded);
        skipsIn.keySet().removeAll(blinded);
        switched.removeAll(blinded);
        return decide(files, edited, moved, ended, candidates, lonely, blinded, named, skipsIn, sharedIn, switched, fourth, seen, by);
    }

    private static String blind(Seen seen, String f) {
        for (Side s : Side.values()) for (FileRun r : seen.of(s, f)) if (gave(r) && r.retriesUnknown() != null) return r.retriesUnknown();
        return null;
    }

    private static List<Verdict> blindly(String f, String why, List<FileRun> afters, Map<String, String> sh, String by) {
        List<Verdict> out = new ArrayList<>();
        Set<String> keys = new java.util.TreeSet<>();
        for (FileRun a : afters) if (gave(a)) for (JUnitXml.Case c : a.cases()) keys.add(key(c));
        for (String k : keys) {
            Verdict fails = failsAfter(f, k, afters, sh, by);
            if (fails != null) out.add(fails.with(Cover.of(List.of(k), Side.BEFORE, Side.AFTER)));
        }
        Verdict unbuilt = brokenAfter(f, afters, by);
        if (unbuilt != null) out.add(unbuilt);
        out.add(new Verdict(f, "", Kind.UNKNOWN, why + ": so it is not known whether a test of the file passed at the first try or only when it was run again, and nothing is "
                + "said of the file but its failures after the change", by, Cover.NONE));
        return out;
    }

    private static Verdict moved(String f, Integrity.Move m, Seen seen, String by) {
        String imports = m.how() == Integrity.How.IMPORTS ? ". Its imports changed: an import may change what a test tests while it passes, which no run shows" : "";
        List<FileRun> olds = seen.of(Side.OLD, f).stream().filter(Witness::gave).toList(), afters = seen.of(Side.AFTER, f).stream().filter(Witness::gave).toList();
        boolean same = !olds.isEmpty() && !afters.isEmpty() && olds.stream().allMatch(o -> afters.stream().allMatch(a -> a.cases().size() == o.cases().size()));
        return new Verdict(f, "", Kind.MOVED, "moved from " + m.from() + " (" + m.how().words + "). Its tests are named after its path or its package, so the file as it was is "
                + "compared with it by how many tests it reports and runs, not by their names" + (same ? ": it reports as many tests as it had" : "") + imports, by,
                moveCover(seen, f));
    }

    private static Cover moveCover(Seen seen, String f) {
        List<FileRun> olds = seen.of(Side.OLD, f).stream().filter(Witness::gave).toList(), afters = seen.of(Side.AFTER, f).stream().filter(Witness::gave).toList();
        boolean grew = false, shrank = false;
        for (FileRun o : olds) for (FileRun a : afters) {
            grew |= a.cases().size() > o.cases().size();
            shrank |= a.cases().size() < o.cases().size();
        }
        Set<String> keys = new java.util.TreeSet<>();
        if (!shrank) keys.addAll(onlyIn(olds, afters));
        if (!grew) keys.addAll(onlyIn(afters, olds));
        return Cover.change(keys);
    }

    private static Set<String> onlyIn(List<FileRun> these, List<FileRun> others) {
        Set<String> out = new java.util.TreeSet<>(), there = new LinkedHashSet<>();
        for (FileRun r : others) for (JUnitXml.Case c : r.cases()) there.add(key(c));
        for (FileRun r : these) for (JUnitXml.Case c : r.cases()) if (!there.contains(key(c))) out.add(key(c));
        return out;
    }

    private static Names movedNames(List<JUnitXml.Case> old, List<JUnitXml.Case> now, Map<String, String> shown) {
        return new Names(old, now, List.of(), 0, List.of(), ran(old), old.size() - ran(old), ran(now), now.size() - ran(now), shown, listing(byKey(old), byKey(now), shown));
    }

    private static List<Verdict> grown(String f, List<Count> counts, String by) {
        if (counts.stream().allMatch(c -> c == null || c.passBoth().isEmpty() || growth(c) <= 0)) return List.of();
        Set<String> fresh = new java.util.TreeSet<>();
        for (Count c : counts) if (c != null) for (JUnitXml.Case x : c.n().fresh()) fresh.add(key(x));
        Cover cover = Cover.change(fresh);
        long min = counts.stream().mapToLong(c -> c == null ? 0 : c.least()).min().orElse(0), max = counts.stream().mapToLong(c -> c == null ? 0 : c.least()).max().orElse(0);
        if (min > 0) {
            Count at = counts.stream().filter(c -> c.least() == min).findFirst().orElseThrow();
            return List.of(new Verdict(f, "", Kind.PASSES_ON_BOTH, movedCounts(at) + (max > min ? " (of every run, the one that gives the fewest)" : "") + ": at least " + min
                    + (min == 1 ? " new test passes" : " new tests pass") + " before and after" + passing(at, min == 1), by, (int) min, cover));
        }
        if (max > 0) {
            Count at = counts.stream().filter(c -> c != null && c.least() > 0).findFirst().orElseThrow();
            return List.of(new Verdict(f, "", Kind.UNSTABLE, movedCounts(at) + ": at least " + at.least() + (at.least() == 1 ? " new test passes" : " new tests pass")
                    + " before and after, but what the file reports, after the change or as it was, is not the same in every run", by, (int) at.least(),
                    Cover.of(fresh, Side.OLD, Side.BEFORE, Side.AFTER)));
        }
        Count at = counts.stream().filter(c -> c != null && growth(c) > 0 && !c.passBoth().isEmpty()).findFirst().orElseThrow();
        long more = growth(at), other = at.other();
        String why = at.n().listing().old() != null ? "a test the file had that its report as it was did not list may be among them: " + at.n().listing().old()
                : "the new " + (more == 1 ? "test" : "tests") + " may be among the " + other + " that do not pass on both sides";
        return List.of(new Verdict(f, "", Kind.UNKNOWN, movedCounts(at) + ". So it has " + more + (more == 1 ? " test" : " tests") + " more than it had, and whether a new test "
                + "passes before and after is not known: " + why + passing(at, more == 1), by, (int) more, cover));
    }

    private static long growth(Count c) {
        return c.n().fresh().size() - c.n().gone().size();
    }

    private static String movedCounts(Count c) {
        long other = c.other();
        return "the file reports " + tests(c.n().fresh().size()) + " where it reported " + c.n().gone().size() + " as it was, at the path it had"
                + (other > 0 ? ", and " + other + " of its tests " + (other == 1 ? "does" : "do") + " not pass on both sides" : "");
    }

    private static String passing(Count c, boolean one) {
        long p = c.passBoth().size();
        return ". " + p + (p == 1 ? " passes" : " pass") + " before and after (" + shortList(shown(c.n().shown(), c.passBoth())) + "); its names changed with the move, so they "
                + "do not say which " + (one ? "test is" : "tests are") + " new";
    }

    private static List<Verdict> shrunk(String f, Seen seen, String by) {
        List<FileRun> olds = seen.of(Side.OLD, f).stream().filter(Witness::gave).toList(), afters = seen.of(Side.AFTER, f).stream().filter(Witness::gave).toList();
        for (FileRun o : olds) {
            for (FileRun a : afters) {
                long had = o.cases().size(), has = a.cases().size();
                if (has >= had) continue;
                return List.of(new Verdict(f, "", Kind.UNKNOWN, "the file reports " + tests(has) + " where it reported " + had + " as it was, at the path it had. As many tests run as "
                        + "passed as it was (" + ran(a.cases()) + " where " + passed(o.cases()) + " passed), so no test that ran is lost by count: whether tests that did not pass as it "
                        + "was (skipped, failed, or passed only when run again) were removed is not known", by, (int) (had - has), Cover.change(onlyIn(olds, afters))));
            }
        }
        return List.of();
    }

    private static List<Verdict> decide(List<String> files, Set<String> edited, Map<String, Integrity.Move> moved, Map<String, Verdict> ended, Map<String, Set<String>> candidates,
            Map<String, Set<String>> lonely, Set<String> blinded, Map<String, Names> named, Map<String, Skips> skipsIn, Map<String, String> sharedIn, List<String> switched, Fourth fourth,
            Seen seen, String by) {
        List<Verdict> out = new ArrayList<>();
        Map<String, Map<String, String>> shownIn = new LinkedHashMap<>();
        Map<String, Set<String>> passingIn = new LinkedHashMap<>();
        Map<String, List<Verdict>> newIn = new LinkedHashMap<>();
        Map<String, Ledger> ledgers = new LinkedHashMap<>();
        Map<String, Integer> aloneIn = new LinkedHashMap<>();
        List<Verdict> last = new ArrayList<>();
        List<String> counting = new ArrayList<>();
        for (String f : files) {
            boolean mv = moved.containsKey(f);
            if (mv) out.add(moved(f, moved.get(f), seen, by));
            if (ended.containsKey(f)) {
                out.add(ended.get(f));
                continue;
            }
            boolean ed = edited.contains(f);
            List<FileRun> olds = ed || mv ? seen.of(Side.OLD, f) : java.util.Collections.singletonList(null), befores = seen.of(Side.BEFORE, f), afters = seen.of(Side.AFTER, f);
            Map<String, String> sh = shownAll(seen, f, !mv);
            shownIn.put(f, sh);
            Ledger ledger = ledger(f, ed || mv, seen, sh);
            ledgers.put(f, ledger);
            if (blinded.contains(f)) {
                out.addAll(blindly(f, blind(seen, f), afters, sh, by));
                continue;
            }
            Set<String> lone = lonely.getOrDefault(f, Set.of());
            int single = befores.stream().filter(r -> r != null).mapToInt(FileRun::alone).max().orElse(0);
            aloneIn.put(f, single);
            List<Map<String, Object>> reads = new ArrayList<>();
            List<Count> counts = new ArrayList<>();
            for (FileRun o : olds) {
                for (FileRun b : befores) {
                    for (FileRun a : afters) {
                        Map<String, Object> r = gave(a) ? read(f, ed, mv, o, b, a, sharedIn.get(f), sh, lone, by) : Map.of();
                        reads.add(r);
                        if (ed) counts.add(gave(o) && gave(a) ? count(f, names(o.cases(), a.cases()), r, a, b, by) : null);
                        else if (mv) counts.add(gave(o) && gave(a) ? count(f, movedNames(o.cases(), a.cases(), sh), r, a, b, by) : null);
                    }
                }
            }
            Set<String> keys = new java.util.TreeSet<>();
            for (Map<String, Object> r : reads) keys.addAll(r.keySet());
            Set<String> passing = new LinkedHashSet<>(), freshEvery = new LinkedHashSet<>();
            List<Verdict> mine = new ArrayList<>();
            for (String k : keys) {
                Cover c = Cover.of(List.of(k), Side.BEFORE, Side.AFTER);
                Object first = reads.get(0).get(k);
                List<Object> all = reads.stream().map(r -> r.get(k)).toList();
                boolean candidate = first instanceof Verdict v && sensitive(v.kind());
                Verdict fails = failsAfter(f, k, afters, sh, by);
                List<String> unended = befores.stream().filter(r -> status(r, k) == Out.NO_RESULT).map(r -> message(r, k)).distinct().toList();
                if (fails != null) {
                    (candidate ? last : mine).add(fails.with(c));
                } else if (!unended.isEmpty() && !all.stream().allMatch(x -> java.util.Objects.equals(x, first))) {
                    boolean judged = befores.stream().map(r -> status(r, k)).anyMatch(s -> s != null && s != Out.NO_RESULT);
                    (candidate ? last : mine).add(new Verdict(f, shown(sh, k), Kind.UNKNOWN, (judged ? "in one run it ran alone on both sides; in another, " : "") + String.join("; in another run, ", unended), by, c));
                } else if (candidate) {
                    boolean oneType = ((Verdict) first).kind() != Kind.SENSITIVE_BY_ERROR || befores.stream().map(r -> type(r, k)).distinct().count() == 1;
                    if (oneType && all.stream().allMatch(x -> java.util.Objects.equals(x, first))) last.add(((Verdict) first).with(c));
                    else if (afters.stream().anyMatch(r -> status(r, k) != Out.PASSED)) last.add(new Verdict(f, shown(sh, k), Kind.UNSTABLE, "passed after the change in one run and not in another", by, c));
                    else if (befores.stream().allMatch(r -> status(r, k) == Out.ERRORED)) last.add(new Verdict(f, shown(sh, k), Kind.UNKNOWN, String.join("; in another run, ",
                            befores.stream().map(r -> threw(r, k)).distinct().count() > 1 ? befores.stream().map(r -> threw(r, k)).distinct().toList()
                                    : befores.stream().map(r -> "before the change it threw " + type(r, k) + ", not an assertion").distinct().toList()), by, 1, all.stream().anyMatch(x -> x instanceof Verdict v && v.newCode()), c)
                            .aloneBecause(single));
                    else if (befores.stream().anyMatch(r -> status(r, k) == Out.ERRORED)) last.add(new Verdict(f, shown(sh, k), Kind.UNSTABLE, "threw before the change in one run and not in another", by, c).aloneBecause(single));
                    else last.add(new Verdict(f, shown(sh, k), Kind.UNSTABLE, "failed before the change in one run and not in another", by, c).aloneBecause(single));
                } else if (all.stream().allMatch(x -> java.util.Objects.equals(x, first))) {
                    if (first == Mark.FRESH) freshEvery.add(k);
                    if (first == Mark.FRESH || first instanceof Verdict v && (v.kind() == Kind.EXISTING_PASSES_ON_BOTH || v.kind() == Kind.PASSES_ON_BOTH)) passing.add(k);
                    if (first instanceof Verdict v) mine.add(v.with(c));
                } else if (first instanceof Verdict v0 && all.stream().allMatch(x -> x instanceof Verdict v && v.kind() == v0.kind() && v.tests() == v0.tests() && v.newCode() == v0.newCode())) {
                    String why = String.join("; in another run, ", all.stream().map(x -> ((Verdict) x).detail()).distinct().toList());
                    mine.add(new Verdict(f, v0.name(), v0.kind(), why, by, v0.tests(), v0.newCode(), c, all.stream().mapToInt(x -> ((Verdict) x).alone()).max().orElse(0)));
                }
            }
            passingIn.put(f, passing);
            List<Verdict> grew = mv ? grown(f, counts, by) : List.of();
            if (mv && !muted("new tests")) newIn.put(f, grew);
            if (mv && grew.isEmpty()) for (String k : freshEvery) mine.add(new Verdict(f, shown(sh, k), Kind.EXISTING_PASSES_ON_BOTH, "", by, Cover.of(List.of(k), Side.BEFORE, Side.AFTER)));
            if (!muted("per test")) out.addAll(mine);
            Verdict unbuilt = brokenAfter(f, afters, by);
            if (unbuilt != null) out.add(unbuilt);
            Skips s = skipsIn.get(f);
            if (s != null && !muted("skips")) for (Verdict v : s.listed()) if (v.cover().change().stream().noneMatch(ledger.unsteady()::contains)) out.add(v);
            if (named.containsKey(f) && !muted("new tests")) newIn.put(f, newTests(f, counts, freshEvery, by).stream()
                    .map(v -> single == 0 ? v : v.aloneBecause(single, "the tests counted as passing before and after ")).toList());
            FileRun o = ed || mv ? seen.of(Side.OLD, f).get(0) : null, a = afters.get(0);
            if (mv && gave(o)) counting.add(f);
            if (mv && !gave(o)) out.add(new Verdict(f, "", Kind.UNKNOWN, "run as it was before the change, at " + moved.get(f).from() + ", the file " + (o == null ? "gave no result"
                    : o.problem()) + ", so it cannot be told whether tests that ran before the change do not run now, nor whether it has tests it did not have", by, Cover.NONE));
            else if (ed && !gave(o)) out.add(new Verdict(f, "", Kind.UNKNOWN, "run as it was before the change, the file " + (o == null ? "gave no result" : o.problem())
                    + ", so it cannot be told which of its tests are new, were switched off or were removed", by, Cover.NONE));
            else if (a.cases().isEmpty() && (o == null || o.cases().isEmpty())) out.add(new Verdict(f, "", Kind.UNKNOWN, "the file ran after the change and reports no tests", by, Cover.NONE));
        }
        Set<String> unsteady = new LinkedHashSet<>(), recounted = new LinkedHashSet<>();
        for (String f : switched) {
            Names n1 = named.get(f);
            Skips s1 = skipsIn.get(f);
            List<String> first = signature(s1, n1);
            for (FileRun o : seen.of(Side.OLD, f)) {
                for (FileRun a : seen.of(Side.AFTER, f)) {
                    List<String> now = said(o, a, f, by);
                    if (now == null || !first.subList(0, first.size() - 1).equals(now.subList(0, now.size() - 1))) unsteady.add(f);
                    if (now == null || !first.get(first.size() - 1).equals(now.get(now.size() - 1))) recounted.add(f);
                }
            }
        }
        Set<String> judged = new LinkedHashSet<>();
        Map<String, Long> said = new LinkedHashMap<>();
        Map<String, Long> wavered = new LinkedHashMap<>();
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
        Set<String> spoke = new LinkedHashSet<>(), rowed = new LinkedHashSet<>();
        List<Verdict> lostRows = new ArrayList<>();
        for (Map.Entry<String, Names> e : named.entrySet()) {
            String f = e.getKey();
            Set<String> into = muted("lost") ? new LinkedHashSet<>() : spoke;
            List<Verdict> l = lost(f, e.getValue(), seen, fourth, said.getOrDefault(f, 0L), wavered.getOrDefault(f, 0L), into, null, by);
            if (muted("lost")) continue;
            if (!l.isEmpty()) rowed.add(f);
            lostRows.addAll(l);
        }
        for (String f : counting) {
            Set<String> gone = onlyIn(seen.of(Side.OLD, f).stream().filter(Witness::gave).toList(), seen.of(Side.AFTER, f).stream().filter(Witness::gave).toList());
            List<Verdict> l = muted("lost") ? List.of() : lost(f, null, seen, fourth, 0, 0, new LinkedHashSet<>(), moved.get(f), by).stream()
                    .map(v -> v.cover() == Cover.FILE ? v : v.with(v.cover().and(Cover.change(gone)))).toList();
            lostRows.addAll(l.isEmpty() && !muted("lost") && !muted("gone") ? shrunk(f, seen, by) : l);
        }
        for (List<Verdict> v : newIn.values()) out.addAll(v);
        out.addAll(lostRows);
        if (!muted("gone")) out.addAll(gone(named, spoke, rowed, said, judged, recounted, seen, by));
        for (String f : unsteady) {
            if (muted("skips")) break;
            Skips s = skipsIn.get(f);
            if (spoke.contains(f) || !s.answer().isEmpty() || s.fewer() || out.stream().anyMatch(v -> v.file().equals(f) && v.name().isEmpty() && v.kind() == Kind.UNSTABLE)) continue;
            out.add(new Verdict(f, "", Kind.UNSTABLE, "whether tests that ran before the change are skipped now differs from run to run: what the file reports, after the change or as it "
                    + "was, is not the same in every run", by, varied(seen, f, SKIPS, Side.OLD, Side.AFTER)));
        }
        for (Map.Entry<String, Skips> s : skipsIn.entrySet()) {
            String f = s.getKey();
            if (spoke.contains(f) || muted("skips")) continue;
            for (Verdict v : s.getValue().answer()) {
                if (unsteady.contains(f)) out.add(new Verdict(f, "", Kind.UNSTABLE,
                        "whether tests that ran before the change are skipped now differs from run to run: what the file reports, after the change or as it was, is not the same in every run",
                        by, v.tests(), v.cover().and(varied(seen, f, SKIPS, Side.OLD, Side.AFTER))));
                else out.add(v);
            }
        }
        for (Map.Entry<String, Names> e : named.entrySet()) {
            if (fourth.unclear().containsKey(e.getKey()) && !muted("untold")) out.add(fourth.untold(e.getKey(), shownIn.get(e.getKey()), by));
        }
        out.addAll(last);
        for (Map.Entry<String, Ledger> e : ledgers.entrySet()) out.addAll(reconcile(e.getKey(), e.getValue(), out, by, aloneIn.getOrDefault(e.getKey(), 0)));
        return out;
    }

    private enum Mark { SKIP, FRESH }

    private static Map<String, Object> read(String f, boolean edited, boolean moved, FileRun o, FileRun b, FileRun a, String shared, Map<String, String> sh, Set<String> lone, String by) {
        Map<String, Object> out = new LinkedHashMap<>();
        Names n = edited && gave(o) ? names(o.cases(), a.cases()) : null;
        Set<String> fresh = new LinkedHashSet<>();
        if (n != null) for (JUnitXml.Case c : n.fresh()) fresh.add(key(c));
        Map<String, JUnitXml.Case> was = new LinkedHashMap<>();
        Set<String> twice = new LinkedHashSet<>();
        if (gave(b)) for (JUnitXml.Case c : b.cases()) if (was.put(key(c), c) != null) twice.add(key(c));
        for (Map.Entry<String, List<JUnitXml.Case>> e : byKey(a.cases()).entrySet()) {
            String k = e.getKey();
            List<JUnitXml.Case> cs = e.getValue();
            String name = shown(sh, k);
            if (cs.size() > 1) twice.add(k);
            if (cs.stream().allMatch(c -> out(c) == Out.SKIPPED)) {
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
            if (twice.contains(k)) {
                out.put(k, new Verdict(f, name, Kind.UNKNOWN, "more than one test reports this name, so they cannot be told apart", by, Cover.NONE));
                continue;
            }
            JUnitXml.Case p = was.get(k);
            Object v;
            if (!gave(b)) v = b != null && b.broken() ? Verdict.beforeFailed(f, name, "before the change the file " + b.problem(), by)
                    : new Verdict(f, name, Kind.UNKNOWN, "before the change the file " + (b == null ? "gave no result" : b.problem()), by, Cover.NONE);
            else if (p == null && b.alone() > 0) v = new Verdict(f, name, Kind.UNKNOWN, "before the change its file did not end, and in this run it was not run alone: "
                    + "the run of the file after the change that its single runs followed did not report it", by, Cover.NONE);
            else if (p == null) v = Verdict.beforeFailed(f, name, "was not found before the change", by);
            else if (out(p) == Out.NO_RESULT) v = new Verdict(f, name, Kind.UNKNOWN, p.message(), by, Cover.NONE);
            else if (lone.contains(k)) v = new Verdict(f, name, Kind.UNKNOWN, "after the change it passes only together with the other changed test files, and before the change "
                    + "the file could be run only without them (as the base has them), so what it did there may come from them, not from the change", by, Cover.NONE);
            else if (out(p) == Out.PASSED && shared != null) v = new Verdict(f, name, Kind.UNKNOWN, "passes before and after, but " + shared, by, Cover.NONE);
            else if (out(p) == Out.PASSED && (edited || moved) && !gave(o)) v = new Verdict(f, name, Kind.UNKNOWN, "passes before and after, but run as it was before the change, the file "
                    + (o == null ? "gave no result" : o.problem()) + ", so it cannot be told whether this test is new", by, Cover.NONE);
            else if (out(p) == Out.PASSED && moved) v = Mark.FRESH;
            else if (out(p) == Out.PASSED) v = n == null ? new Verdict(f, name, Kind.PASSES_ON_BOTH, "", by, Cover.NONE)
                    : fresh.contains(k) ? Mark.FRESH : new Verdict(f, name, Kind.EXISTING_PASSES_ON_BOTH, "", by, Cover.NONE);
            else if (out(p) == Out.SKIPPED) v = new Verdict(f, name, Kind.UNKNOWN, "was skipped before the change", by, Cover.NONE);
            else if (out(p) == Out.RETRIED) v = new Verdict(f, name, Kind.UNKNOWN, "before the change the build ran it again and got another result", by, Cover.NONE);
            else if (out(p) == Out.ERRORED) v = threw(f, name, p, by);
            else v = new Verdict(f, name, Kind.SENSITIVE, "", by, Cover.NONE);
            if (b != null && b.alone() > 0 && p != null && out(p) != Out.NO_RESULT && v instanceof Verdict x) v = x.aloneBecause(b.alone());
            out.put(k, v);
        }
        return out;
    }

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

    private static Verdict brokenAfter(String f, List<FileRun> afters, String by) {
        List<FileRun> broken = afters.stream().filter(r -> r != null && r.problem() != null && r.broken()).toList();
        if (broken.isEmpty()) return null;
        boolean all = afters.stream().filter(r -> !gave(r)).count() == broken.size();
        return new Verdict(f, "", Kind.FAILS_AFTER, "after the change it " + broken.get(0).problem() + " (in " + broken.size() + " of the " + afters.size() + " runs after the change)", by,
                all ? new Cover(Set.of(), Map.of(Side.AFTER, Set.of(""))) : Cover.NONE);
    }

    private record Fourth(Map<String, FileRun> first, Map<String, Set<String>> broke, Map<String, Set<String>> wavering, Map<String, List<String>> unclear,
            Map<String, Map<String, String>> companions) {
        static final Fourth NONE = new Fourth(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

        String said(String file, Collection<String> keys, Map<String, String> shown) {
            List<String> which = keys.stream().filter(broke.getOrDefault(file, Set.of())::contains).toList();
            FileRun run = first.get(file);
            JUnitXml.Case one = run.cases().stream().filter(c -> key(c).equals(which.get(0))).findFirst().orElseThrow();
            String names = which.size() == 1 ? shown(shown, which.get(0)) + " " + describe(one) : shortList(which.stream().map(k -> shown(shown, k)).toList()) + " fail or throw";
            return "passed before the change; as the test was, on the code after the change " + names;
        }

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
            String code = problem != null ? "" : ", or, where it is not reported there, whether the change of the code, not of the tests, took " + (n == 1 ? "it" : "them")
                    + " away, as it does a row of a parameterized test whose source it shortened";
            return new Verdict(file, "", Kind.UNKNOWN, head + ", so it is not known whether the change broke " + which + " and took it out of the way or replaced it" + code, by, n,
                    Cover.change(keys));
        }
    }

    private static final String WAVERED = "passed before the change, and as the test was, failed on the code after the change in one run and not in another";

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
            return new Fourth(first, Map.of(), Map.of(), unclear, Map.of());
        }
        Map<String, FileRun> ran = run(runner, mixed.dir, files, timeout);
        seen.saw(Side.OLD_TESTS, files, ran);
        for (String f : files) first.put(f, ran.get(f));
        Map<String, Set<String>> failing = new LinkedHashMap<>(), broke = new LinkedHashMap<>(), wavering = new LinkedHashMap<>();
        Map<String, List<String>> unclear = new LinkedHashMap<>();
        Map<String, Map<String, String>> companions = new LinkedHashMap<>();
        for (String f : files) {
            for (String k : wanted.get(f)) {
                Out s = status(first.get(f), k);
                if (failed(s)) failing.computeIfAbsent(f, x -> new LinkedHashSet<>()).add(k);
                else if (s != Out.PASSED) unclear.computeIfAbsent(f, x -> new ArrayList<>()).add(k);
            }
        }
        Map<String, Map<String, String>> clean = new LinkedHashMap<>();
        if (files.size() > 1) {
            for (String f : failing.keySet()) {
                FileRun now = seen.of(Side.AFTER, f).get(0);
                List<String> gone = failing.get(f).stream().filter(k -> !gave(now) || notRun(now, k)).toList();
                if (gone.isEmpty()) continue;
                FileRun solo = oldAlone(change, runner, files, f, mixed, timeout);
                for (String k : gone) {
                    Out s = status(solo, k);
                    if (failed(s)) continue;
                    clean.computeIfAbsent(f, x -> new LinkedHashMap<>()).put(k, !gave(solo) ? "gives no result (" + solo.problem() + ")" : s == null ? "gives no clean result"
                            : s == Out.SKIPPED ? "is skipped" : s == Out.RETRIED ? "passes only when run again" : "passes");
                }
            }
        }
        List<String> again = new ArrayList<>(failing.keySet());
        List<String> counts = files.stream().filter(f -> fewer.contains(f) && !failing.containsKey(f)).toList();
        for (int i = 1; i < runs && !(again.isEmpty() && counts.isEmpty()); i++) {
            if (!again.isEmpty()) {
                seen.saw(Side.OLD, again, run(runner, sides.old().dir, again, timeout));
                seen.saw(Side.OLD_TESTS, again, run(runner, mixed.dir, again, timeout));
                seen.saw(Side.AFTER, again, run(runner, sides.head().dir, again, timeout));
            }
            if (!counts.isEmpty()) seen.saw(Side.OLD_TESTS, counts, run(runner, mixed.dir, counts, timeout));
        }
        for (String f : again) {
            for (String k : failing.get(f)) {
                boolean every = seen.of(Side.OLD, f).stream().allMatch(r -> status(r, k) == Out.PASSED) && seen.of(Side.OLD_TESTS, f).stream().allMatch(r -> failed(status(r, k)));
                String alone = clean.getOrDefault(f, Map.of()).get(k);
                if (every && alone != null) companions.computeIfAbsent(f, x -> new LinkedHashMap<>()).put(k, alone);
                else (every ? broke : wavering).computeIfAbsent(f, x -> new LinkedHashSet<>()).add(k);
            }
        }
        return new Fourth(first, broke, wavering, unclear, companions);
    }

    private static FileRun oldAlone(Change change, Runner runner, List<String> files, String file, Scratch mixed, int timeout) {
        List<String> others = files.stream().filter(x -> !x.equals(file)).toList();
        try {
            mixed.write(change.head, others);
            runner.forgetBuilt(mixed.dir, files);
            FileRun run = run(runner, mixed.dir, List.of(file), timeout).get(file);
            return run == null ? FileRun.failed("gave no result") : run;
        } catch (java.io.IOException e) {
            return FileRun.failed("could not be set up alone (" + e.getMessage() + ")");
        } finally {
            try {
                mixed.write(change.base, files);
                runner.forgetBuilt(mixed.dir, files);
            } catch (java.io.IOException e) {
                throw new sdlc.core.UsageException("cannot prepare the checkout for a repeat run: " + e.getMessage());
            }
        }
    }

    private static boolean failed(Out s) {
        return s == Out.FAILED || s == Out.ERRORED;
    }

    static final String DOES_NOT_COMPILE = "does not compile";
    private static final int MAX_ALONE = 8;

    private static FileRun runAlone(Change change, Runner runner, List<String> files, String file, Scratch base, int timeout) {
        try {
            for (String other : files) {
                if (other.equals(file)) continue;
                if (change.before(other) == null) java.nio.file.Files.deleteIfExists(base.dir.resolve(other));
                else base.write(change.base, List.of(other));
            }
            base.write(change.head, List.of(file));
            runner.forgetBuilt(base.dir, files); // a test file that was just taken away must not stay behind as a compiled class
            FileRun run = run(runner, base.dir, List.of(file), timeout).get(file);
            return run == null ? FileRun.failed("gave no result") : run;
        } catch (java.io.IOException e) {
            return FileRun.failed("could not be set up alone (" + e.getMessage() + ")");
        }
    }

    static java.util.function.LongSupplier clock = System::nanoTime;

    // The time all single runs of one check may take together: the time bound of one run.
    static final class Budget {
        private long left;

        Budget(int seconds) {
            left = seconds * 1_000_000_000L;
        }

        int seconds() {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0, left) / 1_000_000_000L);
        }

        void spend(long nanos) {
            left -= Math.max(0, nanos);
        }
    }

    // A single run before the change may take ten times what the same single run took after it, at least 60 s, never more than the bound or the time left.
    static int beforeBound(int timeout, long afterNanos, int left) {
        long tenfold = (Math.max(0, afterNanos) * 10 + 999_999_999L) / 1_000_000_000L;
        return (int) Math.min(Math.min(timeout, left), Math.max(60, tenfold));
    }

    static final String USED_UP = "it was not run alone, as the time for single runs was used up";

    // The run before the change of a file that did not end, made again of single runs: each method of the cases the file reports after the change, run
    // alone after the change and then before it. A case gets its result before the change only where both single runs ended with a report of it and it
    // gave alone after the change what it gave with its file; every other case is a case with no result, its message saying what was seen.
    private static FileRun narrowed(Runner runner, String f, FileRun with, FileRun first, FileRun timedOut, Scratch head, Scratch base, int timeout, Budget budget) {
        try {
            String was = "before the change the file " + timedOut.problem();
            List<JUnitXml.Case> keys = gave(with) ? with.cases() : first.cases();
            Map<String, JUnitXml.Case> got = new LinkedHashMap<>();
            Map<String, String> why = new LinkedHashMap<>();
            Map<String, List<JUnitXml.Case>> groups = new LinkedHashMap<>();
            for (JUnitXml.Case c : keys) {
                if (!gave(with)) {
                    why.put(key(c), "in this run its file gave no result after the change, so it was not run alone");
                    continue;
                }
                Runner.Alone a = runner.alone(f, c);
                if (a.filter() == null) why.put(key(c), "it was not run alone: " + a.why());
                else groups.computeIfAbsent(a.filter(), x -> new ArrayList<>()).add(c);
            }
            for (Map.Entry<String, List<JUnitXml.Case>> g : groups.entrySet()) single(runner, f, g.getKey(), g.getValue(), with, head, base, timeout, budget, why, got);
            List<JUnitXml.Case> cases = new ArrayList<>();
            for (JUnitXml.Case c : keys) {
                JUnitXml.Case b = got.get(key(c));
                cases.add(b != null ? b : new JUnitXml.Case(c.suite(), c.name(), JUnitXml.Status.NO_RESULT, "", was + "; " + why.get(key(c))));
            }
            return new FileRun(cases.stream().sorted(ORDER).toList(), null, false, null, 0, timedOut.timedOut());
        } catch (RuntimeException e) {
            return timedOut;
        }
    }

    private static void single(Runner runner, String f, String filter, List<JUnitXml.Case> group, FileRun with, Scratch head, Scratch base, int timeout, Budget budget,
            Map<String, String> why, Map<String, JUnitXml.Case> got) {
        if (budget.seconds() < 1) {
            for (JUnitXml.Case c : group) why.put(key(c), USED_UP);
            return;
        }
        int bound = Math.min(timeout, budget.seconds());
        long start = clock.getAsLong();
        FileRun after = runner.runAlone(head.dir, f, filter, bound);
        long took = clock.getAsLong() - start;
        budget.spend(took);
        String all = unended(after, filter, "after", bound);
        List<JUnitXml.Case> left = new ArrayList<>();
        for (JUnitXml.Case c : group) {
            String k = key(c), no = all != null ? all : reported(after, k, "after");
            Out alone = no == null ? status(after, k) : null, together = status(with, k);
            if (no == null && alone != together) no = "run alone after the change it " + alone.word + ", and with its file it " + (together == null ? "gave no single result" : together.word);
            if (no != null) why.put(k, no);
            else left.add(c);
        }
        if (left.isEmpty()) return;
        if (budget.seconds() < 1) {
            for (JUnitXml.Case c : left) why.put(key(c), "it was run alone after the change, and not before it, as the time for single runs was used up");
            return;
        }
        bound = beforeBound(timeout, took, budget.seconds());
        start = clock.getAsLong();
        FileRun before = runner.runAlone(base.dir, f, filter, bound);
        budget.spend(clock.getAsLong() - start);
        all = unended(before, filter, "before", bound);
        for (JUnitXml.Case c : left) {
            String k = key(c), no = all != null ? all : reported(before, k, "before");
            if (no != null) why.put(k, no);
            else got.put(k, before.cases().stream().filter(x -> key(x).equals(k)).findFirst().orElseThrow());
        }
    }

    private static String unended(FileRun run, String filter, String side, int bound) {
        if (run.timedOut() > 0) return "run alone (" + filter + "), it did not end " + side + " the change within " + bound + " s";
        if (!gave(run)) return "run alone " + side + " the change, it gave no result: " + run.problem();
        if (run.cases().isEmpty()) return "run alone " + side + " the change, it reported no test";
        return null;
    }

    private static String reported(FileRun run, String key, String side) {
        long n = run.cases().stream().filter(c -> key(c).equals(key)).count();
        if (n == 0) return "run alone " + side + " the change, it reported no test of this name";
        if (n > 1) return "run alone " + side + " the change, it reported more than one test of this name";
        return null;
    }

    private static void restore(Change change, Runner runner, Scratch base, List<String> files) {
        try {
            base.write(change.head, files);
            runner.forgetBuilt(base.dir, files);
        } catch (java.io.IOException e) {
            throw new sdlc.core.UsageException("cannot prepare the checkout for a repeat run: " + e.getMessage());
        }
    }

    // Surefire names a parameterized test's runs by place, arguments or display name, and JUnit 4 may write a value where the place
    // would be: a name says only that the report gave it.
    private record Names(List<JUnitXml.Case> gone, List<JUnitXml.Case> fresh, List<Flip> off, long on, List<Flip> asBefore, long r0, long s0, long r1, long s1, Map<String, String> shown,
            Listing listing) {
        List<JUnitXml.Case> freshSkipped() {
            return fresh.stream().filter(c -> out(c) == Out.SKIPPED).toList();
        }

        boolean changed() {
            return !gone.isEmpty() || !fresh.isEmpty() || !off.isEmpty() || on > 0;
        }

        Set<String> moved() {
            Set<String> out = new LinkedHashSet<>();
            for (JUnitXml.Case c : gone) out.add(key(c));
            for (JUnitXml.Case c : fresh) out.add(key(c));
            return out;
        }
    }

    private record Flip(String key, String name, long tests) {}

    private static Names names(List<JUnitXml.Case> old, List<JUnitXml.Case> now) {
        Map<String, String> shown = shown(old, now);
        Map<String, List<JUnitXml.Case>> was = byKey(old), is = byKey(now);
        Set<String> keys = new java.util.TreeSet<>(was.keySet());
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

    private static long ran(List<JUnitXml.Case> cases) {
        return cases.stream().filter(c -> out(c) != Out.SKIPPED).count();
    }

    private record Skips(List<Verdict> answer, List<Verdict> listed, boolean fewer, List<String> said) {}

    private static Skips skips(String f, Names n, String by) {
        List<Verdict> answer = new ArrayList<>(), listed = new ArrayList<>();
        for (Flip s : n.asBefore()) listed.add(new Verdict(f, s.name(), Kind.EXISTING_SKIPPED, "skipped before the change too", by, (int) s.tests(), Cover.of(List.of(s.key()), Side.OLD, Side.AFTER)));
        List<JUnitXml.Case> freshOff = n.freshSkipped();
        if (n.off().isEmpty() && (freshOff.isEmpty() || n.r0() == 0)) {
            for (JUnitXml.Case c : freshOff) listed.add(new Verdict(f, shown(n.shown(), key(c)), Kind.SKIPPED, "skipped after the change; no test of the file ran before it", by,
                    Cover.of(List.of(key(c)), Side.OLD, Side.AFTER)));
            return new Skips(answer, listed, false, List.of());
        }
        Set<String> shows = new LinkedHashSet<>();
        for (Flip s : n.off()) shows.add(s.key());
        for (JUnitXml.Case c : freshOff) shows.add(key(c));
        Cover cover = Cover.change(shows);
        String counts = counts(n);
        if (n.r1() < n.r0() && n.s1() > n.s0()) {
            long least = Math.min(n.r0() - n.r1(), n.s1() - n.s0());
            if (n.listing().failures() == null) return new Skips(answer, listed, true, List.of());
            answer.add(new Verdict(f, "", Kind.UNKNOWN, counts + skippedNow(n) + ". But " + n.listing().failures() + ": whether a test that ran does not run now is not known", by,
                    (int) least, cover));
            return new Skips(answer, listed, false, List.of(Kind.UNKNOWN + " " + least + " failures " + n.listing().failedBefore() + " " + n.listing().failedAfter() + " " + tally(n)));
        }
        String story, why;
        if (n.s1() > n.s0()) why = "more skipped";
        else if (!n.off().isEmpty()) why = "off, and " + n.fresh().size() + " fresh, " + n.gone().size() + " gone, " + n.on() + " on";
        else why = "skipped under new names";
        if (n.s1() > n.s0()) story = ". So more are skipped while as many or more run: it cannot be told whether skipped tests were added, or a test that ran was switched off and another added";
        else if (!n.off().isEmpty()) story = ", and its list of cases changed as well (" + n.fresh().size() + " names it did not report, " + n.gone().size() + " it no longer reports, "
                + n.on() + " skipped before and run now). It cannot be told whether a test that ran was switched off, or cases changed names, as cases named by their place "
                + "do when a row is put in or taken out before them";
        else story = ". The file had tests that ran, so it cannot be told whether " + (freshOff.size() == 1 ? "this is a new test that is skipped, or a test" : "these are new tests that are skipped, or tests")
                + " that ran before under another name and " + (freshOff.size() == 1 ? "is" : "are") + " switched off";
        long candidates = n.off().stream().mapToLong(Flip::tests).sum() + freshOff.size();
        answer.add(new Verdict(f, "", Kind.UNKNOWN, counts + skippedNow(n) + story, by, (int) candidates, cover));
        return new Skips(answer, listed, false, List.of(Kind.UNKNOWN + " " + candidates + " " + why + " " + tally(n)));
    }

    private static String tally(Names n) {
        return "ran " + n.r0() + ", skipped " + n.s0() + "; runs " + n.r1() + ", skips " + n.s1();
    }

    private static String counts(Names n) {
        return "the file runs " + n.r1() + " of its tests where it ran " + n.r0() + " before the change, and skips " + n.s1() + " where it skipped " + n.s0();
    }

    private static String skippedNow(Names n) {
        List<String> parts = new ArrayList<>();
        if (!n.off().isEmpty()) parts.add("skipped now under " + (n.off().size() == 1 ? "a name" : "names") + " that ran before: " + shortList(n.off().stream().map(Flip::name).toList()));
        List<JUnitXml.Case> freshOff = n.freshSkipped();
        if (!freshOff.isEmpty()) parts.add((parts.isEmpty() ? "skipped " : "") + "under " + (freshOff.size() == 1 ? "a name" : "names") + " the file did not report before: " + shortList(shown(n.shown(), freshOff)));
        return parts.isEmpty() ? "" : " (" + String.join("; ", parts) + ")";
    }

    private static List<String> said(FileRun old, FileRun after, String f, String by) {
        if (!gave(old) || !gave(after)) return null;
        Names n = names(old.cases(), after.cases());
        Skips s = skips(f, n, by);
        return signature(s, n);
    }

    private static List<String> signature(Skips s, Names n) {
        List<String> out = new ArrayList<>(s.said());
        if (s.fewer()) out.add("fewer run, more skipped: " + tally(n));
        for (Flip f : n.off().stream().sorted(java.util.Comparator.comparing(Flip::key)).toList()) out.add("off " + f.key() + " " + f.tests());
        for (String k : n.freshSkipped().stream().map(Witness::key).sorted().toList()) out.add("skipped under a new name " + k);
        out.add(n.gone().size() + " gone, " + n.fresh().size() + " fresh");
        return out;
    }

    private static Map<String, String> shown(List<JUnitXml.Case> old, List<JUnitXml.Case> now) {
        List<JUnitXml.Case> all = new ArrayList<>(old);
        all.addAll(now);
        Map<String, Set<String>> suites = new LinkedHashMap<>();
        for (JUnitXml.Case c : all) suites.computeIfAbsent(c.name(), k -> new LinkedHashSet<>()).add(c.suite());
        Map<String, String> out = new LinkedHashMap<>();
        for (JUnitXml.Case c : all) out.putIfAbsent(key(c), suites.get(c.name()).size() > 1 ? c.suite() + "." + c.name() : c.name());
        return out;
    }

    private static Map<String, String> shownAll(Seen seen, String f, boolean withOld) {
        List<JUnitXml.Case> old = new ArrayList<>(), now = new ArrayList<>();
        if (withOld) for (FileRun r : seen.of(Side.OLD, f)) if (gave(r)) old.addAll(r.cases());
        for (FileRun r : seen.of(Side.AFTER, f)) if (gave(r)) now.addAll(r.cases());
        if (withOld) for (FileRun r : seen.of(Side.OLD_TESTS, f)) if (gave(r)) old.addAll(r.cases());
        return shown(old, now);
    }

    private static String shown(Map<String, String> shown, String key) {
        return shown.getOrDefault(key, name(key));
    }

    private static List<String> shown(Map<String, String> shown, List<JUnitXml.Case> cases) {
        return cases.stream().map(c -> shown(shown, key(c))).toList();
    }

    // unended: of the other tests, those with no result before the change (they are counted with the others, as nothing is known of them)
    private record Count(Names n, List<JUnitXml.Case> passBoth, long other, long least, long unended) {}

    private static Count count(String f, Names n, Map<String, Object> read, FileRun a, FileRun b, String by) {
        List<JUnitXml.Case> passBoth = new ArrayList<>();
        long clean = 0;
        for (JUnitXml.Case c : a.cases()) {
            Object x = read.get(key(c));
            if (x == Mark.FRESH) {
                clean++;
                passBoth.add(c);
            } else if (x instanceof Verdict v && v.kind() == Kind.EXISTING_PASSES_ON_BOTH) clean++;
        }
        Skips s = skips(f, n, by);
        long asBefore = n.asBefore().stream().mapToLong(Flip::tests).sum();
        long other = a.cases().size() - clean - (s.answer().isEmpty() && !s.fewer() ? asBefore : 0);
        long least = n.listing().old() != null ? 0 : Math.max(0, n.fresh().size() - n.gone().size() - other);
        long unended = a.cases().stream().filter(c -> status(b, key(c)) == Out.NO_RESULT).count();
        return new Count(n, passBoth, other, least, Math.min(other, unended));
    }

    private static List<Verdict> newTests(String f, List<Count> counts, Set<String> freshEvery, String by) {
        if (counts.stream().allMatch(c -> c == null || c.passBoth().isEmpty())) return List.of();
        Set<String> fresh = new LinkedHashSet<>();
        for (Count c : counts) if (c != null) for (JUnitXml.Case x : c.n().fresh()) fresh.add(key(x));
        Cover cover = Cover.change(fresh);
        long min = counts.stream().mapToLong(c -> c == null ? 0 : c.least()).min().orElse(0), max = counts.stream().mapToLong(c -> c == null ? 0 : c.least()).max().orElse(0);
        if (min > 0) {
            Count at = counts.stream().filter(c -> c.least() == min).findFirst().orElseThrow();
            return List.of(say(f, at, max > min ? " (of every run, the one that gives the fewest)" : "", cover, by).get(0));
        }
        if (max > 0) {
            Count at = counts.stream().filter(c -> c != null && c.least() > 0).findFirst().orElseThrow();
            Verdict v = say(f, at, "", cover, by).get(0);
            return List.of(new Verdict(f, "", Kind.UNSTABLE, v.detail().substring(0, v.detail().indexOf(". Under names"))
                    + ", but what the file reports, after the change or as it was, is not the same in every run", by, v.tests(), Cover.of(fresh, Side.OLD, Side.BEFORE, Side.AFTER)));
        }
        Count at = counts.stream().filter(c -> c != null && !c.passBoth().isEmpty()).findFirst().orElseThrow();
        return say(f, at, "", cover, by).stream().filter(v -> freshEvery.stream().anyMatch(k -> v.cover().change().contains(k))).toList();
    }

    private static List<Verdict> say(String f, Count c, String which, Cover cover, String by) {
        Names n = c.n();
        List<JUnitXml.Case> passBoth = c.passBoth();
        long other = c.other(), p = passBoth.size(), g = n.gone().size(), fr = n.fresh().size(), least = c.least(), u = c.unended(), seen = other - u;
        List<Verdict> out = new ArrayList<>();
        String counts = "the file reports " + tests(fr) + " it did not have" + (g > 0 ? " and no longer reports " + g + " it had" : "")
                + (seen > 0 ? ", and " + seen + " of its tests " + (seen == 1 ? "does" : "do") + " not pass on both sides" : "")
                + (u > 0 ? ", and " + u + " of its tests " + (u == 1 ? "has" : "have") + " no result before the change" : "");
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
                + (fr - g == 1 ? "test" : "tests") + " then being among the " + other + " that do not pass on both sides" + (u > 0 ? " or have no result before the change" : "") + ")");
        if (n.listing().old() != null) why.add("a test the file had that its report as it was did not list: " + n.listing().old());
        List<String> names = shown(n.shown(), passBoth);
        for (int i = 0; i < passBoth.size(); i++) {
            out.add(new Verdict(f, names.get(i), Kind.UNKNOWN, "passes before and after, but it cannot be told whether it is a new test: " + counts
                    + ". It may be a new test that does not test the change, or a test the file had: " + String.join(", or ", why), by,
                    Cover.of(List.of(key(passBoth.get(i))), Side.BEFORE, Side.AFTER)));
        }
        return out;
    }

    private static List<Verdict> lost(String f, Names n, Seen seen, Fourth fourth, long t, long u, Set<String> spoke, Integrity.Move mv, String by) {
        List<FileRun> olds = seen.of(Side.OLD, f), afters = seen.of(Side.AFTER, f);
        List<FileRun> okOld = olds.stream().filter(Witness::gave).toList(), okAfter = afters.stream().filter(Witness::gave).toList();
        if (okOld.isEmpty() || okAfter.isEmpty()) return List.of();
        boolean whole = okOld.size() == olds.size() && okAfter.size() == afters.size();
        long minR0 = okOld.stream().mapToLong(r -> passed(r.cases())).min().orElse(0), maxR0 = okOld.stream().mapToLong(r -> passed(r.cases())).max().orElse(0);
        long minP0 = okOld.stream().mapToLong(r -> passes(r.cases())).min().orElse(0), maxP0 = okOld.stream().mapToLong(r -> passes(r.cases())).max().orElse(0);
        long minR1 = okAfter.stream().mapToLong(r -> ran(r.cases())).min().orElse(0), maxR1 = okAfter.stream().mapToLong(r -> ran(r.cases())).max().orElse(0);
        long most = maxP0 - minR1, total = minR0 - maxR1, withAgain = minP0 - maxR1;
        if (most <= 0) return List.of();
        spoke.add(f);
        String counts = "the file as it was passed " + minR0 + " of its tests, and after the change it runs " + maxR1
                + (maxR0 != minR0 || maxR1 != minR1 ? " (of every run as it was the one that passed the fewest, and of every run after the change the one that runs the most)" : "");
        String shows = shows(n);
        if (okOld.stream().anyMatch(r -> failing(r.cases()))) {
            long first = Math.max(1, passed(olds.get(0).cases()) - ran(afters.get(0).cases()));
            return List.of(new Verdict(f, "", Kind.UNKNOWN, counts + shows + ". But the file as it was reported failures" + FAILURES + ": a file that did not pass as it was is not "
                    + "said to have lost tests, so whether a test that ran does not run now is not known", by, (int) first, Cover.change(shownKeys(n))));
        }
        Set<String> oldKeys = new LinkedHashSet<>();
        for (FileRun r : okOld) for (JUnitXml.Case c : r.cases()) if (out(c) == Out.PASSED || out(c) == Out.RETRIED) oldKeys.add(key(c));
        List<FileRun> fourths = seen.of(Side.OLD_TESTS, f);
        String why4 = null, unusable = null;
        long r4 = Long.MAX_VALUE, usable = 0, bad = 0;
        if (fourths.isEmpty() && mv != null) why4 = "is not run for a moved file";
        else if (fourths.isEmpty()) {
            FileRun set = fourth.first().get(f);
            why4 = set != null && set.problem() != null ? set.problem() : "was not run, as the first runs lost no test";
        }
        for (FileRun r : fourths) {
            String no = null;
            if (!gave(r)) no = r == null ? "gave no result" : r.problem();
            else {
                List<String> stand = r.cases().stream().filter(c -> failed(out(c)) && !oldKeys.contains(key(c))).map(c -> shown(n.shown(), key(c))).toList();
                if (!stand.isEmpty()) no = "reported a failure that is not one of its old tests (" + shortList(stand) + ")" + FAILURES;
            }
            if (no != null) {
                bad++;
                if (unusable == null) unusable = no;
                continue;
            }
            usable++;
            r4 = Math.min(r4, r.cases().stream().filter(c -> out(c) != Out.SKIPPED && out(c) != Out.RETRIED).count());
        }
        if (!fourths.isEmpty() && usable == 0) why4 = unusable;
        String t4 = t > 0 ? ". At most " + t + (t == 1 ? " of them is an old test" : " of them are old tests") + " said above to be taken out of the way, so at least " + (total - t) + " more" : "";
        if (whole && total - t > 0) {
            String head = counts + ": at least " + total + (total == 1 ? " test that ran before the change does" : " tests that ran before the change do") + " not run now" + shows;
            if (why4 == null && r4 <= maxR1) {
                if (fourth.unclear().containsKey(f)) return List.of();
                return List.of(new Verdict(f, "", Kind.UNKNOWN, head + ". But as it was, on the code after the change, the file runs " + r4 + ", no more than it runs now, so the change "
                        + "of the code, not of the tests, may have taken " + (total == 1 ? "it" : "them") + t4, by, (int) (total - t), Cover.change(shownKeys(n))));
            }
            StringBuilder d = new StringBuilder(head);
            if (maxR0 - minR1 > total) d.append(", and up to ").append(maxR0 - minR1).append(" in some runs: what the file reports is not the same in every run");
            if (why4 == null && r4 < maxR0) d.append(". As it was, on the code after the change, the file runs ").append(r4).append(", and as the change has it ").append(maxR1)
                    .append(": the change of the code may have taken some of them, and on that code the change of the tests runs fewer tests than the file as it was");
            if (mv != null) d.append(". The file moved from ").append(mv.from()).append(" and is not run as it was on the code after the change, so whether the move or the change of ")
                    .append("the code took them is not known; either way they ran before the change and do not run now");
            else if (why4 != null) d.append(". The file as it was gave no report usable for this count on the code after the change (there it ").append(why4)
                    .append("), so whether the change of the tests or of the code took them is not known; either way they ran before the change and do not run now");
            else if (bad > 0) d.append(". In ").append(bad).append(" of the ").append(fourths.size()).append(" runs of the file as it was on the code after the change it ").append(unusable)
                    .append("; the count there is that of the other runs");
            d.append(t4);
            if (okAfter.stream().anyMatch(r -> failing(r.cases()))) d.append(". After the change the file reports failures, and a build that stops after a failure "
                    + "(Surefire's skipAfterFailureCount) skips the tests after it, so some of them may not run for that");
            return List.of(new Verdict(f, "", Kind.NOT_RUN_NOW, d.toString(), by, (int) (total - t), Cover.FILE));
        }
        if (whole && withAgain - t > 0) {
            return List.of(new Verdict(f, "", Kind.UNKNOWN, counts + ", and passed " + (minP0 - minR0) + " more only when the build ran " + (minP0 - minR0 == 1 ? "it" : "them") + " again"
                    + shows + ": a case that passed only when run again may be a set-up that failed and then passed, not a test, so whether a test that ran does not run now "
                    + "is not known", by, (int) (withAgain - t), Cover.change(shownKeys(n))));
        }
        if (!whole || most - t - u > 0) {
            String which = !whole ? ", and a run of the file " + (okOld.size() < olds.size() && okAfter.size() < afters.size() ? "as it was and one after the change"
                    : okOld.size() < olds.size() ? "as it was" : "after the change") + " gave no result" : "";
            String ranges = "the file as it was passed " + range(minR0, maxR0) + " of its tests, and after the change it runs " + range(minR1, maxR1)
                    + (minR0 != maxR0 || minR1 != maxR1 ? ", from run to run" : "");
            return List.of(new Verdict(f, "", Kind.UNSTABLE, ranges + shows + which + ": whether tests that ran before the change do not run now is not the same in every run: "
                    + "what the file reports, after the change or as it was, differs from run to run", by, (int) Math.max(1, most - t - u),
                    Cover.change(shownKeys(n)).and(varied(seen, f, PASSES, Side.OLD)).and(varied(seen, f, RUNS, Side.AFTER))));
        }
        return List.of();
    }

    private static Set<String> shownKeys(Names n) {
        if (n == null) return Set.of();
        Set<String> out = new java.util.TreeSet<>(n.moved());
        for (Flip f : n.off()) out.add(f.key());
        return out;
    }

    private static final java.util.function.Function<List<JUnitXml.Case>, Object> PASSES = cs -> List.of(passed(cs), passes(cs)), RUNS = Witness::ran,
            SKIPS = cs -> List.of((long) cs.size(), skipped(cs)), CASES = cs -> (long) cs.size();

    private static Cover varied(Seen seen, String f, java.util.function.Function<List<JUnitXml.Case>, Object> measure, Side... ways) {
        Map<Side, Set<String>> r = new EnumMap<>(Side.class);
        for (Side way : ways) {
            List<FileRun> all = seen.of(way, f);
            if (all.size() < 2) continue;
            List<Map<String, List<JUnitXml.Case>>> each = all.stream().filter(Witness::gave).map(x -> byKey(x.cases())).toList();
            Set<String> keys = new java.util.TreeSet<>(), out = new java.util.TreeSet<>();
            if (each.size() < all.size()) out.add("");
            for (Map<String, List<JUnitXml.Case>> m : each) keys.addAll(m.keySet());
            for (String k : keys) if (each.stream().map(m -> measure.apply(m.getOrDefault(k, List.of()))).distinct().count() > 1) out.add(k);
            r.put(way, out);
        }
        return new Cover(Set.of(), r);
    }

    private static String shows(Names n) {
        if (n == null) return "";
        List<String> parts = new ArrayList<>();
        if (!n.gone().isEmpty()) parts.add("no longer reported: " + shortList(shown(n.shown(), n.gone())));
        if (!n.off().isEmpty()) parts.add("skipped now under " + (n.off().size() == 1 ? "a name" : "names") + " that ran before: " + shortList(n.off().stream().map(Flip::name).toList()));
        List<JUnitXml.Case> ran = n.fresh().stream().filter(c -> out(c) != Out.SKIPPED).toList(), off = n.freshSkipped();
        if (!ran.isEmpty()) parts.add("reported for the first time: " + shortList(shown(n.shown(), ran)));
        if (!off.isEmpty()) parts.add("skipped under " + (off.size() == 1 ? "a name" : "names") + " the file did not report before: " + shortList(shown(n.shown(), off)));
        return parts.isEmpty() ? "" : " (" + String.join("; ", parts) + ")";
    }

    private static String range(long least, long most) {
        return least == most ? String.valueOf(least) : least + " to " + most;
    }

    private static boolean gave(FileRun r) {
        return r != null && r.problem() == null;
    }

    private static long passed(List<JUnitXml.Case> cases) {
        return cases.stream().filter(c -> out(c) == Out.PASSED).count();
    }

    private static long passes(List<JUnitXml.Case> cases) {
        return cases.stream().filter(c -> out(c) == Out.PASSED || out(c) == Out.RETRIED).count();
    }

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
                        cover.and(varied(seen, f, CASES, Side.OLD, Side.AFTER))));
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

    private static List<Verdict> broken(String f, Names n, List<FileRun> runs, Set<String> passing, Fourth fourth, Map<String, Long> said, Map<String, Long> wavered,
            Set<String> judged, String by) {
        List<Verdict> out = new ArrayList<>();
        FileRun a = runs.get(0);
        Map<String, List<JUnitXml.Case>> now = byKey(a.cases());
        List<String> out1 = new ArrayList<>(), running = new ArrayList<>(), unsure = new ArrayList<>();
        for (String k : fourth.broke().getOrDefault(f, Set.of())) {
            boolean gone = notRun(a, k);
            if (runs.stream().anyMatch(r -> !gave(r) || notRun(r, k) != gone)) unsure.add(k);
            else if (gone) out1.add(k);
            else running.add(k);
        }
        List<JUnitXml.Case> rewrites = List.of();
        for (FileRun r : runs) {
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
            out.add(new Verdict(f, shown(n.shown(), k), Kind.UNSTABLE, WAVERED, by, Cover.of(List.of(k), Side.OLD, Side.OLD_TESTS)));
            judged.add(f + "\n" + k);
        }
        for (Map.Entry<String, String> c : fourth.companions().getOrDefault(f, Map.of()).entrySet()) {
            out.add(new Verdict(f, shown(n.shown(), c.getKey()), Kind.UNKNOWN, "passed before the change; as the test was, on the code after the change, it fails beside the other "
                    + "edited test files as they were, and " + c.getValue() + " beside them as the change has them, so its failure there may come from the change of those files, which is "
                    + "part of the change: whether the change broke it is not known", by, Cover.of(List.of(c.getKey()), read)));
            judged.add(f + "\n" + c.getKey());
        }
        long b = out1.size(), m = rewrites.size();
        Cover gone = Cover.of(out1, read);
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

    private static final String FAILURES = " (a failure outside a test's body, in its set-up, tear-down or loading, may be reported in place of the file's tests or as a case of its own)";
    private static final String NONE = " (a class that stops before its tests, as one does whose set-up meets a failed assumption, may report no case at all)";
    private static final String HIDES = " (a skipped class, factory or template is reported as one case, whatever number of tests it holds)";
    private static final String AGAIN = " (a set-up that failed and was run again may be reported as a case of its own, beside or in place of the tests)";

    // JUnit 4 reports a class it cannot set up as one failed case (initializationError), Vitest a failed afterAll as a case of its own.
    // One skipped case may stand for many tests (an ignored JUnit 4 class, a disabled JUnit 5 factory); so may an empty report.
    private record Listing(List<String> before, List<String> after, boolean failedBefore, boolean failedAfter) {
        String old() {
            return say(before, false);
        }

        String either() {
            List<String> all = new ArrayList<>();
            if (failedBefore && failedAfter) {
                all.add("the file as it was and after the change reported failures" + FAILURES);
                for (String w : before) if (!w.endsWith(FAILURES)) all.add(w);
                for (String w : after) if (!w.endsWith(FAILURES)) all.add(w);
            } else {
                all.addAll(before);
                all.addAll(after);
            }
            return say(all, !before.isEmpty() && !after.isEmpty());
        }

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
                for (String x : List.of(FAILURES, NONE, HIDES, AGAIN)) {
                    final int at = i;
                    if (w.endsWith(x) && why.subList(at + 1, why.size()).stream().anyMatch(later -> later.endsWith(x))) w = w.substring(0, w.length() - x.length());
                }
                parts.add(w);
            }
            return String.join(", and ", parts) + ", so " + (both ? "its reports" : "that report") + " may not list every test";
        }
    }

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
        Set<String> keys = new java.util.TreeSet<>(was.keySet());
        keys.addAll(is.keySet());
        List<String> fewer = new ArrayList<>(), more = new ArrayList<>();
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

    private static List<String> retried(Map<String, List<JUnitXml.Case>> cases, Map<String, String> shown) {
        return cases.entrySet().stream().filter(e -> e.getValue().stream().anyMatch(c -> out(c) == Out.RETRIED)).map(e -> shown(shown, e.getKey())).toList();
    }

    private static long skipped(List<JUnitXml.Case> cases) {
        return cases.size() - ran(cases);
    }

    private static boolean failing(List<JUnitXml.Case> cases) {
        return cases.stream().anyMatch(c -> failed(out(c)));
    }

    private static boolean notRun(FileRun run, String key) {
        return run.cases().stream().filter(c -> key(c).equals(key)).allMatch(c -> out(c) == Out.SKIPPED);
    }

    private record Ledger(Map<String, String> change, Map<Side, Map<String, String>> repeat) {
        Set<String> unsteady() {
            Set<String> out = new LinkedHashSet<>();
            for (Side s : List.of(Side.OLD, Side.AFTER)) out.addAll(repeat.getOrDefault(s, Map.of()).keySet());
            return out;
        }
    }

    private static Ledger ledger(String f, boolean edited, Seen seen, Map<String, String> sh) {
        Map<String, String> change = new java.util.TreeMap<>();
        if (edited) {
            for (FileRun o : seen.of(Side.OLD, f)) {
                if (!gave(o)) continue;
                for (FileRun a : seen.of(Side.AFTER, f)) {
                    if (!gave(a)) continue;
                    Map<String, List<Out>> was = outs(o), is = outs(a);
                    Set<String> keys = new java.util.TreeSet<>(was.keySet());
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
            Map<String, String> here = new java.util.TreeMap<>();
            List<FileRun> ok = all.stream().filter(Witness::gave).toList();
            if (!ok.isEmpty() && ok.size() < all.size()) here.put("", "the file " + side.words + " (one run gave no result and another gave one)");
            List<Map<String, List<Out>>> each = ok.stream().map(Witness::outs).toList();
            Set<String> keys = new java.util.TreeSet<>();
            for (Map<String, List<Out>> m : each) keys.addAll(m.keySet());
            for (String k : keys) {
                List<List<Out>> seq = each.stream().map(m -> m.getOrDefault(k, List.of())).toList();
                if (seq.stream().distinct().count() > 1) here.put(k, shown(sh, k) + " (" + side.words + ": " + String.join(", ", seq.stream().map(Witness::words).toList()) + ")");
            }
            if (!here.isEmpty()) repeat.put(side, here);
        }
        return new Ledger(change, repeat);
    }

    private static Map<String, List<Out>> outs(FileRun run) {
        Map<String, List<Out>> out = new LinkedHashMap<>();
        for (JUnitXml.Case c : run.cases()) out.computeIfAbsent(key(c), k -> new ArrayList<>()).add(out(c));
        for (List<Out> l : out.values()) l.sort(null);
        return out;
    }

    private static String words(List<Out> outs) {
        return outs == null || outs.isEmpty() ? "not reported" : String.join(" and ", outs.stream().map(o -> o.word).toList());
    }

    private static List<Verdict> reconcile(String f, Ledger l, List<Verdict> said, String by, int single) {
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
                which.size(), Cover.NONE).aloneBecause(l.repeat().containsKey(Side.BEFORE) ? single : 0));
        return out;
    }

    private static String name(String key) {
        return key.substring(key.indexOf('#') + 1);
    }

    private static String had(Names n) {
        List<JUnitXml.Case> gone = n.gone(), fresh = n.fresh();
        return "the file no longer reports " + tests(gone.size()) + " under the " + (gone.size() == 1 ? "name" : "names") + " it had (" + shortList(shown(n.shown(), gone)) + "), and reports "
                + (fresh.isEmpty() ? "none under a name it did not have" : fresh.size() + " under " + (fresh.size() == 1 ? "a name" : "names") + " it did not have (" + shortList(shown(n.shown(), fresh)) + ")");
    }

    private static String tests(long n) {
        return n + (n == 1 ? " test" : " tests");
    }

    private static String shortList(List<String> names) {
        if (names.size() <= 10) return String.join(", ", names);
        return String.join(", ", names.subList(0, 10)) + " and " + (names.size() - 10) + " more";
    }

    private static Out status(FileRun run, String key) {
        if (!gave(run)) return null;
        List<Out> found = run.cases().stream().filter(c -> key(c).equals(key)).map(Witness::out).toList();
        return found.size() == 1 ? found.get(0) : null;
    }

    static boolean sensitive(Kind k) {
        return k == Kind.SENSITIVE || k == Kind.SENSITIVE_BY_ERROR;
    }

    // Thrown where the code a test calls did not exist or did not link; and thrown for a cause outside the test (its class's set-up, the machine, a time limit).
    private static final Set<String> ABSENT = Set.of("ClassNotFoundException", "NoSuchMethodException", "NoSuchFieldException", "LinkageError", "NoClassDefFoundError",
            "NoSuchMethodError", "NoSuchFieldError", "AbstractMethodError", "IllegalAccessError", "InstantiationError", "IncompatibleClassChangeError", "ClassFormatError",
            "UnsupportedClassVersionError", "ClassCircularityError", "VerifyError", "BootstrapMethodError", "UnsatisfiedLinkError").stream().map(t -> "java.lang." + t)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> OUTSIDE = Set.of("java.lang.ExceptionInInitializerError", "java.lang.VirtualMachineError", "java.lang.OutOfMemoryError",
            "java.lang.StackOverflowError", "java.lang.InternalError", "java.lang.UnknownError", "java.util.concurrent.TimeoutException", "org.junit.runners.model.TestTimedOutException");

    private static Verdict threw(String f, String name, JUnitXml.Case p, String by) {
        String type = p.type().strip();
        if (ABSENT.contains(type)) return Verdict.beforeFailed(f, name, thrown(p), by);
        if (type.isEmpty() || OUTSIDE.contains(type)) return new Verdict(f, name, Kind.UNKNOWN, thrown(p), by, Cover.NONE);
        return new Verdict(f, name, Kind.SENSITIVE_BY_ERROR, "threw " + shortType(p) + " before the change", by, Cover.NONE);
    }

    private static String thrown(JUnitXml.Case p) {
        return "before the change it threw " + shortType(p) + ", not an assertion";
    }

    private static String message(FileRun run, String key) {
        return run.cases().stream().filter(c -> key(c).equals(key)).findFirst().map(JUnitXml.Case::message).orElse("");
    }

    private static String type(FileRun run, String key) {
        return run.cases().stream().filter(c -> key(c).equals(key)).findFirst().map(c -> c.type().strip()).orElse("");
    }

    private static String threw(FileRun run, String key) {
        return thrown(run.cases().stream().filter(c -> key(c).equals(key)).findFirst().orElseThrow());
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
