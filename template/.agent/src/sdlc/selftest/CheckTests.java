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
            adapters(repo);
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
        check("check: but not from a script: its run says which tests it has", sdlc.check.TestNames.of("a.test.ts", "describe('x', () => { it('adds', () => {}) })").isEmpty());
        check("check: a reported name is matched to the declared one", sdlc.check.TestNames.plain("ATest.java", "two(int)[1]").equals("two")
                && sdlc.check.TestNames.plain("a.test.ts", "x > nested > adds").equals("adds"));
    }

    /** The runners' own reading of what the build tools leave behind: report files as Surefire and vitest write them. */
    private static void adapters(Path repo) throws IOException {
        // Vitest: a file without tests and a test whose own failure says the same words are written alike, and both stay failures
        Path xml = repo.resolve("vitest.xml");
        Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8" ?>
                <testsuites name="vitest tests" tests="1" failures="1" errors="0" time="0.004">
                    <testsuite name="src/app.test.ts" timestamp="2026-11-14T10:00:00.000Z" hostname="h" tests="1" failures="1" errors="0" skipped="0" time="0">
                        <testcase classname="src/app.test.ts" name="app" time="0">
                            <failure message="No test found in suite app" type="Error">
                Error: No test found in suite app
                            </failure>
                        </testcase>
                    </testsuite>
                </testsuites>
                """);
        check("check: vitest's entry for a file without tests stays a failed case", sdlc.check.Runners.vitestReport(xml, "src/app.test.ts").equals("ERRORED app"));
        Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8" ?>
                <testsuites name="vitest tests" tests="2" failures="1" errors="0" time="0.004">
                    <testsuite name="src/refund.test.ts" timestamp="2026-11-14T10:00:00.000Z" hostname="h" tests="2" failures="1" errors="0" skipped="0" time="0.002">
                        <testcase classname="src/refund.test.ts" name="refunds &gt; rejects" time="0.001">
                            <failure message="No test found in suite: business failure" type="Error">
                Error: No test found in suite: business failure
                 ❯ src/refund.test.ts:5:11
                            </failure>
                        </testcase>
                        <testcase classname="src/refund.test.ts" name="refunds &gt; accepts" time="0.001">
                        </testcase>
                    </testsuite>
                </testsuites>
                """);
        check("check: a test whose failure starts with vitest's words for an empty file is still a failure",
                sdlc.check.Runners.vitestReport(xml, "src/refund.test.ts").equals("ERRORED refunds > rejects; PASSED refunds > accepts"));
        Files.delete(xml);

        // Maven: the build passes and no report names the class: the class ran and has no tests. A failed build says nothing.
        if (Proc.WINDOWS) return; // the stand-in for Maven below is a shell script
        Path m = repo.resolve("maven");
        Files.createDirectories(m.resolve("src/test/java/demo"));
        Files.writeString(m.resolve("pom.xml"), "<project/>");
        Files.writeString(m.resolve("src/test/java/demo/ATest.java"), "package demo;\nclass ATest {}\n");
        Files.writeString(m.resolve("mvnw"), """
                #!/bin/sh
                # stands in for Maven: copies the report it is given into place, says what Maven would, exits as told
                mkdir -p target/surefire-reports
                if [ -f given-report.xml ]; then cp given-report.xml target/surefire-reports/TEST-given.xml; fi
                echo "[ERROR] Failed to execute goal: something outside the tests went wrong"
                exit "$(cat given-exit)"
                """);
        Files.writeString(m.resolve("given-exit"), "0");
        check("check: a Maven build that passes with no report for the class: it ran and has no tests", sdlc.check.Runners.mavenRun(m, "src/test/java/demo/ATest.java").equals("ran, no tests"));
        Files.writeString(m.resolve("given-report.xml"), "<testsuite name=\"demo.OtherTest\"><testcase name=\"other\" classname=\"demo.OtherTest\"/></testsuite>");
        check("check: a report of another class says nothing of this one", sdlc.check.Runners.mavenRun(m, "src/test/java/demo/ATest.java").equals("ran, no tests"));
        Files.writeString(m.resolve("given-report.xml"), "<testsuite name=\"demo.ATest\"><testcase name=\"works\" classname=\"demo.ATest\"/></testsuite>");
        check("check: and one of the class gives its cases", sdlc.check.Runners.mavenRun(m, "src/test/java/demo/ATest.java").equals("PASSED works"));
        Files.delete(m.resolve("given-report.xml"));
        Files.writeString(m.resolve("given-exit"), "1");
        String failed = sdlc.check.Runners.mavenRun(m, "src/test/java/demo/ATest.java");
        check("check: a Maven build that fails with no report for the class did not run it, and is not taken for no tests",
                failed.startsWith("no result: did not run (Maven exit 1: Failed to execute goal: something outside the tests went wrong"));
        try (Stream<Path> s = Files.walk(m)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
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
        write(repo, "checks/era.t", ERA);
        write(repo, "checks/named.t", NAMED);
        write(repo, "checks/off.t", OFF);
        write(repo, "checks/titles.t", TITLES);
        write(repo, "checks/once.t", ONCE);
        write(repo, "checks/nested.t", NESTED);
        write(repo, "checks/broken.t", BROKEN);
        write(repo, "checks/asleep.t", ASLEEP);
        write(repo, "checks/swap.t", SWAP);
        write(repo, "checks/side/both.t", SIDE);
        write(repo, "checks/pair.t", PAIR);
        write(repo, "checks/hooked.t", HOOKED);
        write(repo, "checks/init.t", INIT);
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
        check("check: a new test that passes on both sides is called out, said of its edited file with the names the reports give",
                r.contains("at least 1 new test passes before and after, so it does not test the change\n      tests/old.t  [self test]\n        the file reports 2 tests it did not have, "
                        + "and 1 of its tests does not pass on both sides: at least 1 new test passes before and after. Under names the file did not have, 1 passes before and after "
                        + "(proves nothing); the names do not say which test is new"));
        check("check: a test already in the file that passes on both sides is not counted", r.contains("1 test that was already in the edited files passes before and after (not counted)"));
        check("check: a test that fails after the change is reported", r.contains("1 fails after the change") && r.contains("        broken: fails"));
        check("check: a test that threw something other than an assertion before the change cannot be judged",
                r.contains("could not be judged") && r.contains("new feature: before the change it threw"));
        check("check: and the report says why such a test is not counted", r.contains("      A test of code that did not exist before the change cannot fail there for the right reason"));
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
                    && said.contains("new tests pass with and without your change, so they do not test it: tests/old.t (the file reports 2 tests it did not have")
                    && said.contains("(proves nothing)") && said.contains("fails: tests/new.t"));
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
                again.contains("at least 1 new test passes before and after, so it does not test the change\n      tests/old.t  [self test]\n        the file reports 2 tests it did not have, "
                        + "and 1 of its tests does not pass on both sides: at least 1 new test passes before and after. Under names the file did not have, 1 passes before and after (nested > kept)")
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
                unrelated.contains("at least 1 new test passes before and after, so it does not test the change\n      tests/old.t  [self test]\n") && unrelated.contains("(idle)") && code == 1);
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
        write(repo, "checks/judged.t", JUDGED.replace("turned off: pass", "turned off: skip").replace("goes too: pass\n", ""));
        commit(repo, "a test switched off and one removed");
        write(repo, "checks/quiet.t", "asleep: skip\n");
        commit(repo, "a skipped test");
        write(repo, "checks/quiet.t", "asleep: skip\n  still later\n");
        commit(repo, "an edit of a skipped test");
        String later = MENDED.replace("holds: pass", "holds: fails-with later");
        write(repo, "checks/broken.t", later);
        commit(repo, "a test that a later word breaks");
        write(repo, "prod.txt", "v1\nfixed\nmore\nlater\n");
        write(repo, "checks/broken.t", later.replace("holds: fails-with later", "kept up: pass"));
        commit(repo, "a test broken and taken out of the way");
        String history = report(Map.of("selftest-runner", "true", "runs", "1", "since", "1d"));
        check("check: --since names a test its commit broke and took out of the way",
                history.contains("a test broken and taken out of the way\n    tests: 1 passed before, fail as they were after, taken out; 1 could not be judged\n"
                        + "        passed before, fails as it was after, taken out: checks/broken.t  holds\n")
                && history.contains("  1 test passed before its commit, fails on it as it was, and was taken out of the way\n"));
        check("check: --since says of a commit whose tests were only skipped before and after that they were",
                history.contains("an edit of a skipped test\n    tests: only tests that were already there, skipped as before\n"));
        check("check: --since replays each recent commit", history.contains("commits on this branch since 1 days ago") && history.contains("  the fix")
                && history.contains("tests: 1 fail before and pass after") && history.contains("does not test the change: tests/old.t  the file reports 1 test it did not have: at least 1 "
                        + "new test passes before and after. Under names the file did not have, 1 passes before and after (idle)")
                && history.contains("\n  at least 1 new test passes on both sides, so does not test its commit\n") && code == 0);
        check("check: --since names the tests a commit switched off or removed", history.contains("tests: at least 1 ran before, skipped after; at least 1 removed\n")
                && history.contains("ran before, skipped after: checks/judged.t  the file runs 2 of its tests where it ran 4 before the change, and skips 2 where it skipped 1 "
                        + "(skipped now under a name that ran before: turned off)") && history.contains("removed: checks/judged.t  the file no longer reports 1 test under the name it had (goes too)")
                && history.contains("at least 1 test ran before its commit and is skipped after it; at least 1 test was removed"));
        check("check: --since wants a time span", Asserts.throwsUsage(() -> Check.run(Map.of("since", "true"))));
        git(repo, "reset", "-q", "--hard", "HEAD~7");
        write(repo, "prod.txt", "v1\nfixed\nmore\n");
        branch = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: commits on the branch and uncommitted edits are one change", branch.contains("2 files changed against main") && branch.contains("1 fails before the change and passes after it"));
    }

    /** A test file of the base, for what only a run of it as it was can tell: which tests it had and which of them ran. */
    private static final String JUDGED = "stays: pass\nturned off: pass\nwas off: skip\ngoes: pass\ngoes too: pass\n";

    /**
     * Which tests are new, which ran before and are skipped now, and which are gone: read from the
     * run of the file as it was, on the code as it was. Comes in
     * with the fix and a sensitive test in tests/old.t, so a run that says nothing else exits with 0.
     */
    private static void judgedByTheRun(Path repo) throws IOException {
        // the tests the file had, and one more
        write(repo, "checks/judged.t", JUDGED + "idle: pass\n");
        String r = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test the file as it was did not report is new, and is called out when it passes on both sides",
                r.contains("at least 1 new test passes before and after, so it does not test the change\n      checks/judged.t  [self test]\n        the file reports 1 test it did not have: "
                        + "at least 1 new test passes before and after. Under names the file did not have, 1 passes before and after (idle); the names do not say which test is new") && code == 1);
        check("check: the tests the file as it was reported are not counted",
                r.contains("5 tests that were already in the edited files pass before and after (not counted)") && !r.contains("        stays"));
        write(repo, "checks/judged.t", JUDGED);

        // one test switched off; another was off before and stays off
        write(repo, "checks/judged.t", JUDGED.replace("turned off: pass", "turned off: skip"));
        String off = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test that ran before the change and is skipped now is a problem",
                off.contains("at least 1 ran before the change and is skipped now\n      checks/judged.t  [self test]\n        the file runs 3 of its tests where it ran 4 before the change, "
                        + "and skips 2 where it skipped 1 (skipped now under a name that ran before: turned off): at least 1 test that ran does not run now, switched off, or removed "
                        + "while skipped tests were added\n") && code == 1);
        check("check: a test skipped before and after the change is not counted",
                off.contains("1 test is skipped as before the change: the file skipped a test of its name before it too (not counted)") && !off.contains("        was off") && !off.contains(" is skipped\n"));
        java.io.InputStream in = System.in;
        java.io.PrintStream err = System.err;
        ByteArrayOutputStream reasons = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(reasons, true, StandardCharsets.UTF_8));
            System.setIn(new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
            int blocked = Check.run(Map.of("selftest-runner", "true", "stop-hook", "true"));
            check("check: the stop hook blocks on a test that ran before the change and is skipped now", blocked == 2
                    && reasons.toString(StandardCharsets.UTF_8).contains("tests that ran before your change do not run now: checks/judged.t (the file runs 3 of its tests where it ran 4 "
                            + "before the change, and skips 2 where it skipped 1 (skipped now under a name that ran before: turned off)"));
        } finally {
            System.setIn(in);
            System.setErr(err);
        }
        // a skipped test under a name the file did not have, in a file whose tests ran: it may be a test that ran, under a new name
        write(repo, "checks/judged.t", JUDGED + "later: skip\n");
        String later = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a skipped test under a new name, in a file whose tests ran, could not be judged, with the counts",
                later.contains("1 could not be judged\n      checks/judged.t  [self test]\n        the file runs 4 of its tests where it ran 4 before the change, and skips 2 where it skipped 1 "
                        + "(skipped under a name the file did not report before: later). So more are skipped while as many or more run: it cannot be told whether skipped tests "
                        + "were added, or a test that ran was switched off and another added\n") && !later.contains("is skipped now") && !later.contains(" is skipped\n") && code == 0);
        write(repo, "checks/judged.t", JUDGED);
        // in a file none of whose tests ran before, nothing can have been switched off: a skipped test under a new name is listed
        write(repo, "checks/asleep.t", ASLEEP + "later: skip\n");
        String asleep = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a skipped test under a new name, in a file none of whose tests ran before, is listed as skipped",
                asleep.contains("1 is skipped\n      checks/asleep.t  [self test]\n        later\n") && !asleep.contains("could not be judged") && code == 0);
        // the same file, where a test that passes on both sides comes with the skipped one: two new names and one more case each, and only one
        // of the two can be the new test that is skipped
        write(repo, "checks/asleep.t", ASLEEP + "later: skip\nidle: pass\n");
        String woken = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: two new tests, one skipped and one passing on both sides: the second does not test the change",
                woken.contains("at least 1 new test passes before and after, so it does not test the change\n      checks/asleep.t  [self test]\n        the file reports 2 tests it did not have, "
                        + "and 1 of its tests does not pass on both sides: at least 1 new test passes before and after. Under names the file did not have, 1 passes before and after (idle)")
                && woken.contains("1 is skipped\n      checks/asleep.t  [self test]\n        later\n") && code == 1);
        write(repo, "checks/asleep.t", ASLEEP);

        // one gone and none new: removed, by name
        write(repo, "checks/judged.t", JUDGED.replace("goes too: pass\n", ""));
        String one = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test the file had, where it reports no new one, was removed",
                one.contains("at least 1 test was removed: the edited file reports fewer tests than it had before the change\n      checks/judged.t  [self test]\n        the file no longer reports "
                        + "1 test under the name it had (goes too), and reports none under a name it did not have: at least 1 test was removed. The names do not say which: where tests "
                        + "are named by their place, one taken out leaves its name to the next\n") && code == 1);
        // a file whose cases come from a source that gives another number each time: the counts of a repeat run differ, so nothing is removed for certain
        write(repo, "checks/judged.t", JUDGED.replace("goes too: pass\n", "") + "late: skip-later\n");
        String recounted = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a file that reports another number of tests in a repeat run is unstable, not a removal",
                recounted.contains("1 gave different results from run to run\n      checks/judged.t  [self test]\n        the file no longer reports 1 test under the name it had (goes too), "
                        + "and reports none under a name it did not have, but what the file reports, after the change or as it was, is not the same in every run\n")
                && !recounted.contains("removed") && code == 0);
        write(repo, "checks/judged.t", JUDGED.replace("goes too: pass\n", ""));
        reasons.reset();
        try {
            System.setErr(new PrintStream(reasons, true, StandardCharsets.UTF_8));
            System.setIn(new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
            int blocked = Check.run(Map.of("selftest-runner", "true", "stop-hook", "true"));
            check("check: the stop hook blocks on a test removed", blocked == 2
                    && reasons.toString(StandardCharsets.UTF_8).contains("tests removed: checks/judged.t (the file no longer reports 1 test under the name it had (goes too)"));
        } finally {
            System.setIn(in);
            System.setErr(err);
        }

        // two gone and one new in their place: the file lost a test, and which one cannot be told
        write(repo, "checks/judged.t", "stays: pass\nturned off: pass\nwas off: skip\nidle: pass\n");
        String fewer = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a file that reports fewer tests than it had lost tests: at least so many were removed, with both lists",
                fewer.contains("at least 1 test was removed: the edited file reports fewer tests than it had before the change\n      checks/judged.t  [self test]\n"
                        + "        the file no longer reports 2 tests under the names it had (goes, goes too), and reports 1 under a name it did not have (idle): at least 1 test was removed. "
                        + "The names do not say which: where tests are named by their place, one taken out leaves its name to the next\n")
                && code == 1);
        check("check: and its new test that passes on both sides may be one of them renamed: could not be judged, not accused",
                fewer.contains("        idle: passes before and after, but it cannot be told whether it is a new test: the file reports 1 test it did not have and no longer reports 2 it had. "
                        + "It may be a new test that does not test the change, or a test the file had: one of the 2 tests it no longer reports, under a new name\n")
                && !fewer.contains("new test passes"));
        reasons.reset();
        try {
            System.setErr(new PrintStream(reasons, true, StandardCharsets.UTF_8));
            System.setIn(new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
            int blocked = Check.run(Map.of("selftest-runner", "true", "stop-hook", "true"));
            check("check: the stop hook blocks on a file that lost tests", blocked == 2
                    && reasons.toString(StandardCharsets.UTF_8).contains("tests removed: checks/judged.t (the file no longer reports 2 tests under the names it had (goes, goes too)"));
        } finally {
            System.setIn(in);
            System.setErr(err);
        }

        // as many new as gone, but one that ran is skipped under its new name: fewer run than before
        write(repo, "checks/judged.t", JUDGED.replace("goes: pass", "went: skip"));
        String renamedOff = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a gone test that ran, where the new one is skipped, is a test that no longer runs",
                renamedOff.contains("at least 1 ran before the change and is skipped now\n      checks/judged.t  [self test]\n        the file runs 3 of its tests where it ran 4 before the change, "
                        + "and skips 2 where it skipped 1 (skipped under a name the file did not report before: went): at least 1 test that ran does not run now, "
                        + "switched off, or removed while skipped tests were added\n") && !renamedOff.contains("The reports cannot tell") && code == 1);

        // a renamed group: a test gone and one new, as for any rename; a title inside a name is not read
        write(repo, "checks/judged.t", JUDGED.replace("stays: pass", "group > stays: pass"));
        String regrouped = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test under a renamed group is a test gone and one new: could not be judged, both said, and the check does not fail", code == 0
                && regrouped.contains("2 could not be judged\n      checks/judged.t  [self test]\n        group > stays: passes before and after, but it cannot be told whether it is a new test: "
                        + "the file reports 1 test it did not have and no longer reports 1 it had. It may be a new test that does not test the change, or a test the file had: "
                        + "the test it no longer reports, under a new name\n        the file no longer reports 1 test under the name it had (stays), and reports 1 under a name it did not have "
                        + "(group > stays). The reports cannot tell a test renamed from a test deleted and another written\n") && !regrouped.contains("Note:") && !regrouped.contains("removed"));
        // one gone and two new that pass on both sides: whatever their names, one more case than before passes on both sides
        write(repo, "checks/judged.t", JUDGED.replace("stays: pass\n", "") + "one > stays: pass\ntwo > stays: pass\n");
        String twoGroups = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a gone test and two new ones could not be judged as a rename", code == 1 && !twoGroups.contains("removed")
                && twoGroups.contains("        the file no longer reports 1 test under the name it had (stays), and reports 2 under names it did not have (one > stays, two > stays). "
                        + "The reports cannot tell a test renamed from a test deleted and another written\n"));
        check("check: more new tests pass on both sides than tests are gone: at least the rest are new and do not test the change",
                twoGroups.contains("at least 1 new test passes before and after, so it does not test the change\n      checks/judged.t  [self test]\n"
                        + "        the file reports 2 tests it did not have and no longer reports 1 it had: at least 1 new test passes before and after. Under names the file did not have, "
                        + "2 pass before and after (one > stays, two > stays); the names do not say which test is new, as where tests are named by their place a row put in before the "
                        + "others moves an old one to a name the file did not have\n"));
        // a fix that adds two tests, one sensitive and one that passes on both sides: one new test does not test the change
        write(repo, "checks/judged.t", JUDGED + "idle: pass\nguards the fix: needs fixed\n");
        String pair = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: of two new tests, one sensitive and one passing on both sides, the second does not test the change",
                pair.contains("at least 1 new test passes before and after, so it does not test the change\n      checks/judged.t  [self test]\n        the file reports 2 tests it did not have, "
                        + "and 1 of its tests does not pass on both sides: at least 1 new test passes before and after. Under names the file did not have, 1 passes before and after (idle)")
                && pair.contains("\n        guards the fix\n") && code == 1);

        write(repo, "checks/judged.t", JUDGED);

        // the audit's case: an outer class renamed whose nested classes share a method name. The title fits two tests on each side
        write(repo, "checks/nested.t", NESTED.replace("Outer", "Renamed"));
        String nested = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: an outer class renamed whose nested classes share a method name could not be judged, and is not a removal", code == 0 && !nested.contains("removed")
                && nested.contains("        the file no longer reports 3 tests under the names it had (demo.Outer$A.works, demo.Outer$B.works, demo.Outer.top), and reports 3 under names it did not have "
                        + "(demo.Renamed$A.works, demo.Renamed$B.works, demo.Renamed.top). The reports cannot tell a test renamed from a test deleted and another written\n")
                && nested.contains("        demo.Renamed$A.works: passes before and after, but it cannot be told whether it is a new test: the file reports 3 tests it did not have and no longer "
                        + "reports 3 it had. It may be a new test that does not test the change, or a test the file had: one of the 3 tests it no longer reports, under a new name\n")
                && nested.contains("4 could not be judged") && !nested.contains("new test pass"));
        write(repo, "checks/nested.t", NESTED);

        renamedOrNot(repo);
        switchedOff(repo);
        oldTestsOnTheChange(repo);
        unlisted(repo);

        // a test that went to another changed file: each file is judged by its own counts, and a title is not read to pair them
        write(repo, "checks/judged.t", JUDGED.replace("goes too: pass\n", ""));
        write(repo, "checks/moved.t", "goes too: needs fixed\n");
        String moved = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test now in another changed file is removed from its own, and judged as a new test in the other", code == 1 && !moved.contains("moved to another")
                && moved.contains("at least 1 test was removed: the edited file reports fewer tests than it had before the change\n      checks/judged.t  [self test]\n        the file no longer "
                        + "reports 1 test under the name it had (goes too)")
                && moved.contains("      checks/moved.t  [self test]\n        goes too\n"));
        Files.delete(repo.resolve("checks/moved.t"));
        write(repo, "checks/judged.t", JUDGED);

        // the file as it was gives no result (its slow test is cut off): whether a test is new cannot be told
        write(repo, "checks/legacy.t", "held: pass\nadded: pass\n");
        String legacy = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: when the file as it was cannot be run, a test that passes on both sides could not be judged",
                legacy.contains("added: passes before and after, but run as it was before the change, the file timed out after 900 s, so it cannot be told whether this test is new")
                && legacy.contains("held: passes before and after, but run as it was before the change")
                && legacy.contains("checks/legacy.t  [self test]\n        held: ") && !legacy.contains("so it does not test the change") && code == 0);
        check("check: and the report says so for the file as a whole",
                legacy.contains("        run as it was before the change, the file timed out after 900 s, so it cannot be told which of its tests are new, were switched off or were removed\n"));
        write(repo, "checks/legacy.t", LEGACY);

        // The file as it was runs on the code as it was: as it is in the base it compiles only with v1, which the change
        // takes out of prod.txt. On the code after the change it would not compile, and its tests could not be judged.
        write(repo, "prod.txt", "fixed\n");
        write(repo, "checks/era.t", "era: pass\nlasting: pass\n");
        String era = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: the file as it was runs on the code as it was, not on the code after the change",
                era.contains("3 tests that were already in the edited files pass before and after (not counted)") && !era.contains("could not be judged") && code == 0);
        write(repo, "checks/era.t", ERA);
        write(repo, "prod.txt", "v1\nfixed\n");

        // the four checkouts are made outside the repository and removed when done
        java.util.List<Path> made = Check.checkoutsAfterClose(sdlc.check.Change.of(null));
        check("check: the checkouts after, before, as it was and of the old tests are all removed", made.size() == 4 && made.stream().noneMatch(Files::exists));
    }

    /**
     * A report does not say whether a gone test was renamed or deleted, and the files' text is not
     * read to guess it. Only the counts are judged: as many new tests as gone ones could not be
     * judged, and new tests that pass on both sides beyond the number gone do not test the change.
     */
    private static void renamedOrNot(Path repo) throws IOException {
        write(repo, "checks/judged.t", JUDGED);
        // a title, a title built while the file runs, a title with an escaped quote, a method of several cases: only the names change
        write(repo, "checks/named.t", "has a title: pass\n  expects payments\nsums %i: pass\nit\\'s a title: pass\nrefusesNull(String)[%i]: pass\nrefund: pass\ngoes: pass\n");
        String renamed = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: tests renamed could not be judged, one verdict for the file with both lists, and the check does not fail",
                code == 0 && !renamed.contains("removed") && !renamed.contains("new test pass") && !renamed.contains("Note:")
                && renamed.contains("        the file no longer reports 6 tests under the names it had (has a name, adds 1, adds 2, it's a name, rejectsNull(String)[1], rejectsNull(String)[2]), "
                        + "and reports 6 under names it did not have (has a title, sums 1, sums 2, it's a title, refusesNull(String)[1], refusesNull(String)[2]). "
                        + "The reports cannot tell a test renamed from a test deleted and another written\n"));
        check("check: and each new test that passes on both sides may be a gone one: could not be judged",
                renamed.contains("7 could not be judged\n") && renamed.contains("        refusesNull(String)[2]: passes before and after, but it cannot be told whether it is a new test: "
                        + "the file reports 6 tests it did not have and no longer reports 6 it had. It may be a new test that does not test the change, or a test the file had: "
                        + "one of the 6 tests it no longer reports, under a new name\n"));

        // a test renamed, and a new one written right after it: two new tests pass on both sides, and only one can be the gone one
        write(repo, "checks/named.t", NAMED.replace("goes: pass\n", "went: pass\nextra: pass\n"));
        String beside = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test renamed next to a new one: at least one new test passes on both sides, and the check fails",
                beside.contains("at least 1 new test passes before and after, so it does not test the change\n      checks/named.t  [self test]\n        the file reports 2 tests it did not have "
                        + "and no longer reports 1 it had: at least 1 new test passes before and after. Under names the file did not have, 2 pass before and after (went, extra)")
                && beside.contains("(goes), and reports 2 under names it did not have (went, extra). The reports cannot tell") && !beside.contains("removed") && code == 1);

        // one test deleted, another added elsewhere: the reports say exactly what they say of a rename
        write(repo, "checks/named.t", "arrives: pass\n" + NAMED.replace("goes: pass\n", ""));
        String swapped = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test deleted and another added cannot be told from a rename: could not be judged, not a failure",
                swapped.contains("        the file no longer reports 1 test under the name it had (goes), and reports 1 under a name it did not have (arrives). The reports cannot tell")
                && swapped.contains("        arrives: passes before and after, but it cannot be told whether it is a new test: the file reports 1 test it did not have and no longer reports 1 it had. "
                        + "It may be a new test that does not test the change, or a test the file had: the test it no longer reports, under a new name\n")
                && !swapped.contains("removed") && !swapped.contains("new test pass") && code == 0);
        check("check: what is said of code that did not exist before the change is not said of a test that could not be judged for another reason",
                !swapped.contains("A test of code that did not exist"));

        // a row put in among the arguments of a parameterized test, named by its place: the last row has a name the file did not report, and the
        // reports are the same whether the row was put in front of the others or after them. One more case than before, and every case passes on
        // both sides: the new one, wherever it is, does not test the change. It is said of the file: the last name may be an old row moved on.
        write(repo, "checks/named.t", NAMED.replace("rejectsNull(String)[%i]: pass\n", "rejectsNull(String)[1]: pass\nrejectsNull(String)[2]: pass\nrejectsNull(String)[3]: pass\n"));
        String rows = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a row put in before the others of a parameterized test, every case passing on both sides: at least one new test does not test the change, said of the file",
                rows.contains("at least 1 new test passes before and after, so it does not test the change\n      checks/named.t  [self test]\n        the file reports 1 test it did not have: "
                        + "at least 1 new test passes before and after. Under names the file did not have, 1 passes before and after (rejectsNull(String)[3]); the names do not say which "
                        + "test is new, as where tests are named by their place a row put in before the others moves an old one to a name the file did not have\n")
                && !rows.contains("        rejectsNull(String)[3]\n") && code == 1);
        // the same, where the row put in is sensitive: the new test may be it, and the last row an old one at a new place. Not accused.
        write(repo, "checks/named.t", NAMED.replace("rejectsNull(String)[%i]: pass\n", "rejectsNull(String)[1]: pass\nrejectsNull(String)[2]: needs fixed\nrejectsNull(String)[3]: pass\n"));
        String sensitiveRow = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a sensitive row put in among others is not taken for a new test that passes on both sides: the last row could not be judged",
                sensitiveRow.contains("\n        rejectsNull(String)[2]\n") && sensitiveRow.contains("        rejectsNull(String)[3]: passes before and after, but it cannot be told whether it "
                        + "is a new test: the file reports 1 test it did not have, and 1 of its tests does not pass on both sides. It may be a new test that does not test the change, "
                        + "or a test the file had: a test moved to this name by one put in before it, where cases are named by their place (the new test then being among the 1 "
                        + "that do not pass on both sides)\n") && !sensitiveRow.contains("new test pass") && code == 0);
        write(repo, "checks/named.t", NAMED.replace("rejectsNull(String)[%i]: pass\n", "rejectsNull(String)[1]: pass\nrejectsNull(String)[2]: pass\nrejectsNull(String)[3]: pass\n"
                + "rejectsBlank(String)[1]: pass\nrejectsBlank(String)[2]: pass\n"));
        String newMethod = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: the cases under names the file did not report, as many as it has more cases, all passing on both sides, are new tests",
                newMethod.contains("at least 3 new tests pass before and after, so they do not test the change\n      checks/named.t  [self test]\n        the file reports 3 tests it did not have: "
                        + "at least 3 new tests pass before and after. Under names the file did not have, 3 pass before and after (rejectsNull(String)[3], rejectsBlank(String)[1], "
                        + "rejectsBlank(String)[2]); the names do not say which tests are new") && code == 1);
        // renamed, and a row added: two gone and three new, all passing on both sides: one more than before, and it passes on both sides
        write(repo, "checks/named.t", NAMED.replace("rejectsNull(String)[%i]: pass\n", "refusesNull(String)[%i]: pass\nrefusesNull(String)[3]: pass\n"));
        String renamedRows = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a parameterized test renamed, with a row added: at least one new test passes on both sides, and the rename could not be judged",
                renamedRows.contains("        the file reports 3 tests it did not have and no longer reports 2 it had: at least 1 new test passes before and after. Under names the file "
                        + "did not have, 3 pass before and after (refusesNull(String)[1], refusesNull(String)[2], refusesNull(String)[3]); the names do not say which test is new")
                && renamedRows.contains("(rejectsNull(String)[1], rejectsNull(String)[2]), and reports 3 under names it did not have (refusesNull(String)[1], refusesNull(String)[2], "
                        + "refusesNull(String)[3]). The reports cannot tell") && !renamedRows.contains("removed") && code == 1);
        // a row taken out: the file reports one case fewer, so a test was removed, whatever the names say. Named by place, taking out the first
        // row gives the same reports as taking out the last, so the name gone is not said to be the row taken out
        write(repo, "checks/named.t", NAMED.replace("rejectsNull(String)[%i]: pass\n", "rejectsNull(String)[1]: pass\n"));
        String fewerRows = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a row taken out of a parameterized test is a test removed, said of the file with the name the file no longer reports",
                fewerRows.contains("at least 1 test was removed: the edited file reports fewer tests than it had before the change\n      checks/named.t  [self test]\n        the file no longer "
                        + "reports 1 test under the name it had (rejectsNull(String)[2]), and reports none under a name it did not have: at least 1 test was removed. The names do not say "
                        + "which: where tests are named by their place, one taken out leaves its name to the next\n") && !fewerRows.contains("        rejectsNull(String)[2]\n") && code == 1);
        write(repo, "checks/named.t", NAMED);

        // every test of the file removed: the file runs and has none
        write(repo, "checks/named.t", "  nothing left\n");
        String emptied = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a file whose tests were all removed reports each of them removed",
                emptied.contains("at least 8 tests were removed: the edited file reports fewer tests than it had before the change\n      checks/named.t  [self test]\n        the file no longer "
                        + "reports 8 tests under the names it had (has a name, adds 1, adds 2, it's a name, rejectsNull(String)[1], rejectsNull(String)[2], refund, goes), and reports none "
                        + "under a name it did not have: at least 8 tests were removed.") && !emptied.contains("reports no tests") && code == 1);
        write(repo, "checks/named.t", NAMED);
        // a new file with no test: nothing to judge, and the report says so
        write(repo, "checks/empty.t", "  nothing yet\n");
        String none = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a new file with no test is said to report none", none.contains("checks/empty.t  [self test]\n        the file ran after the change and reports no tests\n") && code == 0);
        Files.delete(repo.resolve("checks/empty.t"));
    }

    /** A test file of the base for "ran before the change, skipped now": tests that failed, threw, passed, a parameterized one, two of one name. */
    private static final String OFF = "failing: needs fixed\nthrowing: error-unless fixed\ntarget: pass\nparams(int)[1]: pass\nparams(int)[2]: skip\ntwin: skip\ntwin: pass\n";

    /** Which skipped tests the change switched off, and which it did not. */
    private static void switchedOff(Path repo) throws IOException {
        write(repo, "checks/judged.t", JUDGED);
        // a test that failed or threw before the change also ran
        write(repo, "checks/off.t", OFF.replace("failing: needs fixed", "failing: skip").replace("throwing: error-unless fixed", "throwing: skip"));
        String failed = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test that failed or threw before the change ran, and is skipped now",
                failed.contains("at least 2 ran before the change and are skipped now\n      checks/off.t  [self test]\n        the file runs 3 of its tests where it ran 5 before the change, "
                        + "and skips 4 where it skipped 2 (skipped now under names that ran before: failing, throwing): at least 2 tests that ran do not run now, switched off, or removed "
                        + "while skipped tests were added\n") && code == 1);

        // two cases of one name before the change, one of which ran: both skipped now
        write(repo, "checks/off.t", OFF.replace("twin: pass", "twin: skip"));
        String twins = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: of two tests of one name, one ran and both are skipped now: skipped now, with the counts",
                twins.contains("at least 1 ran before the change and is skipped now\n      checks/off.t  [self test]\n        the file runs 4 of its tests where it ran 5 before the change, "
                        + "and skips 3 where it skipped 2 (skipped now under a name that ran before: twin): at least 1 test that ran does not run now") && code == 1);

        // a second test of the name exists only before the change: it does not hide the one switched off
        write(repo, "checks/off.t", OFF.replace("target: pass", "target: skip\ntarget: skip-without fixed"));
        String hidden = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: two tests of one name on the side before the change do not hide a test switched off",
                hidden.contains("at least 1 ran before the change and is skipped now\n      checks/off.t  [self test]\n        the file runs 4 of its tests where it ran 5 before the change, "
                        + "and skips 3 where it skipped 2 (skipped now under a name that ran before: target): at least 1 test") && code == 1);

        // two cases exchange which of them is skipped: a test switched off and another switched on, or rows that changed places
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass\nparams(int)[2]: skip", "params(int)[1]: skip\nparams(int)[2]: pass"));
        String reordered = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: two cases that exchange which of them is skipped could not be judged, with the counts and both stories",
                reordered.contains("could not be judged\n") && reordered.contains("\n        the file runs 5 of its tests where it ran 5 before the change, and skips 2 where it skipped 2 "
                        + "(skipped now under a name that ran before: params(int)[1]), and its list of cases changed as well (0 names it did not report, 0 it no longer reports, "
                        + "1 skipped before and run now). It cannot be told whether a test that ran was switched off, or cases changed names")
                && !reordered.contains("ran before the change and is skipped now") && code == 0);
        // one invocation that ran is skipped now, and a new one that runs is added: the new one does not make up for it, and the
        // reports cannot tell this from a skipped case added in front of the others. Not passed, not accused: could not be judged.
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass", "params(int)[1]: skip").replace("params(int)[2]: skip", "params(int)[2]: skip\nparams(int)[3]: pass"));
        String paid = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: an invocation switched off is not made up for by a new one that runs: it could not be judged, in every run",
                paid.contains("could not be judged\n") && paid.contains("\n        the file runs 5 of its tests where it ran 5 before the change, and skips 3 where it skipped 2 "
                        + "(skipped now under a name that ran before: params(int)[1]). So more are skipped while as many or more run: it cannot be told whether skipped tests were added, "
                        + "or a test that ran was switched off and another added\n")
                && !paid.contains("ran before the change and is skipped now") && !paid.contains("from run to run") && !paid.contains("new test pass") && code == 0);
        // a skipped case added in front of cases that all ran: the same
        write(repo, "checks/named.t", NAMED.replace("rejectsNull(String)[%i]: pass\n", "rejectsNull(String)[1]: pass\nrejectsNull(String)[2]: skip\nrejectsNull(String)[3]: pass\n"));
        String inserted = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a skipped case among cases that ran, where as many run as before, could not be judged",
                inserted.contains("\n        the file runs 8 of its tests where it ran 8 before the change, and skips 1 where it skipped 0 (skipped now under a name that ran before: "
                        + "rejectsNull(String)[2]). So more are skipped while as many or more run")
                && !inserted.contains("ran before the change and is skipped now") && !inserted.contains("new test pass") && code == 0);
        write(repo, "checks/named.t", NAMED);
        // a run put in front of a skipped one: the skipped run has a name the file did not report. Under names by place that is a row moved
        // on; under any other naming it may be a test that ran, switched off and renamed, while a skipped one was switched on
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass", "params(int)[1]: pass\nparams(int)[2]: pass").replace("params(int)[2]: skip", "params(int)[3]: skip"));
        String shifted = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a skipped case under a new name, after a row put in before it, could not be judged, with both stories",
                shifted.contains("\n        the file runs 6 of its tests where it ran 5 before the change, and skips 2 where it skipped 2 (skipped under a name the file did not report "
                        + "before: params(int)[3]). The file had tests that ran, so it cannot be told whether this is a new test that is skipped, or a test that ran before under "
                        + "another name and is switched off\n") && !shifted.contains(" is skipped\n") && !shifted.contains("ran before the change and is skipped now") && code == 0);
        // what could not be judged in one run and is not switched off in another is unstable
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass\nparams(int)[2]: skip", "params(int)[1]: skip-once fixed\nparams(int)[2]: skip\nparams(int)[3]: pass"));
        String undecidedOnce = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a test that could not be judged in one run and is not switched off in another is unstable",
                undecidedOnce.contains("1 gave different results from run to run\n      checks/off.t  [self test]\n        whether tests that ran before the change are skipped now differs "
                        + "from run to run: what the file reports, after the change or as it was, is not the same in every run\n") && !undecidedOnce.contains("So more are skipped") && code == 0);
        // a skipped case added after the last one: under names by place the reports are the same as for a row that ran, switched off, with another put in before it
        write(repo, "checks/off.t", OFF.replace("params(int)[2]: skip", "params(int)[2]: skip\nparams(int)[3]: skip"));
        String appended = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a skipped case added after the last one could not be judged: more are skipped while as many run",
                appended.contains("\n        the file runs 5 of its tests where it ran 5 before the change, and skips 3 where it skipped 2 (skipped under a name the file did not report "
                        + "before: params(int)[3]). So more are skipped while as many or more run") && !appended.contains(" is skipped\n")
                && !appended.contains("ran before the change and is skipped now") && code == 0);
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass", "params(int)[1]: skip"));
        String fewer = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: when more invocations are skipped than before, the method is switched off, with the counts",
                fewer.contains("at least 1 ran before the change and is skipped now\n      checks/off.t  [self test]\n        the file runs 4 of its tests where it ran 5 before the change, "
                        + "and skips 3 where it skipped 2 (skipped now under a name that ran before: params(int)[1]): at least 1 test") && code == 1);

        // a skip that comes and goes is unstable, not switched off; one run alone cannot tell
        write(repo, "checks/off.t", OFF.replace("target: pass", "target: skip-once fixed"));
        String wavering = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a test skipped in one run after the change and not in another is unstable, not switched off",
                !wavering.contains("ran before the change and is skipped now") && wavering.contains("1 gave different results from run to run\n      checks/off.t  [self test]\n        "
                        + "whether tests that ran before the change are skipped now differs from run to run: what the file reports, after the change or as it was, is not the same in every run\n") && code == 0);
        // a case that only a repeat run reports: the decision is made again from that run's whole report, and it is another one
        write(repo, "checks/off.t", OFF.replace("target: pass", "target: skip\nlate: skip-later"));
        String late = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test switched off, in one run", late.contains("at least 1 ran before the change and is skipped now\n      checks/off.t  [self test]\n        the file runs 4 of its "
                + "tests where it ran 5 before the change, and skips 3 where it skipped 2 (skipped now under a name that ran before: target)") && code == 1);
        late = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a case that only a repeat run reports changes the answer: unstable, not the first run's answer",
                late.contains("1 gave different results from run to run\n      checks/off.t  [self test]\n        " + "whether tests that ran before the change are skipped now differs from run to run: what the file reports, after the change or as it was, is not the same in every run\n")
                && !late.contains("ran before the change and is skipped now") && code == 0);
        write(repo, "checks/off.t", OFF);

        // which of two nested classes' tests of one name is skipped changes from run to run: the counts are the same, the tests are not
        write(repo, "checks/nested.t", NESTED.replace("demo.Outer$A::works: pass", "demo.Outer$A::works: skip-once fixed").replace("demo.Outer$B::works: pass", "demo.Outer$B::works: pass-once"));
        String alternate = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test of a nested class skipped now is shown with its class where another class has a test of its name",
                alternate.contains("at least 1 ran before the change and is skipped now\n      checks/nested.t  [self test]\n        the file runs 2 of its tests where it ran 3 before the change, "
                        + "and skips 1 where it skipped 0 (skipped now under a name that ran before: demo.Outer$A.works)") && code == 1);
        alternate = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a skip that moves between two nested classes' tests of one name from run to run is unstable, not switched off",
                alternate.contains("1 gave different results from run to run\n      checks/nested.t  [self test]\n        whether tests that ran before the change are skipped now differs from "
                        + "run to run") && !alternate.contains("ran before the change and is skipped now") && code == 0);
        write(repo, "checks/nested.t", NESTED);

        // the file as it was ran the test in its first run and skipped it in a later one: the repeat runs of the old side count
        write(repo, "checks/once.t", "target: skip\n");
        String once = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a test that ran as the file was in one run and was skipped there in another is unstable, not switched off",
                once.contains("1 gave different results from run to run\n      checks/once.t  [self test]\n        " + "whether tests that ran before the change are skipped now differs from run to run: what the file reports, after the change or as it was, is not the same in every run\n")
                && !once.contains("ran before the change and is skipped now") && code == 0);
        write(repo, "checks/once.t", ONCE);

        // a name is the test, brackets and all: "rate" and "rate[3]" are two names, and one does not stand in for the other
        write(repo, "checks/titles.t", "rate: skip\nrate[3]: skip\n");
        String titles = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test whose name differs from another's only by a number in brackets is judged on its own",
                titles.contains("at least 1 ran before the change and is skipped now\n      checks/titles.t  [self test]\n        the file runs 0 of its tests where it ran 1 before the change, "
                        + "and skips 2 where it skipped 1 (skipped now under a name that ran before: rate): at least 1 test") && code == 1);
        write(repo, "checks/titles.t", TITLES);

        // skipped after the change where the file as it was gave no result: listed, not judged switched off
        write(repo, "checks/legacy.t", "held: skip\n");
        String unknown = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test skipped after the change, where the file as it was gave no result, is listed as skipped",
                unknown.contains("1 is skipped\n      checks/legacy.t  [self test]\n        held\n") && !unknown.contains("skipped now") && code == 0);
        write(repo, "checks/legacy.t", LEGACY);
    }

    /**
     * A test file of the base whose tests pass on the base's code and fail on the code of the fix
     * (holds, fragile, the first run of rows), or fail there in the first run only (wobbly).
     */
    private static final String BROKEN = "holds: fails-with fixed\nfragile: fails-with fixed\nsteady: pass\nrows(int)[1]: fails-with fixed\nrows(int)[2]: pass\nwobbly: breaks-once fixed\n";

    /** BROKEN as a change that keeps every test and makes each pass on its code: edited tests, nothing gone. */
    private static final String MENDED = "holds: pass\nfragile: pass\nsteady: pass\nrows(int)[1]: pass\nrows(int)[2]: pass\nwobbly: pass\n";

    /**
     * The fourth run: an edited test file as it was, on the code after the change, only where a
     * test is gone, switched off or runs fewer times. An old test that passed before the change and
     * fails there was broken by the change and taken out of the way.
     */
    private static void oldTestsOnTheChange(Path repo) throws IOException {
        write(repo, "checks/judged.t", JUDGED);
        // every old test kept under its name, each edited to pass: the old versions fail on the change, and nothing is said of it
        write(repo, "checks/broken.t", MENDED);
        String mended = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: old tests kept under their names are not run as they were, and their old versions are not reported",
                code == 0 && !mended.contains("out of the way") && mended.contains("7 tests that were already in the edited files pass before and after (not counted)"));
        write(repo, "tests/old.t", "kept: pass\n"); // the sensitive test of the fix, the other edited file, is a test that file did not have
        check("check: the fourth run is not made when each edited file reports the same list of tests as before", Check.fourthCheckout(sdlc.check.Change.of(null)).equals("not made, head intact"));
        write(repo, "tests/old.t", "kept: pass\nsensitive: needs fixed\n");

        // a row the change breaks taken out while the file grows: named by place, [a, b] to [b, d, c], with a broken, b and d passing on both sides and c
        // sensitive. Nothing is gone or skipped and no count is short, but the list of names changed: the old a fails under a name that still runs
        write(repo, "checks/broken.t", MENDED.replace("rows(int)[2]: pass", "rows(int)[2]: pass\nrows(int)[3]: needs fixed"));
        String grown = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a broken row replaced while the file grows is not passed in silence: its old version fails under a name that still runs, could not be judged",
                grown.contains("\n        holds, fragile, rows(int)[1], wobbly passed before the change and fail on the code after it as the file was. A test of each name still runs after the "
                        + "change, so it cannot be told whether each was edited for the changed behaviour, or taken out and its name given to another case")
                && grown.contains("\n        rows(int)[3]\n") && code == 0);

        // a test the change breaks, renamed to one that passes: the old one as it was fails on the change
        write(repo, "checks/broken.t", MENDED.replace("holds: pass", "kept up: pass"));
        String renamed = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a gone test that passed before and fails as it was on the change was taken out of the way: the check fails, in every run",
                renamed.contains("1 test passed before the change and fails on the code after it as the test was, and the change took it out of the way: "
                        + "removed, renamed or switched off\n      checks/broken.t  [self test]\n        holds: passed before the change; as the test was, "
                        + "on the code after the change holds fails: expected something else. The edited file does not report it now\n") && code == 1);
        check("check: the old tests of that file whose names still run, and whose old versions fail too, may have been edited: could not be judged, not accused",
                renamed.contains("\n        fragile, rows(int)[1] passed before the change and fail on the code after it as the file was. A test of each name still runs after the change, "
                        + "so it cannot be told whether each was edited for the changed behaviour, or taken out and its name given to another case, as a row taken out does to "
                        + "the rows after it where cases are named by their place\n") && !renamed.contains("wobbly passed") && !renamed.contains("steady")
                && !renamed.contains("The reports cannot tell"));
        check("check: the fourth run is made then, in a checkout of its own, and the checkout after the change is left as the change has it",
                Check.fourthCheckout(sdlc.check.Change.of(null)).equals("made, head intact"));
        java.io.InputStream in = System.in;
        java.io.PrintStream err = System.err;
        ByteArrayOutputStream reasons = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(reasons, true, StandardCharsets.UTF_8));
            System.setIn(new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
            int blocked = Check.run(Map.of("selftest-runner", "true", "stop-hook", "true"));
            check("check: the stop hook blocks on it", blocked == 2
                    && reasons.toString(StandardCharsets.UTF_8).contains("passed before your change, fails on your code as it was, and your change took it out of the way: checks/broken.t » holds"));
        } finally {
            System.setIn(in);
            System.setErr(err);
        }

        // two runners, each with a test gone: each runner's fourth checkout holds its own old files and no other runner's
        write(repo, "checks/side/both.t", SIDE.replace("goes: pass\n", ""));
        check("check: each runner runs its old tests in a fourth checkout of its own, with no other runner's old files in it",
                Check.fourthCheckoutsApart(sdlc.check.Change.of(null), "checks/side/").equals("self test of checks/side/: checks/side/both.t; self test: checks/broken.t, tests/old.t"));
        write(repo, "checks/side/both.t", SIDE);

        // deleted: the same, said once, not also as removed
        write(repo, "checks/broken.t", MENDED.replace("holds: pass\n", ""));
        String deleted = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a deleted test the change breaks is said to be taken out of the way, and not also listed as removed",
                deleted.contains("        holds: passed before the change; as the test was, on the code after the change holds fails") && !deleted.contains("removed: the edited file") && code == 1);

        // deleted, and a test that passes renamed: the file reports one test fewer, and that one is the broken test already said. Not said twice
        write(repo, "checks/broken.t", MENDED.replace("holds: pass\n", "").replace("steady: pass", "steadfast: pass"));
        String renamedBeside = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a deleted test the change breaks, beside a rename, is said once: taken out of the way, and no test besides it is said to be removed",
                renamedBeside.contains("1 test passed before the change and fails on the code after it as the test was, and the change took it out of the way: removed, renamed or switched "
                        + "off\n      checks/broken.t  [self test]\n        holds: passed before the change") && !renamedBeside.contains("was removed") && code == 1);
        // deleted, and a test that passes deleted too: two fewer, one of them said to be taken out of the way, so at least one more removed
        write(repo, "checks/broken.t", MENDED.replace("holds: pass\n", "").replace("steady: pass\n", ""));
        String twoGone = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: two tests deleted, one of which the change breaks: taken out of the way, and at least one more removed besides it",
                twoGone.contains("        holds: passed before the change; as the test was, on the code after the change holds fails")
                && twoGone.contains("at least 1 test was removed: the edited file reports fewer tests than it had before the change\n      checks/broken.t  [self test]\n        the file no "
                        + "longer reports 2 tests under the names it had (holds, steady), and reports none under a name it did not have: at least 2 tests were removed, of which at most 1 "
                        + "is an old test said above to be taken out of the way, so at least 1 more. The names do not say which") && code == 1);

        // two nested classes each lose their method of one name, and the change breaks only the first: the second is not taken for the first
        write(repo, "checks/broken.t", BROKEN);
        write(repo, "checks/pair.t", "demo.Pair::top: pass\n");
        String pair = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: of two nested classes' tests of one name, the one the change breaks is taken out of the way, and the other is still counted as removed",
                pair.contains("      checks/pair.t  [self test]\n        demo.Pair$A.works: passed before the change; as the test was, on the code after the change demo.Pair$A.works "
                        + "fails: expected something else. The edited file does not report it now\n")
                && pair.contains("at least 1 test was removed: the edited file reports fewer tests than it had before the change\n      checks/pair.t  [self test]\n        the file no "
                        + "longer reports 2 tests under the names it had (demo.Pair$A.works, demo.Pair$B.works), and reports none under a name it did not have: at least 2 tests were "
                        + "removed, of which at most 1 is an old test said above to be taken out of the way, so at least 1 more.") && code == 1);
        write(repo, "checks/pair.t", PAIR);

        // fails as it was on the change in one run and not in another: unstable
        write(repo, "checks/broken.t", MENDED.replace("wobbly: pass", "wobbled: pass"));
        String wobbly = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a gone test that fails as it was on the change in one run and not in another is unstable, not taken out",
                wobbly.contains("1 gave different results from run to run\n      checks/broken.t  [self test]\n        wobbly: passed before the change, and as the test was, "
                        + "failed on the code after the change in one run and not in another\n") && !wobbly.contains("out of the way") && code == 0);

        // a run of a parameterized test the change breaks, switched off
        write(repo, "checks/broken.t", MENDED.replace("rows(int)[1]: pass", "rows(int)[1]: skip"));
        String rowOff = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a switched-off run of a parameterized test that fails as it was on the change was taken out of the way",
                rowOff.contains("      checks/broken.t  [self test]\n        rows(int)[1]: passed before the change; as the test was, on the code after the change rows(int)[1] fails: "
                        + "expected something else, and the change skips it now\n")
                && !rowOff.contains("ran before the change and is skipped now") && code == 1);

        // a test the change breaks, skipped after the change in the first run only: whether the change took it out of the way is not the same in every run
        write(repo, "checks/broken.t", MENDED.replace("holds: pass", "holds: skip-once fixed"));
        String skippedOnce = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: an old test the change breaks, skipped after the change in one run and run in another, is unstable, not taken out of the way",
                skippedOnce.contains("      checks/broken.t  [self test]\n        holds: passed before the change; as the test was, on the code after the change holds fails: "
                        + "expected something else, and the change skips it, or the file does not report it, in one run after the change and runs it in another\n")
                && !skippedOnce.contains("out of the way: removed") && !skippedOnce.contains("ran before the change and is skipped now") && code == 0);

        // a test the change breaks, deleted, and a new test skipped in the first run after the change only: in the later run it passes after the
        // change and not before, so it may be the old test rewritten. What is said must hold in every run: could not be judged
        write(repo, "checks/broken.t", MENDED.replace("holds: pass", "holds anew: skip-once fixed"));
        String rewriteLater = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a case that can be a rewrite of a broken test in a repeat run only keeps it from being said taken out of the way",
                rewriteLater.contains("        holds passed before the change and fails on the code after it as the file was, and the file does not report it now or skips it. It has "
                        + "1 test that passes after the change and does not pass on both sides (holds anew), so it cannot be told whether the old test was rewritten")
                && !rewriteLater.contains("out of the way: removed") && code == 0);

        // a row the change breaks, switched off, with a new row after it: the skips could not be judged (more are skipped while as many run),
        // and the fourth run finds the row skipped now failing on the change, with no case that could be it rewritten
        write(repo, "checks/broken.t", MENDED.replace("rows(int)[1]: pass", "rows(int)[1]: skip").replace("rows(int)[2]: pass", "rows(int)[2]: pass\nrows(int)[3]: pass"));
        String behind = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a row switched off behind a new one could not be judged by its skips, and the fourth run fails it when the change breaks it",
                behind.contains("(skipped now under a name that ran before: rows(int)[1]). So more are skipped while as many or more run")
                && behind.contains("      checks/broken.t  [self test]\n        rows(int)[1]: passed before the change; as the test was, on the code after the change "
                        + "rows(int)[1] fails: expected something else, and the change skips it now\n") && code == 1);

        // and a row taken out of the arguments: the file runs fewer cases. Named by place, taking out the last row (as here) and taking out the first,
        // which the change breaks, so that the last takes its place, give the same reports: the check fails either way, as the file reports fewer cases
        write(repo, "checks/broken.t", MENDED.replace("rows(int)[2]: pass\n", ""));
        String rowGone = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: the last row taken out of a parameterized test whose first row the change breaks: a test removed, and the first row could not be judged",
                rowGone.contains("at least 1 test was removed: the edited file reports fewer tests than it had before the change\n      checks/broken.t  [self test]\n        the file no longer "
                        + "reports 1 test under the name it had (rows(int)[2]), and reports none under a name it did not have: at least 1 test was removed.")
                && rowGone.contains(", rows(int)[1], wobbly passed before the change and fail on the code after it as the file was. A test of each name still runs after the change")
                && rowGone.contains("The file runs fewer tests than before (5 where it ran 6)\n") && code == 1);

        // a test the change breaks, renamed and rewritten for the changed behaviour: the rewrite fails before and passes after. The reports are the
        // same as for a test taken out and a sensitive one written: could not be judged, with both stories
        write(repo, "checks/broken.t", MENDED.replace("holds: pass", "holds anew: needs fixed"));
        String rewritten = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a broken test renamed and rewritten, where a sensitive test could be the rewrite, could not be judged, with both stories",
                rewritten.contains("\n        holds passed before the change and fails on the code after it as the file was, and the file does not report it now or skips it. "
                        + "It has 1 test that passes after the change and does not pass on both sides (holds anew), so it cannot be told whether the old test was rewritten "
                        + "for the changed behaviour under other names, or taken out of the way and other tests written\n")
                && rewritten.contains("\n        holds anew\n") && !rewritten.contains("out of the way: removed") && code == 0);
        // two broken tests renamed, and only one sensitive test that can be a rewrite: at least one was taken out of the way
        write(repo, "checks/broken.t", MENDED.replace("holds: pass", "holds anew: needs fixed").replace("fragile: pass", "sturdy: pass"));
        String one = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: more broken tests gone than cases that can be their rewrites: at least the rest were taken out of the way",
                one.contains("at least 1 test passed before the change and fails on the code after it as the test was, and the change took it out of the way: removed, "
                        + "renamed or switched off\n      checks/broken.t  [self test]\n        holds, fragile passed before the change and fail on the code after it as the file "
                        + "was, and the file does not report them now or skips them. It has 1 test that passes after the change and does not pass on both sides (holds anew), "
                        + "and at most 1 of the 2 old tests can be among them, rewritten for the changed behaviour: at least 1 was taken out of the way\n") && code == 1);
        write(repo, "checks/broken.t", BROKEN);

        // two cases exchange which of them is skipped: could not be judged by the reports, but the one switched off fails as it was on the change
        write(repo, "checks/swap.t", "guard: skip\nidle: pass\n");
        String swap = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a switch-off that only could be judged by the reports is turned into a failure by the fourth run when the old test fails on the change",
                swap.contains("It cannot be told whether a test that ran was switched off, or cases changed names")
                && swap.contains("      checks/swap.t  [self test]\n        guard: passed before the change; as the test was, on the code after the change guard fails: "
                        + "expected something else, and the change skips it now\n") && code == 1);
        write(repo, "checks/swap.t", SWAP);
        write(repo, "checks/broken.t", BROKEN);

        // the file as it was does not compile on the change (it needs v1, which the change takes out): nothing is known
        write(repo, "prod.txt", "fixed\n");
        write(repo, "checks/era.t", "era: pass\nenduring: pass\n");
        String era = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a gone test whose old file does not compile on the change could not be judged, and the report says why",
                era.contains("        the file no longer reports 1 test under the name it had (lasting), and reports 1 under a name it did not have (enduring). The reports cannot tell a test renamed "
                        + "from a test deleted and another written. The file as it was, run on the code after the change, does not compile, so whether the change broke these tests is not known\n")
                && !era.contains("out of the way") && code == 0);
        write(repo, "checks/era.t", ERA);
        write(repo, "prod.txt", "v1\nfixed\n");
    }

    /** A test file of the base whose report holds a case for a failure outside the tests, as Vitest reports a failed afterAll. */
    private static final String HOOKED = "adds: pass\nrefunds: pass\nafterAll hook: fail\n";

    /** A test file of the base whose report holds one case in place of its tests, as JUnit 4 reports a class it cannot set up. */
    private static final String INIT = "initializationError: error-unless never\n";

    /**
     * A report in which something failed need not list every test of its file: a fault outside the
     * tests may be reported as a case of its own, or in place of the tests. What only the counts
     * prove is then not said: could not be judged, with the reason.
     */
    private static void unlisted(Path repo) throws IOException {
        // the hook mended: the case that stood for its failure is gone, and no test was removed
        write(repo, "checks/hooked.t", "adds: pass\nrefunds: pass\n");
        String hook = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a failing case gone from a file whose report as it was held a failure is not a test removed: could not be judged, with the reason",
                hook.contains("      checks/hooked.t  [self test]\n        the file no longer reports 1 test under the name it had (afterAll hook), and reports none under a name it "
                        + "did not have. But the file as it was reported failures (a failure outside a test's body, in its set-up, tear-down or loading, may be reported in place of "
                        + "the file's tests or as a case of its own), so that report may not list every test: whether tests were removed is not known")
                && !hook.contains("was removed") && code == 0);
        write(repo, "checks/hooked.t", HOOKED);

        // the class's set-up mended: its two tests come out from behind the one case that stood for them, and neither is a new test
        write(repo, "checks/init.t", "works: pass\nalso works: pass\n");
        String init = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: tests that come out from behind a failure reported in their place are not new tests that pass on both sides: could not be judged, with the reason",
                init.contains("      checks/init.t  [self test]\n        works: passes before and after, but it cannot be told whether it is a new test: the file reports 2 tests it did "
                        + "not have and no longer reports 1 it had. It may be a new test that does not test the change, or a test the file had: the test it no longer reports, under a "
                        + "new name, or a test the file had that a report did not list: the file as it was reported failures")
                && !init.contains("new test pass") && code == 0);
        write(repo, "checks/init.t", INIT);

        // a file whose tests failed as it was: one test gone and one skipped now. Neither count is said, as the report as it was may not list every test.
        // Where the names and their numbers stayed the same, the skip is said all the same (see switchedOff: a test that failed before and is skipped now)
        write(repo, "checks/off.t", OFF.replace("target: pass\n", "").replace("params(int)[1]: pass", "params(int)[1]: skip"));
        String failedBefore = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: in a file whose report as it was held failures, fewer tests run and one is gone: could not be judged, with the reason, neither switched off nor removed",
                failedBefore.contains("        the file runs 3 of its tests where it ran 5 before the change, and skips 3 where it skipped 2 (skipped now under a name that ran before: "
                        + "params(int)[1]). But the file as it was reported failures") && failedBefore.contains("whether a test that ran does not run now is not known")
                && failedBefore.contains("whether tests were removed is not known") && !failedBefore.contains("is skipped now\n") && !failedBefore.contains("was removed") && code == 0);
        write(repo, "checks/off.t", OFF);

        // after the change a failure stands in place of the tests: it fails the check, and the counts of that report are not said on top of it
        write(repo, "checks/side/both.t", "initializationError: error-unless never\n");
        String standIn = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a failure reported in place of a file's tests after the change fails the check, and is not also counted as tests removed",
                standIn.contains("1 fails after the change\n      checks/side/both.t  [self test]\n        initializationError: throws IllegalStateException")
                && standIn.contains("        the file no longer reports 2 tests under the names it had (stays, goes), and reports 1 under a name it did not have (initializationError). But "
                        + "after the change the file reports failures") && !standIn.contains("was removed") && code == 1);
        write(repo, "checks/side/both.t", SIDE);

        // and an old test the change breaks, gone from such a report, is not said to be taken out of the way
        write(repo, "checks/broken.t", MENDED.replace("holds: pass\n", "") + "initializationError: error-unless never\n");
        String brokenStandIn = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a broken old test gone from a report that holds a failure after the change could not be judged, not taken out of the way",
                brokenStandIn.contains("        holds passed before the change and fails on the code after it as the file was, and the file does not report it now or skips it. But after "
                        + "the change the file reports failures") && !brokenStandIn.contains("out of the way: removed") && brokenStandIn.contains("1 fails after the change") && code == 1);
        write(repo, "checks/broken.t", BROKEN);
    }

    /** A test file of the base with two nested classes that have a method of one name, the first of which the fix breaks. */
    private static final String PAIR = "demo.Pair$A::works: fails-with fixed\ndemo.Pair$B::works: pass\ndemo.Pair::top: pass\n";

    /** A test file of the base that a second stand-in runner owns. */
    private static final String SIDE = "stays: pass\ngoes: pass\n";

    /** A test file of the base whose test passes on the base's code and fails on the code of the fix, beside a skipped one. */
    private static final String SWAP = "guard: fails-with fixed\nidle: skip\n";

    /** A test file of the base whose only test is skipped: none of its tests ran before the change. */
    private static final String ASLEEP = "dozing: skip\n";

    /** A test file of the base with two tests whose names differ only by "[3]". */
    private static final String TITLES = "rate: pass\nrate[3]: skip\n";

    /** A test file of the base whose test, as it is there, runs only the first time it is run in a checkout. */
    private static final String ONCE = "target: pass-once\n";

    /** A test file of the base as Surefire reports nested JUnit classes: two nested classes with a method of one name, and a method of the outer class. */
    private static final String NESTED = "demo.Outer$A::works: pass\ndemo.Outer$B::works: pass\ndemo.Outer::top: pass\n";

    /** A test file of the base whose tests the change renames, rewrites or removes. */
    private static final String NAMED = "has a name: pass\n  expects payments\nadds %i: pass\nit\\'s a name: pass\nrejectsNull(String)[%i]: pass\nrefund: pass\ngoes: pass\n";

    /** A test file of the base that, as it is there, gives no result: what it had cannot be learnt from a run of it. */
    private static final String LEGACY = "held: pass\nslow: timeout\n";

    /** A test file of the base that, as it is there, compiles only on the base's code. */
    private static final String ERA = "era: compile-needs v1\nlasting: pass\n";

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
