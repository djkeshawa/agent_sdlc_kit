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
        check("check: and from a script", sdlc.check.TestNames.of("a.test.ts", "describe('x', () => { it('adds', () => {}); test.each([1])(\"each %i\", () => {}); it.skip(`later`, () => {}) })")
                .containsAll(java.util.Set.of("adds", "later")));
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

        // which cases are runs of one parameterized test: the runner says, from the shape its tool gives their names
        check("check: Surefire's names of the runs of a parameterized test give the test and the place",
                sdlc.check.Runners.invocation("maven", "check(int)[3]").equals("check(int) @ 3") && sdlc.check.Runners.invocation("maven", "check(int, String)[12]").equals("check(int, String) @ 12")
                && sdlc.check.Runners.invocation("maven", "check(int[])[1]").equals("check(int[]) @ 1") && sdlc.check.Runners.invocation("maven", "testPrint[0]").equals("testPrint @ 0"));
        check("check: any other name Surefire writes is a test of its own", java.util.stream.Stream.of("rate", "check(int)", "[1] 1, 2", "check[0: x=1]", "factory()[1][2]", "a > rate[3]", "check(int)[3] again", "check(int)[x]")
                .allMatch(n -> sdlc.check.Runners.invocation("maven", n).equals("a test of its own")));
        check("check: a vitest title is a test, brackets and all", sdlc.check.Runners.invocation("vitest", "rate[3]").equals("a test of its own")
                && sdlc.check.Runners.invocation("vitest", "check(int)[3]").equals("a test of its own"));

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
        write(repo, "checks/titles.vitest.t", TITLES);
        write(repo, "checks/once.t", ONCE);
        write(repo, "checks/nested.t", NESTED);
        write(repo, "checks/broken.t", BROKEN);
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
                && history.contains("tests: 1 fail before and pass after") && history.contains("does not test the change: tests/old.t  idle")
                && history.matches("(?s).*\\n  \\d+ new tests? pass(es)? on both sides, so do(es)? not test (its|their) commit\\n.*") && code == 0);
        check("check: --since names the tests a commit switched off or removed", history.contains("tests: 1 ran before, skipped after; 1 removed\n")
                && history.contains("ran before, skipped after: checks/judged.t  turned off") && history.contains("removed: checks/judged.t  goes too")
                && history.contains("1 test ran before its commit and is skipped after it; 1 test was removed"));
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
                r.contains("1 new test passes before and after, so it does not test the change\n      checks/judged.t  [self test]\n        idle\n") && code == 1);
        check("check: the tests the file as it was reported are not counted",
                r.contains("5 tests that were already in the edited files pass before and after (not counted)") && !r.contains("        stays"));
        write(repo, "checks/judged.t", JUDGED);

        // one test switched off; another was off before and stays off
        write(repo, "checks/judged.t", JUDGED.replace("turned off: pass", "turned off: skip"));
        String off = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test that ran before the change and is skipped now is a problem",
                off.contains("1 ran before the change and is skipped now\n      checks/judged.t  [self test]\n        turned off\n") && code == 1);
        check("check: a test skipped before and after the change is not counted",
                off.contains("1 test is skipped as before the change: it was skipped before it too, or its test skips as many of its cases as before, or fewer (not counted)") && !off.contains("        was off") && !off.contains(" is skipped\n"));
        java.io.InputStream in = System.in;
        java.io.PrintStream err = System.err;
        ByteArrayOutputStream reasons = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(reasons, true, StandardCharsets.UTF_8));
            System.setIn(new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
            int blocked = Check.run(Map.of("selftest-runner", "true", "stop-hook", "true"));
            check("check: the stop hook blocks on a test that ran before the change and is skipped now", blocked == 2
                    && reasons.toString(StandardCharsets.UTF_8).contains("ran before your change and is skipped now: checks/judged.t » turned off"));
        } finally {
            System.setIn(in);
            System.setErr(err);
        }
        // a new test that is skipped is listed, as before
        write(repo, "checks/judged.t", JUDGED + "later: skip\n");
        String later = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a new test that is skipped is listed as skipped, not as switched off",
                later.contains("1 is skipped\n      checks/judged.t  [self test]\n        later\n") && !later.contains("skipped now") && code == 0);
        write(repo, "checks/judged.t", JUDGED);

        // one gone and none new: removed, by name
        write(repo, "checks/judged.t", JUDGED.replace("goes too: pass\n", ""));
        String one = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test the file had, where it reports no new one, was removed",
                one.contains("1 test was removed: the edited file reports fewer tests than it had before the change\n      checks/judged.t  [self test]\n        goes too\n") && code == 1);
        reasons.reset();
        try {
            System.setErr(new PrintStream(reasons, true, StandardCharsets.UTF_8));
            System.setIn(new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
            int blocked = Check.run(Map.of("selftest-runner", "true", "stop-hook", "true"));
            check("check: the stop hook blocks on a test removed", blocked == 2
                    && reasons.toString(StandardCharsets.UTF_8).contains("removed: checks/judged.t » goes too (the file had it before your change)"));
        } finally {
            System.setIn(in);
            System.setErr(err);
        }

        // two gone and one new in their place: the file lost a test, and which one cannot be told
        write(repo, "checks/judged.t", "stays: pass\nturned off: pass\nwas off: skip\nidle: pass\n");
        String fewer = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a file that reports fewer tests than it had lost tests: at least so many were removed, with both lists",
                fewer.contains("at least 1 test was removed: the edited file reports fewer tests than it had before the change\n      checks/judged.t  [self test]\n"
                        + "        the file had 2 tests that it does not report now (goes, goes too), and reports 1 that it did not have (idle): at least 1 was removed, and which cannot be told\n")
                && code == 1);
        check("check: and its new test that passes on both sides may be one of them renamed: could not be judged, not accused",
                fewer.contains("        idle: passes before and after, but the file does not report 2 tests it had, and this may be one of them under a new name\n")
                && !fewer.contains("new test passes"));
        reasons.reset();
        try {
            System.setErr(new PrintStream(reasons, true, StandardCharsets.UTF_8));
            System.setIn(new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
            int blocked = Check.run(Map.of("selftest-runner", "true", "stop-hook", "true"));
            check("check: the stop hook blocks on a file that lost tests", blocked == 2
                    && reasons.toString(StandardCharsets.UTF_8).contains("tests removed: checks/judged.t (the file had 2 tests that it does not report now (goes, goes too)"));
        } finally {
            System.setIn(in);
            System.setErr(err);
        }

        // as many new as gone, but one that ran is skipped under its new name: fewer run than before
        write(repo, "checks/judged.t", JUDGED.replace("goes: pass", "went: skip"));
        String renamedOff = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a gone test that ran, where the new one is skipped, is a test that no longer runs",
                renamedOff.contains("at least 1 ran before the change and is skipped now\n      checks/judged.t  [self test]\n        the file had 1 test that it does not report now (goes), "
                        + "and reports 1 that it did not have (went). 1 of the tests it does not report now ran before the change, and 0 of the new ones run: "
                        + "at least 1 test that ran does not run now, switched off under a new name or removed while skipped tests were added\n") && code == 1);

        // a renamed group: the test keeps its own title, and is the same test
        write(repo, "checks/judged.t", JUDGED.replace("stays: pass", "group > stays: pass"));
        String regrouped = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test under a renamed group, with its title unchanged, is the same test and a note", code == 0 && !regrouped.contains("removed")
                && regrouped.contains("1 test is reported under another group, with its own title unchanged, and counts as the same test: stays is now group > stays")
                && regrouped.contains("5 tests that were already in the edited files pass before and after (not counted)"));
        // but not when the title fits two new tests: which one it is cannot be told. One gone and two new: could not be judged,
        // but two new tests pass on both sides, and at most one of them can be the gone one
        write(repo, "checks/judged.t", JUDGED.replace("stays: pass\n", "") + "one > stays: pass\ntwo > stays: pass\n");
        String twoGroups = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a title that fits two new tests pairs with neither: the gone test could not be judged", code == 1 && !twoGroups.contains("removed")
                && twoGroups.contains("        the file had 1 test that it does not report now (stays), and reports 2 that it did not have (one > stays, two > stays). "
                        + "The reports cannot tell a test renamed from a test deleted and another written\n"));
        check("check: more new tests pass on both sides than tests are gone: the rest are new and do not test the change",
                twoGroups.contains("at least 1 new test passes before and after, so it does not test the change\n      checks/judged.t  [self test]\n"
                        + "        the file has 2 tests it did not have that pass before and after (one > stays, two > stays). It does not report 1 test it had (stays), "
                        + "and at most that one can be among them under a new name: at least 1 is a new test that does not test the change\n"));

        write(repo, "checks/judged.t", JUDGED);

        // the audit's case: an outer class renamed whose nested classes share a method name. The title fits two tests on each side
        write(repo, "checks/nested.t", NESTED.replace("Outer", "Renamed"));
        String nested = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: an outer class renamed whose nested classes share a method name could not be judged, and is not a removal", code == 0 && !nested.contains("removed")
                && nested.contains("        the file had 2 tests that it does not report now (demo.Outer$A.works, demo.Outer$B.works), and reports 2 that it did not have "
                        + "(demo.Renamed$A.works, demo.Renamed$B.works). The reports cannot tell a test renamed from a test deleted and another written\n")
                && nested.contains("1 test is reported under another group, with its own title unchanged, and counts as the same test: demo.Outer.top is now demo.Renamed.top")
                && nested.contains("        works: passes before and after, but the file does not report 2 tests it had, and this may be one of them under a new name\n")
                && !nested.contains("new test"));
        write(repo, "checks/nested.t", NESTED);

        renamedOrNot(repo);
        switchedOff(repo);
        oldTestsOnTheChange(repo);

        // a test that went to another changed file has moved
        write(repo, "checks/judged.t", JUDGED.replace("goes too: pass\n", ""));
        write(repo, "checks/moved.t", "goes too: needs fixed\n");
        String moved = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test now in another changed file has moved, and is not counted as removed", code == 0 && !moved.contains("removed")
                && moved.contains("1 test moved to another changed file: goes too (now in checks/moved.t)"));
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
                code == 0 && !renamed.contains("removed") && !renamed.contains("new test") && !renamed.contains("Note:")
                && renamed.contains("        the file had 5 tests that it does not report now (has a name, adds 1, adds 2, it's a name, rejectsNull(String)), "
                        + "and reports 5 that it did not have (has a title, sums 1, sums 2, it's a title, refusesNull(String)). "
                        + "The reports cannot tell a test renamed from a test deleted and another written\n"));
        check("check: and each new test that passes on both sides may be a gone one: could not be judged",
                renamed.contains("7 could not be judged\n") && renamed.contains("        refusesNull(String)[2]: passes before and after, but the file does not report 5 tests it had, and this may be one of them under a new name\n"));

        // a test renamed, and a new one written right after it: two new tests pass on both sides, and only one can be the gone one
        write(repo, "checks/named.t", NAMED.replace("goes: pass\n", "went: pass\nextra: pass\n"));
        String beside = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test renamed next to a new one: at least one new test passes on both sides, and the check fails",
                beside.contains("at least 1 new test passes before and after, so it does not test the change\n      checks/named.t  [self test]\n        the file has 2 tests it did not have "
                        + "that pass before and after (went, extra). It does not report 1 test it had (goes), and at most that one can be among them under a new name")
                && beside.contains("(goes), and reports 2 that it did not have (went, extra). The reports cannot tell") && !beside.contains("removed") && code == 1);

        // one test deleted, another added elsewhere: the reports say exactly what they say of a rename
        write(repo, "checks/named.t", "arrives: pass\n" + NAMED.replace("goes: pass\n", ""));
        String swapped = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test deleted and another added cannot be told from a rename: could not be judged, not a failure",
                swapped.contains("        the file had 1 test that it does not report now (goes), and reports 1 that it did not have (arrives). The reports cannot tell")
                && swapped.contains("        arrives: passes before and after, but the file does not report 1 test it had, and this may be that one under a new name\n")
                && !swapped.contains("removed") && !swapped.contains("new test") && code == 0);

        // a row put in among the arguments of a parameterized test: every later run has another place, and the last has none
        // in the file as it was. The test (the method) is what is new or not, never a place.
        write(repo, "checks/named.t", NAMED.replace("rejectsNull(String)[%i]: pass\n", "rejectsNull(String)[1]: pass\nrejectsNull(String)[2]: pass\nrejectsNull(String)[3]: pass\n"));
        String rows = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a run of a parameterized test the file as it was ran is not a new test, whatever its place",
                !rows.contains("new test") && rows.contains("10 tests that were already in the edited files pass before and after (not counted)") && code == 0);
        write(repo, "checks/named.t", NAMED.replace("rejectsNull(String)[%i]: pass\n", "rejectsNull(String)[1]: pass\nrejectsNull(String)[2]: pass\nrejectsNull(String)[3]: pass\n"
                + "rejectsBlank(String)[1]: pass\nrejectsBlank(String)[2]: pass\n"));
        String newMethod = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: the runs of a parameterized test the file as it was did not have are new",
                newMethod.contains("2 new tests pass before and after, so they do not test the change\n      checks/named.t  [self test]\n        rejectsBlank(String)[1]\n        rejectsBlank(String)[2]\n") && code == 1);
        // renamed, and a row added: the runs of one parameterized test are one test in the counts, so one gone and one new
        write(repo, "checks/named.t", NAMED.replace("rejectsNull(String)[%i]: pass\n", "refusesNull(String)[%i]: pass\nrefusesNull(String)[3]: pass\n"));
        String renamedRows = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a parameterized test renamed, with a row added, is one test gone and one new: could not be judged, not a new test",
                renamedRows.contains("(rejectsNull(String)), and reports 1 that it did not have (refusesNull(String)). The reports cannot tell")
                && !renamedRows.contains("new test") && !renamedRows.contains("removed") && code == 0);
        // a row taken out: the test still runs, so it was not removed, and which arguments went cannot be told
        write(repo, "checks/named.t", NAMED.replace("rejectsNull(String)[%i]: pass\n", "rejectsNull(String)[1]: pass\n"));
        String fewerRows = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a parameterized test that runs fewer times than before is not removed, and could not be judged",
                !fewerRows.contains("removed") && fewerRows.contains("\n        rejectsNull(String): ran 2 times in the file as it was and runs 1 times now. "
                        + "The report names its runs only by their place, so it cannot be told which arguments were taken out\n") && code == 0);
        write(repo, "checks/named.t", NAMED);

        // every test of the file removed: the file runs and has none
        write(repo, "checks/named.t", "  nothing left\n");
        String emptied = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a file whose tests were all removed reports them removed, a parameterized test once",
                emptied.contains("7 tests were removed: the edited file reports fewer tests than it had before the change\n      checks/named.t  [self test]\n        has a name\n")
                && emptied.contains("        rejectsNull(String)\n") && !emptied.contains("reports no tests") && code == 1);
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
                failed.contains("2 ran before the change and are skipped now\n      checks/off.t  [self test]\n        failing\n        throwing\n") && code == 1);

        // two cases of one name before the change, one of which ran: both skipped now
        write(repo, "checks/off.t", OFF.replace("twin: pass", "twin: skip"));
        String twins = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: of two tests of one name, one ran and both are skipped now: skipped now, with the counts",
                twins.contains("1 ran before the change and is skipped now\n      checks/off.t  [self test]\n        twin: 2 of 2 are skipped after the change; 1 of 2 ran before it\n") && code == 1);

        // a second test of the name exists only before the change: it does not hide the one switched off
        write(repo, "checks/off.t", OFF.replace("target: pass", "target: skip\ntarget: skip-without fixed"));
        String hidden = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: two tests of one name on the side before the change do not hide a test switched off",
                hidden.contains("1 ran before the change and is skipped now\n      checks/off.t  [self test]\n        target\n") && code == 1);

        // the arguments of a parameterized test reordered: the same ones run and are skipped, only their places changed
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass\nparams(int)[2]: skip", "params(int)[1]: skip\nparams(int)[2]: pass"));
        String reordered = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: invocations of a parameterized test that change places are not switched off", !reordered.contains("skipped now") && code == 0
                && reordered.contains("tests are skipped as before the change: each was skipped before it too, or its test skips as many of its cases as before, or fewer (not counted)"));
        // one invocation that ran is skipped now, and a new one that runs is added: the new one does not make up for it, and the
        // reports cannot tell this from a skipped case added in front of the others. Not passed, not accused: could not be judged.
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass", "params(int)[1]: skip").replace("params(int)[2]: skip", "params(int)[2]: skip\nparams(int)[3]: pass"));
        String paid = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: an invocation switched off is not made up for by a new one that runs: it could not be judged, in every run",
                paid.contains("could not be judged\n") && paid.contains("\n        params(int): 2 of 3 are skipped after the change and 1 of 2 before it, while 1 run where 1 ran before. "
                        + "The report names its cases only by their place, so it cannot be told whether a skipped case was added or a case that ran was switched off and another one added\n")
                && !paid.contains("skipped now") && !paid.contains("from run to run") && !paid.contains("new test"));
        // a skipped case added in front of cases that all ran: the same
        write(repo, "checks/named.t", NAMED.replace("rejectsNull(String)[%i]: pass\n", "rejectsNull(String)[1]: pass\nrejectsNull(String)[2]: skip\nrejectsNull(String)[3]: pass\n"));
        String inserted = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a skipped case among cases that ran, where as many run as before, could not be judged",
                inserted.contains("\n        rejectsNull(String): 1 of 3 are skipped after the change and 0 of 2 before it, while 2 run where 2 ran before. The report names its cases only by their place")
                && !inserted.contains("skipped now") && !inserted.contains("new test") && code == 0);
        write(repo, "checks/named.t", NAMED);
        // a run put in front of a skipped one: the skipped run has a place the file as it was did not have, and its test skips as many as before
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass", "params(int)[1]: pass\nparams(int)[2]: pass").replace("params(int)[2]: skip", "params(int)[3]: skip"));
        String shifted = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a skipped run moved to a new place by a row put in front of it is skipped as before, not new",
                !shifted.contains(" is skipped\n") && !shifted.contains("skipped now") && !shifted.contains("new test") && code == 0);
        // what could not be judged in one run and is not switched off in another is unstable
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass\nparams(int)[2]: skip", "params(int)[1]: skip-once fixed\nparams(int)[2]: skip\nparams(int)[3]: pass"));
        String undecidedOnce = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a test that could not be judged in one run and is not switched off in another is unstable",
                undecidedOnce.contains("1 gave different results from run to run\n      checks/off.t  [self test]\n        params(int): skipped after the change in one run and not in another")
                && !undecidedOnce.contains("params(int): 2 of 3") && !undecidedOnce.contains("skipped now"));
        // cases added after the last one, skipped: an ordinary new argument whose assumption does not hold. Not switched off
        write(repo, "checks/off.t", OFF.replace("params(int)[2]: skip", "params(int)[2]: skip\nparams(int)[3]: skip"));
        String appended = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a skipped case added after the last one is listed as skipped, and does not switch its test off",
                appended.contains("1 is skipped\n      checks/off.t  [self test]\n        params(int)[3]\n") && !appended.contains("skipped now") && code == 0);
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass", "params(int)[1]: skip"));
        String fewer = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: when more invocations are skipped than before, the method is switched off, with the counts",
                fewer.contains("1 ran before the change and is skipped now\n      checks/off.t  [self test]\n        params(int): 2 of 2 are skipped after the change; 1 of 2 ran before it\n") && code == 1);

        // a skip that comes and goes is unstable, not switched off; one run alone cannot tell
        write(repo, "checks/off.t", OFF.replace("target: pass", "target: skip-once fixed"));
        String wavering = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a test skipped in one run after the change and not in another is unstable, not switched off",
                !wavering.contains("skipped now") && wavering.contains("1 gave different results from run to run\n      checks/off.t  [self test]\n        target: skipped after the change in one run and not in another") && code == 0);
        write(repo, "checks/off.t", OFF);

        // the file as it was ran the test in its first run and skipped it in a later one: the repeat runs of the old side count
        write(repo, "checks/once.t", "target: skip\n");
        String once = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a test that ran as the file was in one run and was skipped there in another is unstable, not switched off",
                once.contains("1 gave different results from run to run\n      checks/once.t  [self test]\n        target: skipped after the change in one run and not in another")
                && !once.contains("skipped now") && code == 0);
        write(repo, "checks/once.t", ONCE);

        // a Vitest title is the test, brackets and all: "rate" and "rate[3]" are two tests, and one does not pay for the other
        write(repo, "checks/titles.vitest.t", "rate: skip\nrate[3]: pass\n");
        String titles = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test whose title differs from another's only by a number in brackets is judged on its own",
                titles.contains("1 ran before the change and is skipped now\n      checks/titles.vitest.t  [self test]\n        rate\n") && code == 1);
        write(repo, "checks/titles.vitest.t", TITLES);

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
        check("check: the fourth run is not made when no test is gone, switched off or run fewer times", Check.fourthCheckout(sdlc.check.Change.of(null)).equals("not made, head intact"));

        // a test the change breaks, renamed to one that passes: the old one as it was fails on the change
        write(repo, "checks/broken.t", MENDED.replace("holds: pass", "kept up: pass"));
        String renamed = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a gone test that passed before and fails as it was on the change was taken out of the way: the check fails, in every run",
                renamed.contains("1 test passed before the change and fails on the code after it as the test was, and the change took it out of the way: "
                        + "removed, renamed, switched off or run fewer times\n      checks/broken.t  [self test]\n        holds: passed before the change; as the test was, "
                        + "on the code after the change holds fails: expected something else. The edited file does not report it now\n") && code == 1);
        check("check: the old tests of that file that are still there under their names are not reported, though their old versions fail too",
                !renamed.contains("fragile") && !renamed.contains("rows(int)") && !renamed.contains("wobbly") && !renamed.contains("The reports cannot tell"));
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

        // deleted: the same, said once, not also as removed
        write(repo, "checks/broken.t", MENDED.replace("holds: pass\n", ""));
        String deleted = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a deleted test the change breaks is said to be taken out of the way, and not also listed as removed",
                deleted.contains("        holds: passed before the change; as the test was, on the code after the change holds fails") && !deleted.contains("removed: the edited file") && code == 1);

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
                rowOff.contains("      checks/broken.t  [self test]\n        rows(int): passed before the change; as the test was, on the code after the change rows(int)[1] fails: "
                        + "expected something else, and the change switched it off (1 of 2 are skipped after the change; 2 of 2 ran before it)\n")
                && !rowOff.contains("skipped now") && code == 1);

        // and one taken out of the arguments: the test runs fewer times
        write(repo, "checks/broken.t", MENDED.replace("rows(int)[2]: pass\n", ""));
        String rowGone = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a parameterized test that runs fewer times, one of whose old runs fails as it was on the change, was taken out of the way",
                rowGone.contains("        rows(int): passed before the change; as the test was, on the code after the change rows(int)[1] fails: expected something else, "
                        + "and the change runs it fewer times (2 before, 1 now)\n") && code == 1);
        write(repo, "checks/broken.t", BROKEN);

        // the file as it was does not compile on the change (it needs v1, which the change takes out): nothing is known
        write(repo, "prod.txt", "fixed\n");
        write(repo, "checks/era.t", "era: pass\nenduring: pass\n");
        String era = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a gone test whose old file does not compile on the change could not be judged, and the report says why",
                era.contains("        the file had 1 test that it does not report now (lasting), and reports 1 that it did not have (enduring). The reports cannot tell a test renamed "
                        + "from a test deleted and another written. The file as it was, run on the code after the change, does not compile, so whether the change broke these tests is not known\n")
                && !era.contains("out of the way") && code == 0);
        write(repo, "checks/era.t", ERA);
        write(repo, "prod.txt", "v1\nfixed\n");
    }

    /** A test file of the base, named as Vitest names its tests: two tests whose titles differ only by "[3]". */
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
