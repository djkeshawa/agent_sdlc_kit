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

        // an uncommitted change: the fix, an edited test file, a new untracked test file, an ignored file
        write(repo, "prod.txt", "v1\nfixed\n");
        write(repo, "tests/old.t", "kept: pass\nsensitive: needs fixed\nproves nothing: pass\n");
        write(repo, "tests/new.t", "new feature: error-unless fixed\nbroken: fail\nlater: skip\nrandom: flaky\n");
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

        // two cases under one name cannot be told apart
        write(repo, "tests/twins.t", "same: needs fixed\nsame: pass\n");
        String twins = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: two tests that report one name are not judged", twins.contains("same: more than one test reports this name") && !twins.contains("        same\n"));
        Files.delete(repo.resolve("tests/twins.t"));

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
