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
 * The executed statement about a change's own tests: each test the change adds or edits is run
 * before the change (the base commit, with only the change's test files laid over it) and after
 * it. A test that fails before and passes after is sensitive to the change; one that passes on
 * both sides does not test it.
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
        /** Skipped after the change. */
        SKIPPED
    }

    /** name is "" when the verdict is about the whole file. */
    record Verdict(String file, String name, Kind kind, String detail, String runner) {}

    record Result(List<Verdict> verdicts, List<String> unsupported, int runs) {
        long count(Kind k) {
            return verdicts.stream().filter(v -> v.kind() == k).count();
        }
    }

    static Result run(Change change, List<Runner> runners, int runs, int timeoutSeconds) {
        List<Verdict> verdicts = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        try (Scratch head = Scratch.of(change.head, "after")) {
            Map<Runner, List<String>> owned = new LinkedHashMap<>();
            Set<String> support = new LinkedHashSet<>();
            for (String path : change.paths("AM")) {
                Runner owner = runners.stream().filter(r -> r.runs(head.dir, path)).findFirst().orElse(null);
                if (owner != null) owned.computeIfAbsent(owner, k -> new ArrayList<>()).add(path);
                else if (Integrity.testNamed(path)) unsupported.add(path);
                if (runners.stream().anyMatch(r -> r.support(path))) support.add(path);
            }
            if (owned.isEmpty()) return new Result(verdicts, unsupported, 0);
            try (Scratch base = Scratch.of(change.base, "before")) {
                base.write(change.head, new ArrayList<>(support));
                // a fixture or helper the change deletes is gone before the change too, as the tests now expect
                for (String gone : change.paths("D")) if (runners.stream().anyMatch(r -> r.support(gone))) java.nio.file.Files.deleteIfExists(base.dir.resolve(gone));
                for (Map.Entry<Runner, List<String>> e : owned.entrySet()) {
                    verdicts.addAll(judge(change, e.getKey(), e.getValue(), base, head, Math.max(1, runs), timeoutSeconds));
                }
            }
        } catch (java.io.IOException e) {
            throw new sdlc.core.UsageException("cannot prepare the checkouts for the test runs: " + e.getMessage());
        }
        return new Result(verdicts, unsupported, Math.max(1, runs));
    }

    private static String key(JUnitXml.Case c) {
        return c.suite() + "#" + c.name();
    }

    private static List<Verdict> judge(Change change, Runner runner, List<String> files, Scratch base, Scratch head, int runs, int timeout) {
        List<Verdict> out = new ArrayList<>();
        Map<String, FileRun> after = runner.run(head.dir, files, timeout);
        Map<String, FileRun> before = new LinkedHashMap<>(runner.run(base.dir, files, timeout));
        // One test of new code stops every test of its module from compiling before the change. Those files are
        // then run one at a time, each with the other changed tests put back as the base has them.
        Set<String> alone = new LinkedHashSet<>();
        for (String f : files) if (before.get(f) != null && before.get(f).broken()) alone.add(f);
        if (alone.size() < 2 || alone.size() > MAX_ALONE) alone.clear();
        for (String f : alone) before.put(f, runAlone(change, runner, files, f, base, timeout));
        // candidates: failed before by assertion, passed after. They are run again to rule out a test that fails at random.
        Map<String, Set<String>> candidates = new LinkedHashMap<>();
        for (String f : files) {
            FileRun a = after.get(f), b = before.get(f);
            Set<String> existing = TestNames.of(f, change.before(f));
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
                boolean old = existing.contains(TestNames.plain(f, c.name()));
                JUnitXml.Case p = was.get(key(c));
                if (twice.contains(key(c))) {
                    if (seen.add(key(c))) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "more than one test reports this name, so they cannot be told apart", runner.name()));
                } else if (c.status() == Status.FLAKY) out.add(new Verdict(f, c.name(), Kind.UNSTABLE, "the build ran it again and got another result", runner.name()));
                else if (c.status() == Status.SKIPPED) out.add(new Verdict(f, c.name(), Kind.SKIPPED, "skipped after the change", runner.name()));
                else if (c.status() != Status.PASSED) out.add(new Verdict(f, c.name(), Kind.FAILS_AFTER, describe(c), runner.name()));
                else if (b == null || b.problem() != null) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "before the change the file " + (b == null ? "gave no result" : b.problem()), runner.name()));
                else if (p == null) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "was not found before the change", runner.name()));
                else if (p.status() == Status.PASSED) out.add(new Verdict(f, c.name(), old ? Kind.EXISTING_PASSES_ON_BOTH : Kind.PASSES_ON_BOTH, "", runner.name()));
                else if (p.status() == Status.SKIPPED) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "was skipped before the change", runner.name()));
                else if (p.status() == Status.FLAKY) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "before the change the build ran it again and got another result", runner.name()));
                else if (p.status() == Status.ERRORED) out.add(new Verdict(f, c.name(), Kind.UNKNOWN, "before the change it threw " + shortType(p) + ", not an assertion", runner.name()));
                else candidates.computeIfAbsent(f, k -> new LinkedHashSet<>()).add(key(c));
            }
        }
        if (candidates.isEmpty()) return out;
        List<String> again = new ArrayList<>(candidates.keySet());
        Map<String, String> unstable = new LinkedHashMap<>(); // key -> why
        for (int i = 1; i < runs; i++) {
            Map<String, FileRun> a = runner.run(head.dir, again, timeout);
            List<String> together = again.stream().filter(f -> !alone.contains(f)).toList();
            Map<String, FileRun> b = new LinkedHashMap<>();
            if (!alone.isEmpty()) restore(change, base, files);
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
        return out;
    }

    /** What a runner says when a file cannot be compiled; the Maven runner uses these words. */
    static final String DOES_NOT_COMPILE = "does not compile";
    /** Above this many files, running each alone costs more than it tells. */
    private static final int MAX_ALONE = 8;

    /** Runs one changed test file before the change with every other changed test file as the base has it (or absent, when it is new). */
    private static FileRun runAlone(Change change, Runner runner, List<String> files, String file, Scratch base, int timeout) {
        try {
            for (String other : files) {
                if (other.equals(file)) continue;
                if (change.before(other) == null) java.nio.file.Files.deleteIfExists(base.dir.resolve(other));
                else base.write(change.base, List.of(other));
            }
            base.write(change.head, List.of(file));
            FileRun run = runner.run(base.dir, List.of(file), timeout).get(file);
            return run == null ? FileRun.failed("gave no result") : run;
        } catch (java.io.IOException e) {
            return FileRun.failed("could not be set up alone (" + e.getMessage() + ")");
        }
    }

    /** Puts every changed test file back over the base as the change has it. */
    private static void restore(Change change, Scratch base, List<String> files) {
        try {
            base.write(change.head, files);
        } catch (java.io.IOException e) {
            throw new sdlc.core.UsageException("cannot prepare the checkout for a repeat run: " + e.getMessage());
        }
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
