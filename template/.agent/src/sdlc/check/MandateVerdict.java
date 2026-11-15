package sdlc.check;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import sdlc.check.JUnitXml.Status;
import sdlc.check.Runner.FileRun;
import sdlc.check.Witness.Kind;
import sdlc.core.Ctx;
import sdlc.mandate.Mandate;
import sdlc.mandate.SelfApproval;
import sdlc.util.Proc;

final class MandateVerdict {
    private MandateVerdict() {}

    enum State { SATISFIED("satisfied"), VIOLATED("violated"), UNKNOWN("unknown"), NOT_RUN("not run");
        final String word;

        State(String word) {
            this.word = word;
        }
    }

    record Line(State state, String what, boolean independent, List<String> details) {}

    record Result(String scope, List<String> outside, String approval, List<Line> lines, String unresolved) {
        boolean failed() {
            return scope.equals("OUTSIDE") || lines.stream().anyMatch(l -> l.state() == State.VIOLATED);
        }
    }

    private static final Pattern BUILD = Pattern.compile("(.*/)?(pom\\.xml|mvnw|mvnw\\.cmd|build\\.gradle[^/]*|settings\\.gradle[^/]*|gradle\\.properties|gradlew[^/]*"
            + "|package\\.json|package-lock\\.json|npm-shrinkwrap\\.json|yarn\\.lock|pnpm-lock\\.yaml|pnpm-workspace\\.yaml|bun\\.lockb?|\\.npmrc|\\.yarnrc[^/]*"
            + "|vite\\.config\\.[^/]+|vitest\\.config\\.[^/]+|vitest\\.workspace\\.[^/]+|jest\\.config\\.[^/]+|babel\\.config\\.[^/]+|\\.babelrc[^/]*|tsconfig[^/]*\\.json)"
            + "|(.*/)?(\\.mvn|gradle)/.*");

    static int run(Map<String, String> opt, List<Runner> runners, int runs, int timeout) {
        String id = opt.get("mandate");
        Mandate.requireId(id);
        Mandate.Loaded read = Mandate.loaded(Ctx.root, id);
        Mandate m = read.mandate();
        Change change = Change.of(opt.get("base"));
        Result r = judge(read, change, runners, runs, timeout, opt.containsKey("no-run"));
        print(sdlc.util.SafeOut.stream(System.out), m, change, r);
        return r.failed() ? 1 : 0;
    }

    static Result judge(Mandate.Loaded read, Change change, List<Runner> runners, int runs, int timeout, boolean noRun) {
        Mandate m = read.mandate();
        String own = Mandate.DIR + "/" + m.id() + ".json";
        List<String> outside = new ArrayList<>();
        for (Change.File f : change.files) if (!f.path().equals(own) && !m.inScope(f.path())) outside.add(f.path());

        String blob = read.blob();
        SelfApproval.Record approved = SelfApproval.of(Ctx.root, m.id());
        String approval;
        boolean inForce = false;
        if (approved == null) approval = "not approved";
        else if (!approved.blob().equals(blob)) approval = "edited since it was approved by " + approved.by() + " on " + day(approved.at()) + ": approve it again";
        else {
            approval = "approved by " + approved.by() + " on " + day(approved.at()) + " (their own approval, recorded on this machine)";
            inForce = true;
        }
        String scope = !outside.isEmpty() ? "OUTSIDE" : inForce ? "INSIDE" : "AMENDED";

        List<Line> lines = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, String> e : m.locked().entrySet()) {
            String now = blobAt(change.head, e.getKey());
            if (now == null) broken.add(e.getKey() + " is gone");
            else if (!now.equals(e.getValue())) broken.add(e.getKey() + " is not the file the mandate names");
        }
        if (m.locked().isEmpty()) lines.add(new Line(State.SATISFIED, "the mandate locks no tests", true, List.of()));
        else lines.add(new Line(broken.isEmpty() ? State.SATISFIED : State.VIOLATED, "locked tests are the files the mandate names (" + m.locked().size() + ")", true, broken));

        List<String> build = change.files.stream().map(Change.File::path).filter(p -> BUILD.matcher(p).matches()).toList();
        lines.add(new Line(build.isEmpty() ? State.SATISFIED : State.VIOLATED, "build files and test configuration are unchanged", true, build));

