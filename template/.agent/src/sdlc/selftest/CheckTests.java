package sdlc.selftest;

import static sdlc.selftest.Asserts.check;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import sdlc.check.Check;
import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.core.Trusted;
import sdlc.util.Proc;

/**
 * Self test: sdlc check, in a repository of its own with no configuration. The test runner is a
 * stand-in (sdlc.check.SelfTestRunner), so no build tool is needed: a ".t" file lists its cases.
 */
final class CheckTests {
    private CheckTests() {}

    static void run() throws IOException {
        Path savedRoot = Ctx.root;
        Config savedCfg = Ctx.cfg;
        Path repo = Files.createTempDirectory("sdlc-checktest").toRealPath();
        try {
            Trusted.env = name -> null;
            output(repo);
            reports(repo);
            reactor(repo);
            scenarios(repo);
        } finally {
            Trusted.env = System::getenv;
            Ctx.root = savedRoot;
            Ctx.cfg = savedCfg;
            try (Stream<Path> s = Files.walk(repo)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** The output of a child process is held in memory only up to a limit. */
    private static void output(Path repo) {
        Proc whole = Proc.run(repo, "git", "help", "-a");
        Proc ends = Proc.exec(repo, Map.of(), null, 60, 40, 40, null, null, "git", "help", "-a");
        check("output: a build's output is kept at both ends and the middle is left out, with a count", whole.ok() && whole.out().length() > 200
                && ends.ok() && ends.out().startsWith(whole.out().substring(0, 40)) && ends.out().endsWith(whole.out().substring(whole.out().length() - 40))
                && ends.out().contains((whole.out().getBytes(StandardCharsets.UTF_8).length - 80) + " bytes of output left out here"));
        java.util.List<String> heard = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Proc listened = Proc.exec(repo, Map.of(), null, 60, 40, 40, (line, endsLine) -> heard.add(line), null, "git", "help", "-a");
        check("output: a listener gets every line, also those of the middle that is left out",
                listened.out().equals(ends.out()) && String.join("\n", heard).strip().equals(whole.out().strip()) && heard.size() > 10);
        java.util.List<String> pieces = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<Boolean> lineEnds = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Proc.exec(repo, Map.of(), null, 60, 40, 40, (line, end) -> {
            pieces.add(line);
            lineEnds.add(end);
        }, null, "git", "rev-parse", "--sq-quote", "a".repeat(17000), "COMPILATION ERROR");
        check("output: a listener is given the end of a very long line too, and is told which piece ends the line",
                pieces.stream().anyMatch(l -> l.contains("COMPILATION ERROR")) && lineEnds.size() >= 2 && !lineEnds.get(0) && lineEnds.get(lineEnds.size() - 1));
        Proc strict = Proc.exec(repo, Map.of(), null, 60, 40, 0, null, null, "git", "help", "-a");
        check("output: an answer that is read as a whole is an error when it is too large, not a shortened answer",
                strict.code() == Proc.TOO_MUCH_OUTPUT && strict.out().isEmpty() && strict.err().contains("larger than"));
        check("output: one that fits is unchanged", Proc.exec(repo, Map.of(), null, 60, 1 << 20, 0, null, null, "git", "help", "-a").out().equals(whole.out()));
    }

    private static void reports(Path repo) throws IOException {
        Path xml = repo.resolve("report.xml");
        Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="demo.RefundsTest" tests="4">
                  <testcase name="passes" classname="demo.RefundsTest" time="0.01"/>
                  <testcase name="fails()" classname="demo.RefundsTest"><failure message="expected: &lt;60&gt; but was: &lt;100&gt;" type="org.opentest4j.AssertionFailedError">trace</failure></testcase>
                  <testcase name="throws" classname="demo.RefundsTest"><error message="boom" type="java.lang.IllegalStateException">trace</error><system-out>noise</system-out></testcase>
                  <testcase name="off" classname="demo.RefundsTest"><skipped/></testcase>
                </testsuite>
                """.stripLeading());
        var cases = sdlc.check.JUnitXml.read(xml);
        check("check: a JUnit report gives each case's outcome", cases.size() == 4 && cases.get(0).status() == sdlc.check.JUnitXml.Status.PASSED
                && cases.get(1).status() == sdlc.check.JUnitXml.Status.FAILED && cases.get(1).type().endsWith("AssertionFailedError")
                && cases.get(2).status() == sdlc.check.JUnitXml.Status.ERRORED && cases.get(3).status() == sdlc.check.JUnitXml.Status.SKIPPED
                && cases.get(1).suite().equals("demo.RefundsTest"));
        Files.writeString(xml, "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY x \"y\">]><testsuite><testcase name=\"a\" classname=\"a\"/></testsuite>");
        boolean refused = false;
        try {
            sdlc.check.JUnitXml.read(xml);
        } catch (IOException e) {
            refused = true;
        }
        check("check: a report with a DOCTYPE is refused outright", refused);
        Files.writeString(xml, "<testsuite><testcase name=\"again\" classname=\"a\"><flakyFailure type=\"java.lang.AssertionError\">x</flakyFailure></testcase></testsuite>");
        check("check: a case the build ran again is flaky, not passed", sdlc.check.JUnitXml.read(xml).get(0).status() == sdlc.check.JUnitXml.Status.FLAKY);
        Files.delete(xml);
        check("check: test names are read from Java", sdlc.check.TestNames.of("ATest.java",
                "class ATest { @Test void one() {} @ParameterizedTest @ValueSource(ints = {1, 2}) void two(int n) {} void helper() {} }").equals(java.util.Set.of("one", "two")));
        check("check: and from a script", sdlc.check.TestNames.of("a.test.ts", "describe('x', () => { it('adds', () => {}); test.each([1])(\"each %i\", () => {}); it.skip(`later`, () => {}) })")
                .containsAll(java.util.Set.of("adds", "later")));
        check("check: a reported name is matched to the declared one", sdlc.check.TestNames.plain("ATest.java", "two(int)[1]").equals("two")
                && sdlc.check.TestNames.plain("a.test.ts", "x > nested > adds").equals("adds"));
    }

    private static void reactor(Path repo) throws IOException {
        Path r = repo.resolve("reactor");
        Files.createDirectories(r.resolve("backend/api"));
        Files.createDirectories(r.resolve("tools/cli"));
        Files.writeString(r.resolve("backend/pom.xml"), "<project><modules><module>api</module></modules></project>");
        Files.writeString(r.resolve("backend/api/pom.xml"), "<project/>");
        Files.writeString(r.resolve("tools/cli/pom.xml"), "<project/>");
        check("check: Maven starts at the topmost pom that lists the module, not at the repository root",
                sdlc.check.Runners.reactorRootOf(r, "backend/api").equals("backend") && sdlc.check.Runners.reactorRootOf(r, "tools/cli").equals("tools/cli"));
        Files.writeString(r.resolve("pom.xml"), "<project><modules>\n  <module>backend</module>\n</modules></project>");
        check("check: and follows a chain of modules up to the root", sdlc.check.Runners.reactorRootOf(r, "backend/api").isEmpty() && sdlc.check.Runners.reactorRootOf(r, "tools/cli").equals("tools/cli"));
        try (Stream<Path> s = Files.walk(r)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static void scenarios(Path repo) throws IOException {
        write(repo, "prod.txt", "v1\n");
        write(repo, "tests/old.t", "kept: pass\n");
        // Files whose tests only the run judges. They lie outside a test folder, so the reading of the diff (Integrity)
        // says nothing about them and the exit code is the run's alone.
        write(repo, "checks/judged.t", JUDGED);
        write(repo, "checks/legacy.t", LEGACY);
        write(repo, ".gitignore", "ignored/\n");
        git(repo, "init", "-q", "-b", "main");
        commit(repo, "base");
        Ctx.init(repo);

        String nothing = report(Map.of("selftest-runner", "true"));
        check("check: nothing changed says so and passes", nothing.contains("nothing has changed against main") && code == 0);

        // an edit that keeps a file's size, made right after a commit, is still seen (git must not trust size and time alone)
        write(repo, "prod.txt", "v2\n");
        check("check: an edit of the same size as the committed file is part of the change", report(Map.of("no-run", "true")).contains("1 file changed against main"));
        write(repo, "prod.txt", "v1\n");

        // git can be told not to look at a file; the check looks all the same
        for (String flag : new String[] {"assume-unchanged", "skip-worktree"}) {
            git(repo, "update-index", "--" + flag, "prod.txt");
            write(repo, "prod.txt", "v1\nhidden\n");
            check("check: an edit of a file marked " + flag + " is part of the change", report(Map.of("no-run", "true")).contains("1 file changed against main"));
            git(repo, "update-index", "--no-" + flag, "prod.txt");
            write(repo, "prod.txt", "v1\n");
        }

        // a flagged file that is deleted is a deleted file, where no sparse checkout leaves it out
        git(repo, "update-index", "--skip-worktree", "tests/old.t");
        Files.delete(repo.resolve("tests/old.t"));
        check("check: a deleted file marked skip-worktree is part of the change", report(Map.of("no-run", "true")).contains("1 file changed against main"));
        write(repo, "tests/old.t", "kept: pass\n");
        git(repo, "update-index", "--no-skip-worktree", "tests/old.t");

        // an uncommitted change: the fix, an edited test file, a new untracked test file, an ignored file
        write(repo, "prod.txt", "v1\nfixed\n");
        write(repo, "tests/old.t", "kept: pass\nsensitive: needs fixed\nproves nothing: pass\n");
        write(repo, "tests/new.t", "new feature: error-unless fixed\nbroken: fail\nlater: skip\nrandom: flaky fixed\n");
        write(repo, "ignored/x.t", "never: fail\n");
        String objectsBefore = Proc.run(repo, "git", "count-objects").out();
        String index = Proc.run(repo, "git", "ls-files", "--stage").out();
        String status = Proc.run(repo, "git", "status", "--porcelain").out();
        String r = report(Map.of("selftest-runner", "true", "runs", "3"));
        check("check: the change is the working tree against the base, untracked files in, ignored files out", r.contains("3 files changed against main"));
        check("check: a test that fails before and passes after is reported as sensitive, after repeat runs",
                r.contains("1 fails before the change and passes after it, in 3 of 3 runs") && r.contains("        sensitive"));
        check("check: a new test that passes on both sides is called out", r.contains("1 new test passes before and after, so it does not test the change") && r.contains("        proves nothing"));
        check("check: a test already in the file that passes on both sides is not counted", r.contains("1 test that was already in the edited files passes before and after (not counted)"));
        check("check: a test that fails after the change is reported", r.contains("1 fails after the change") && r.contains("        broken: fails"));
        check("check: a test that threw something other than an assertion before the change cannot be judged",
                r.contains("could not be judged") && r.contains("new feature: before the change it threw"));
        check("check: a test whose result changes from run to run is unstable, not sensitive", r.contains("1 gave different results from run to run") && r.contains("        random"));
        check("check: a skipped test is listed", r.contains("1 is skipped") && r.contains("        later"));
        check("check: problems make the exit code 1", code == 1);
        check("check: the working tree and the index are untouched",
                Proc.run(repo, "git", "ls-files", "--stage").out().equals(index) && Proc.run(repo, "git", "status", "--porcelain").out().equals(status));
        check("check: nothing is written into the repository", !Files.exists(repo.resolve(".sdlc")) && !Files.exists(repo.resolve(".agent")));
        check("check: not even git objects: the uncommitted files are stored outside the repository",
                Proc.run(repo, "git", "count-objects").out().equals(objectsBefore));

        // as a Stop hook: blocks (exit 2, reasons on stderr) on what is certain and fixable, never on "could not be judged"
        java.io.InputStream in = System.in;
        java.io.PrintStream err = System.err;
        java.io.ByteArrayOutputStream reasons = new java.io.ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(reasons, true, StandardCharsets.UTF_8));
            System.setIn(new java.io.ByteArrayInputStream("{\"hook_event_name\": \"Stop\", \"stop_hook_active\": false}".getBytes(StandardCharsets.UTF_8)));
            int first = Check.run(Map.of("selftest-runner", "true", "stop-hook", "true"));
            String said = reasons.toString(StandardCharsets.UTF_8);
            check("check: the stop hook blocks on a new test that proves nothing and on a failing one", first == 2
                    && said.contains("passes with and without your change") && said.contains("proves nothing") && said.contains("fails: tests/new.t"));
            check("check: the stop hook does not block on what could not be judged", !said.contains("new feature"));
            System.setIn(new java.io.ByteArrayInputStream("{\"stop_hook_active\": true}".getBytes(StandardCharsets.UTF_8)));
            check("check: the stop hook does not block a second time in a row", Check.run(Map.of("selftest-runner", "true", "stop-hook", "true")) == 0);
        } finally {
            System.setIn(in);
            System.setErr(err);
        }

        // only good news: exit 0
        write(repo, "tests/old.t", "kept: pass\nsensitive: needs fixed\n");
        Files.delete(repo.resolve("tests/new.t"));
        String good = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a change whose new test is sensitive passes", code == 0 && good.contains("in 2 of 2 runs") && good.contains("not evidence that the change is correct"));
        System.setIn(new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        try {
            check("check: the stop hook is silent when there is nothing to fix", Check.run(Map.of("selftest-runner", "true", "stop-hook", "true")) == 0);
        } finally {
            System.setIn(in);
        }

        // a test file no runner knows
        write(repo, "src/test/python/test_x.py", "def test_x(): pass\n");
        String py = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test file with no runner is listed under Not checked", py.contains("the test file src/test/python/test_x.py: no runner for it"));
        Files.delete(repo.resolve("src/test/python/test_x.py"));

        // a test of new code beside a test of the fix: before the change the first does not compile, and takes its module with it
        write(repo, "prod.txt", "v1\nfixed\nfeature\n");
        write(repo, "tests/feature.t", "uses the new code: compile-needs feature\n");
        write(repo, "tests/feature2.t", "also uses it: compile-needs feature\n");
        String mixed = report(Map.of("selftest-runner", "true", "runs", "3"));
        check("check: a test that cannot compile before the change does not hide the tests that can",
                mixed.contains("1 fails before the change and passes after it, in 3 of 3 runs") && mixed.contains("        sensitive")
                && mixed.contains("2 could not be judged") && mixed.contains("uses the new code: before the change the file does not compile"));
        Files.delete(repo.resolve("tests/feature.t"));
        Files.delete(repo.resolve("tests/feature2.t"));
        write(repo, "prod.txt", "v1\nfixed\n");

        // a test that leans on another changed test file: alone before the change it fails for want of that file, not of the change
        write(repo, "prod.txt", "v1\nfixed\nfeature\n");
        write(repo, "tests/feature.t", "uses the new code: compile-needs feature\n");
        write(repo, "tests/feature2.t", "also uses it: compile-needs feature\n");
        write(repo, "tests/lean.t", "leans: leans-on tests/helper.t\n");
        write(repo, "tests/helper.t", "helps: pass\n");
        String lean = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a test that fails before the change only for want of another changed test file is not called sensitive",
                lean.contains("leans: after the change it passes only together with the other changed test files") && !lean.contains("        leans\n")
                && lean.contains("1 fails before the change and passes after it, in 2 of 2 runs") && lean.contains("        sensitive"));
        for (String f : new String[] {"feature.t", "feature2.t", "lean.t", "helper.t"}) Files.delete(repo.resolve("tests/" + f));
        write(repo, "prod.txt", "v1\nfixed\n");

        // a test of a name the file already had, in another group: the report tells them apart, so it is a new test
        write(repo, "tests/old.t", "kept: pass\nsensitive: needs fixed\nnested > kept: pass\n");
        String again = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a new test under a name the file already had is not passed off as an old one",
                again.contains("1 new test passes before and after, so it does not test the change") && again.contains("        nested > kept\n")
                && again.contains("1 test that was already in the edited files passes before and after (not counted)") && code == 1);
        write(repo, "tests/old.t", "kept: pass\nsensitive: needs fixed\n");

        // what both sides share although the change touches it: "passes on both" says nothing then
        write(repo, "shared.lock", "v2\n");
        write(repo, "tests/old.t", "kept: pass\nsensitive: needs fixed\nidle: pass\n");
        String sharedSetup = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: when both sides ran with a setup the change touches, a test that passes on both is not judged",
                sharedSetup.contains("idle: passes before and after, but the change touches shared.lock") && !sharedSetup.contains("so it does not test the change")
                && sharedSetup.contains("1 fails before the change and passes after it"));
        Files.delete(repo.resolve("shared.lock"));
        // the same kind of file somewhere else in the repository decides nothing for these tests
        write(repo, "elsewhere/shared.lock", "v2\n");
        String unrelated = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: such a file elsewhere in the repository does not excuse a test that proves nothing",
                unrelated.contains("1 new test passes before and after, so it does not test the change") && unrelated.contains("        idle") && code == 1);
        Files.delete(repo.resolve("elsewhere/shared.lock"));
        write(repo, "tests/old.t", "kept: pass\nsensitive: needs fixed\n");

        // two cases under one name cannot be told apart
        write(repo, "tests/twins.t", "same: needs fixed\nsame: pass\n");
        String twins = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: two tests that report one name are not judged", twins.contains("same: more than one test reports this name") && !twins.contains("        same\n"));
        Files.delete(repo.resolve("tests/twins.t"));

        judgedByTheRun(repo);

        // in a pull request build the base is the event's branch, or the check stops
        Trusted.env = name -> name.equals("GITHUB_BASE_REF") ? "not-fetched" : null;
        check("check: in a pull request build a base branch that was not fetched is an error, not 'nothing changed'", Asserts.throwsUsage(() -> Check.run(Map.of("selftest-runner", "true"))));
        Trusted.env = name -> null;

        // --no-run and a bad option
        String noRun = report(Map.of("no-run", "true"));
        check("check: --no-run reads the diff only", noRun.contains("not run (--no-run)"));
        check("check: --runs must be a number above zero", Asserts.throwsUsage(() -> Check.run(Map.of("runs", "0"))));
        check("check: --base must be a commit", Asserts.throwsUsage(() -> Check.run(Map.of("base", "--upload-pack=x"))));

        // committed work on a branch is part of the change too
        git(repo, "switch", "-q", "-c", "work");
        commit(repo, "the fix");
        write(repo, "prod.txt", "v1\nfixed\nmore\n");
        String branch;
        commit(repo, "more");
        write(repo, "tests/old.t", "kept: pass\nsensitive: needs fixed\nidle: pass\n");
        commit(repo, "a test that proves nothing");
        String history = report(Map.of("selftest-runner", "true", "runs", "1", "since", "1d"));
        check("check: --since replays each recent commit", history.contains("commits on this branch since 1 days ago") && history.contains("  the fix")
                && history.contains("tests: 1 fail before and pass after") && history.contains("does not test the change: tests/old.t  idle")
                && history.contains("new tests pass on both sides, so do not test their commit") && code == 0);
        check("check: --since wants a time span", Asserts.throwsUsage(() -> Check.run(Map.of("since", "true"))));
        git(repo, "reset", "-q", "--hard", "HEAD~2");
        write(repo, "prod.txt", "v1\nfixed\nmore\n");
        branch = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: commits on the branch and uncommitted edits are one change", branch.contains("2 files changed against main") && branch.contains("1 fails before the change and passes after it"));
    }

    /** A test file of the base, for what only a run of it as it was can tell: which tests it had and which of them ran. */
    private static final String JUDGED = "stays: pass\nturned off: pass\nwas off: skip\ngoes: pass\ngoes too: pass\n";

    /**
     * Which tests are new: read from the run of the file as it was, on the code as it was. Comes in
     * with the fix and a sensitive test in tests/old.t, so a run that says nothing else exits with 0.
     */
    private static void judgedByTheRun(Path repo) throws IOException {
        // the tests the file had, and one more
        write(repo, "checks/judged.t", JUDGED + "idle: pass\n");
        String r = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test the file as it was did not report is new, and is called out when it passes on both sides",
                r.contains("1 new test passes before and after, so it does not test the change\n      checks/judged.t  [self test]\n        idle\n") && code == 1);
        check("check: the tests the file as it was reported are not counted",
                r.contains("5 tests that were already in the edited files pass before and after (not counted)") && !r.contains("        stays"));
        write(repo, "checks/judged.t", JUDGED);

        // the file as it was gives no result (its slow test is cut off): whether a test is new cannot be told
        write(repo, "checks/legacy.t", "held: pass\nadded: pass\n");
        String legacy = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: when the file as it was cannot be run, a test that passes on both sides could not be judged",
                legacy.contains("added: passes before and after, but run as it was before the change, the file timed out after 900 s, so it cannot be told whether this test is new")
                && legacy.contains("held: passes before and after, but run as it was before the change")
                && legacy.contains("checks/legacy.t  [self test]\n        held: ") && !legacy.contains("so it does not test the change") && code == 0);
        check("check: and the report says so for the file as a whole",
                legacy.contains("        run as it was before the change, the file timed out after 900 s, so it cannot be told which of its tests are new\n"));
        write(repo, "checks/legacy.t", LEGACY);
    }

    /** A test file of the base that, as it is there, gives no result: what it had cannot be learnt from a run of it. */
    private static final String LEGACY = "held: pass\nslow: timeout\n";

    private static int code;

    private static String report(Map<String, String> options) {
        PrintStream out = System.out;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        System.setOut(new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            code = Check.run(options);
        } finally {
            System.setOut(out);
        }
        return sink.toString(StandardCharsets.UTF_8);
    }

    private static void write(Path repo, String rel, String text) throws IOException {
        Path f = repo.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private static void commit(Path repo, String subject) {
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", subject);
    }

    private static void git(Path repo, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Proc p = Proc.run(repo, cmd);
        if (!p.ok()) throw new IllegalStateException("git " + String.join(" ", args) + ": " + p.err());
    }
}