        String unresolved = approved != null && inForce && m.failsIf() != null && approved.covers().isBlank()
                ? "\"this fails if " + m.failsIf() + "\": the approver named no test that covers it" : null;

        if (noRun || !build.isEmpty()) {
            State s = noRun ? State.NOT_RUN : State.UNKNOWN;
            List<String> why = noRun ? List.of() : List.of("the build changed, so no test result of this run can be relied on");
            lines.add(new Line(s, "scenarios found, ran and passed", false, why));
            if (!m.mustStayTrue().isEmpty()) lines.add(new Line(s, "what must stay true passes before and after", false, why));
            lines.add(new Line(s, "the change's own tests fail before it and pass after it", false, why));
            return new Result(scope, outside, approval, lines, unresolved);
        }

        try (Sides sides = new Sides(change, runners)) {
            Set<String> wanted = new LinkedHashSet<>();
            for (Mandate.Scenario s : m.scenarios()) wanted.add(s.path());
            for (Mandate.Truth t : m.mustStayTrue()) wanted.add(t.test());
            Map<Runner, List<String>> owned = new LinkedHashMap<>();
            for (String path : wanted) {
                Runner owner = runners.stream().filter(r -> r.runs(sides.head().dir, path)).findFirst().orElse(null);
                if (owner != null) owned.computeIfAbsent(owner, k -> new ArrayList<>()).add(path);
            }
            Map<String, FileRun> after = new LinkedHashMap<>();
            for (Map.Entry<Runner, List<String>> e : owned.entrySet()) after.putAll(e.getKey().run(sides.head().dir, e.getValue(), timeout));

            List<String> bad = new ArrayList<>(), unknown = new ArrayList<>();
            int passed = 0;
            for (Mandate.Scenario s : m.scenarios()) {
                String shown = s.path() + (s.wholeFile() ? "" : "#" + s.name());
                State st = outcome(after.get(s.path()), s.path(), s.name());
                if (st == State.SATISFIED) passed++;
                else if (st == State.VIOLATED) bad.add(shown + " " + why(after.get(s.path()), s.path(), s.name()));
                else unknown.add(shown + " " + why(after.get(s.path()), s.path(), s.name()));
            }
            List<String> detail = new ArrayList<>(bad);
            detail.addAll(unknown);
            lines.add(new Line(!bad.isEmpty() ? State.VIOLATED : !unknown.isEmpty() ? State.UNKNOWN : State.SATISFIED,
                    "scenarios found, ran and passed: " + passed + " of " + m.scenarios().size(), false, detail));

            if (!m.mustStayTrue().isEmpty()) {
                Map<String, FileRun> before = new LinkedHashMap<>();
                for (Map.Entry<Runner, List<String>> e : owned.entrySet()) {
                    List<String> truths = e.getValue().stream().filter(p -> m.mustStayTrue().stream().anyMatch(t -> t.test().equals(p))).toList();
                    if (!truths.isEmpty()) before.putAll(e.getKey().run(sides.base().dir, truths, timeout));
                }
                bad = new ArrayList<>();
                unknown = new ArrayList<>();
                int held = 0;
                for (Mandate.Truth t : m.mustStayTrue()) {
                    State b = outcome(before.get(t.test()), t.test(), ""), a = outcome(after.get(t.test()), t.test(), "");
                    if (b == State.SATISFIED && a == State.SATISFIED) held++;
                    else if (a == State.VIOLATED) bad.add("\"" + t.is() + "\": " + t.test() + " fails after the change");
                    else if (b == State.VIOLATED) bad.add("\"" + t.is() + "\": " + t.test() + " fails before the change, so it does not describe what was true");
                    else unknown.add("\"" + t.is() + "\": " + t.test() + " " + why(a != State.SATISFIED ? after.get(t.test()) : before.get(t.test()), t.test(), ""));
                }
                detail = new ArrayList<>(bad);
                detail.addAll(unknown);
                lines.add(new Line(!bad.isEmpty() ? State.VIOLATED : !unknown.isEmpty() ? State.UNKNOWN : State.SATISFIED,
                        "what must stay true passes before and after: " + held + " of " + m.mustStayTrue().size(), false, detail));
            }

            Witness.Result w = Witness.run(change, runners, runs, timeout, sides);
            long sensitive = w.count(Kind.SENSITIVE), both = w.count(Kind.PASSES_ON_BOTH), failing = w.count(Kind.FAILS_AFTER);
            long notRun = w.count(Kind.NOT_RUN_NOW), takenAway = w.count(Kind.OLD_FAILS_AFTER);
            long preApproved = w.verdicts().stream().filter(v -> v.kind() == Kind.SENSITIVE && m.locked().containsKey(v.file())).count();
            detail = new ArrayList<>();
            for (Witness.Verdict v : w.verdicts()) {
                boolean whole = v.name().isEmpty();
                if (v.kind() == Kind.PASSES_ON_BOTH) detail.add(v.file() + (whole ? ": " + v.detail() : "  " + v.name() + ": passes before and after, so it does not test the change"));
                else if (v.kind() == Kind.FAILS_AFTER || v.kind() == Kind.OLD_FAILS_AFTER) detail.add(v.file() + (whole ? "" : "  " + v.name()) + ": " + v.detail());
                else if (v.kind() == Kind.NOT_RUN_NOW) detail.add(v.file() + ": " + v.detail());
                else if (v.kind() == Kind.MOVED) detail.add(v.file() + ": " + v.detail());
            }
            for (Witness.Verdict v : w.verdicts()) {
                if (v.kind() == Kind.SENSITIVE) detail.add(v.file() + "  " + v.name() + ": fails before, passes after (" + (m.locked().containsKey(v.file()) ? "a test approved with the mandate" : "a test written with the change") + ")");
            }
            long undecided = w.count(Kind.UNKNOWN) + w.count(Kind.UNSTABLE) + w.count(Kind.SKIPPED);
            if (undecided > 0) detail.add(undecided + " of the change's tests could not be judged, gave different results or were skipped");
            for (String f : w.unsupported()) detail.add(f + ": no runner for it in this version");
            List<String> wrong = new ArrayList<>();
            count(wrong, w, Kind.FAILS_AFTER, " fails after it", " fail after it");
            count(wrong, w, Kind.PASSES_ON_BOTH, " new test passes on both sides", " new tests pass on both sides");
            count(wrong, w, Kind.NOT_RUN_NOW, " ran before it and does not run now", " ran before it and do not run now");
            count(wrong, w, Kind.OLD_FAILS_AFTER, " passed before it, fails on it as it was and was taken out of the way", " passed before it, fail on it as they were and were taken out of the way");
            String what = w.verdicts().isEmpty() ? "the change adds or edits no test this version can run"
                    : "the change's own tests: " + sensitive + (sensitive == 1 ? " fails" : " fail") + " before it and " + (sensitive == 1 ? "passes" : "pass") + " after it"
                            + (sensitive > 0 ? " (" + preApproved + " approved with the mandate, " + (sensitive - preApproved) + " written with the change)" : "")
                            + (wrong.isEmpty() ? "" : "; " + String.join("; ", wrong));
            State st = both + failing + notRun + takenAway > 0 ? State.VIOLATED : undecided > 0 || !w.unsupported().isEmpty() || sensitive == 0 ? State.UNKNOWN : State.SATISFIED;
            lines.add(new Line(st, what, false, detail));
        }
        return new Result(scope, outside, approval, lines, unresolved);
    }

    private static void count(List<String> parts, Witness.Result w, Kind kind, String one, String many) {
        long n = w.count(kind);
        if (n > 0) parts.add((w.atLeast(kind) ? "at least " : "") + n + (n == 1 ? one : many));
    }

    private static String blobAt(String tree, String path) {
        Proc p = Proc.run(Ctx.root, "git", "rev-parse", "-q", "--verify", tree + ":" + path);
        return p.ok() && !p.out().isBlank() ? p.out().strip() : null;
    }

    private static String day(String instant) {
        return instant.length() >= 10 ? instant.substring(0, 10) : instant;
    }

    private static State outcome(FileRun run, String path, String name) {
        if (run == null) return State.UNKNOWN;
        if (run.problem() != null) return run.broken() ? State.VIOLATED : State.UNKNOWN;
        List<JUnitXml.Case> cases = cases(run, path, name);
        if (cases.isEmpty()) return State.UNKNOWN;
        if (cases.stream().anyMatch(c -> c.status() == Status.FAILED || c.status() == Status.ERRORED)) return State.VIOLATED;
        if (run.retriesUnknown() != null) return State.UNKNOWN;
        return cases.stream().allMatch(c -> c.status() == Status.PASSED && !c.retried()) ? State.SATISFIED : State.UNKNOWN;
    }

    private static List<JUnitXml.Case> cases(FileRun run, String path, String name) {
        if (name.isEmpty()) return run.cases();
        List<JUnitXml.Case> exact = run.cases().stream().filter(c -> c.name().equals(name)).toList();
        // Java reports one method once per invocation ("refunds(int)[1]", "[2]"); elsewhere every reported case is a test of its own.
        boolean java = path.endsWith(".java");
        if (!exact.isEmpty()) return exact.size() == 1 || java && exact.stream().map(JUnitXml.Case::suite).distinct().count() == 1 ? exact : List.of();
        List<JUnitXml.Case> plain = run.cases().stream().filter(c -> TestNames.plain(path, c.name()).equals(name)).toList();
        if (!java) return plain.size() == 1 ? plain : List.of();
        long homes = plain.stream().map(c -> c.suite() + "\n" + c.name().replaceFirst("[(\\[{].*$", "")).distinct().count();
        return homes == 1 ? plain : List.of();
    }

    private static String why(FileRun run, String path, String name) {
        if (run == null) return "has no runner in this version, or does not exist";
        if (run.problem() != null) return run.problem();
        List<JUnitXml.Case> cases = cases(run, path, name);
        if (cases.isEmpty()) return "was not found among the tests that ran, or more than one test has that name";
        for (JUnitXml.Case c : cases) {
            if (c.status() == Status.FAILED) return "fails" + (c.message().isBlank() ? "" : ": " + brief(c.message()));
            if (c.status() == Status.ERRORED) return "throws " + c.type() + (c.message().isBlank() ? "" : ": " + brief(c.message()));
        }
        return cases.stream().anyMatch(c -> c.status() == Status.SKIPPED) ? "is skipped" : "gave different results when the build ran it again";
    }

    private static String brief(String message) {
        String m = message.replaceAll("\\s+", " ").strip();
        return m.length() > 120 ? m.substring(0, 120) + "..." : m;
    }

    static void print(PrintStream out, Mandate m, Change change, Result r) {
        String sha = change.base.length() > 7 ? change.base.substring(0, 7) : change.base;
        out.println("sdlc check --mandate " + m.id() + ": " + change.files.size() + " file" + (change.files.size() == 1 ? "" : "s") + " changed against " + change.baseName + " (" + sha + ")");
        out.println();
        out.println("Mandate   " + safe(m.intent()));
        out.println("          " + safe(r.approval()));
        out.println();
        out.println("Scope     " + r.scope() + switch (r.scope()) {
            case "INSIDE" -> "   every changed file is inside the mandate, and the mandate is approved as it is";
            case "AMENDED" -> "  every changed file is inside the mandate, but the mandate is " + (r.approval().startsWith("not") ? "not approved" : "not approved in the form it has now");
            default -> "  " + r.outside().size() + (r.outside().size() == 1 ? " changed file is" : " changed files are") + " not covered by the mandate";
        });
        for (String f : r.outside()) out.println("            " + safe(f));
        out.println();
        out.println("Evidence");
        for (Line l : r.lines()) {
            out.println("  " + pad(l.state().word, 10) + safe(l.what()) + "   [" + (l.independent() ? "read from git" : "from the change's own test run") + "]");
            for (String d : l.details()) out.println("              " + safe(d));
        }
        if (r.unresolved() != null) out.println("  unresolved " + safe(r.unresolved()));
        out.println();
        out.println("What this does not say");
        out.println("  - that the change is correct: the tests and the code come from the same author");
        out.println("  - that the mandate was approved before the work: only that this text was approved");
        if (r.lines().stream().anyMatch(l -> l.state() == State.UNKNOWN || l.state() == State.NOT_RUN)) out.println("  - anything about an obligation marked unknown or not run");
        if (m.apis() != null) out.println("  - whether the change keeps to scope.apis: this version does not check it");
    }

    private static String safe(String text) {
        return sdlc.mandate.MandateCard.safe(text);
    }

    private static String pad(String s, int n) {
        return s + " ".repeat(Math.max(1, n - s.length()));
    }
}
