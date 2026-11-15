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
        // Surefire's rerunFailingTestsCount: a case that failed and passed when run again (flakyFailure, flakyError), and one that failed every
        // time (its failure or error, then a rerunFailure or rerunError for each rerun). The last result and the rerun are kept apart
        Files.writeString(xml, """
                <testsuite name="demo.AgainTest">
                  <testcase name="flaky" classname="demo.AgainTest"><flakyFailure message="first" type="java.lang.AssertionError"><stackTrace>x</stackTrace></flakyFailure></testcase>
                  <testcase name="flakyError" classname="demo.AgainTest"><flakyError message="first" type="java.lang.IllegalStateException"><stackTrace>x</stackTrace></flakyError></testcase>
                  <testcase name="spent" classname="demo.AgainTest"><failure message="still" type="java.lang.AssertionError">x</failure><rerunFailure message="still" type="java.lang.AssertionError"><stackTrace>x</stackTrace></rerunFailure><rerunFailure message="still" type="java.lang.AssertionError"><stackTrace>x</stackTrace></rerunFailure></testcase>
                  <testcase name="" classname="demo.AgainTest"><error message="set-up" type="java.lang.IllegalStateException">x</error><rerunError message="set-up" type="java.lang.IllegalStateException"><stackTrace>x</stackTrace></rerunError></testcase>
                  <testcase name="plain" classname="demo.AgainTest"/>
                </testsuite>
                """);
        var again = sdlc.check.JUnitXml.read(xml);
        check("check: a case the build ran again that passed then is passed, with its rerun kept", again.get(0).status() == sdlc.check.JUnitXml.Status.PASSED && again.get(0).retried()
                && again.get(1).status() == sdlc.check.JUnitXml.Status.PASSED && again.get(1).retried());
        check("check: a case that failed every time the build ran it is failed or errored, with its rerun kept, not passed",
                again.get(2).status() == sdlc.check.JUnitXml.Status.FAILED && again.get(2).retried() && again.get(2).message().equals("still")
                && again.get(3).status() == sdlc.check.JUnitXml.Status.ERRORED && again.get(3).retried() && again.get(3).type().endsWith("IllegalStateException"));
        check("check: a case the build did not run again is not marked so", again.get(4).status() == sdlc.check.JUnitXml.Status.PASSED && !again.get(4).retried());
        Files.delete(xml);
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
        Path none = repo.resolve("no-retries.json"); // where the check's reporter wrote nothing
        check("check: vitest's entry for a file without tests stays a failed case", sdlc.check.Runners.vitestReport(xml, none, "src/app.test.ts").equals("ERRORED app"));
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
        Path retries = repo.resolve("retries.json");
        Files.writeString(retries, "{\"tests\":[{\"file\":\"src/refund.test.ts\",\"name\":\"refunds > rejects\",\"state\":\"fail\",\"retryCount\":0,\"retry\":null},"
                + "{\"file\":\"src/refund.test.ts\",\"name\":\"refunds > accepts\",\"state\":\"pass\",\"retryCount\":0,\"retry\":null}]}");
        check("check: a test whose failure starts with vitest's words for an empty file is still a failure",
                sdlc.check.Runners.vitestReport(xml, retries, "src/refund.test.ts").equals("ERRORED refunds > rejects; PASSED refunds > accepts"));
        vitestRetries(repo, xml, retries, none);
        vitestNames(xml, retries, none);
        vitestReporterRuns(repo);
        Files.delete(xml);
        Files.delete(retries);

        // Maven: the build passes and no report names the class: the class ran and has no tests. A failed build says nothing.
        if (Proc.WINDOWS) return; // the stand-in for Maven below is a shell script
        Path m = repo.resolve("maven");
        Files.createDirectories(m.resolve("src/test/java/demo"));
        Files.writeString(m.resolve("pom.xml"), "<project/>");
        Files.writeString(m.resolve("src/test/java/demo/ATest.java"), "package demo;\nclass ATest {}\n");
        // the stand-in uses nothing but the shell itself: the self test also runs where the PATH holds no mkdir, cp or cat
        Files.createDirectories(m.resolve("target/surefire-reports"));
        Files.writeString(m.resolve("mvnw"), """
                #!/bin/sh
                # stands in for Maven: copies the report it is given into place, says what Maven would, exits as told
                if [ -f given-report.xml ]; then
                  while IFS= read -r line || [ -n "$line" ]; do printf '%s\\n' "$line"; done < given-report.xml > target/surefire-reports/TEST-given.xml
                fi
                echo "[ERROR] Failed to execute goal: something outside the tests went wrong"
                read -r code < given-exit
                exit "$code"
                """);
        Files.writeString(m.resolve("given-exit"), "0\n");
        check("check: a Maven build that passes with no report for the class: it ran and has no tests", sdlc.check.Runners.mavenRun(m, "src/test/java/demo/ATest.java").equals("ran, no tests"));
        Files.writeString(m.resolve("given-report.xml"), "<testsuite name=\"demo.OtherTest\"><testcase name=\"other\" classname=\"demo.OtherTest\"/></testsuite>");
        check("check: a report of another class says nothing of this one", sdlc.check.Runners.mavenRun(m, "src/test/java/demo/ATest.java").equals("ran, no tests"));
        Files.writeString(m.resolve("given-report.xml"), "<testsuite name=\"demo.ATest\"><testcase name=\"works\" classname=\"demo.ATest\"/></testsuite>");
        check("check: and one of the class gives its cases", sdlc.check.Runners.mavenRun(m, "src/test/java/demo/ATest.java").equals("PASSED works"));
        Files.delete(m.resolve("given-report.xml"));
        Files.writeString(m.resolve("given-exit"), "1\n");
        String failed = sdlc.check.Runners.mavenRun(m, "src/test/java/demo/ATest.java");
        check("check: a Maven build that fails with no report for the class did not run it, and is not taken for no tests",
                failed.startsWith("no result: did not run (Maven exit 1: Failed to execute goal: something outside the tests went wrong"));
        try (Stream<Path> s = Files.walk(m)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /**
     * CR5-1: vitest's JUnit report writes a test that failed and passed when vitest ran it again as
     * an ordinary pass. The check's own reporter says, per test, how often vitest ran it again; a
     * run whose account is missing, does not fit the report or does not count retries does not
     * say which passes were at the first try, once for the file.
     */
    private static void vitestRetries(Path repo, Path xml, Path retries, Path none) throws IOException {
        // the report Vitest 3.2.4 wrote for the old guard on the new code: it failed at the first try and passed when run again
        Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8" ?>
                <testsuites name="vitest tests" tests="2" failures="0" errors="0" time="0.009944472">
                    <testsuite name="app.test.js" timestamp="2026-11-15T00:35:40.898Z" hostname="h" tests="2" failures="0" errors="0" skipped="0" time="0.009944472">
                        <testcase classname="app.test.js" name="guard" time="0.008255058">
                        </testcase>
                        <testcase classname="app.test.js" name="group &gt; plain" time="0.001">
                        </testcase>
                    </testsuite>
                </testsuites>
                """);
        java.util.function.Function<String, String> said = json -> {
            try {
                Files.writeString(retries, json);
                return sdlc.check.Runners.vitestReport(xml, retries, "app.test.js");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        String guard = "{\"file\":\"app.test.js\",\"name\":\"guard\",\"state\":\"pass\",\"retryCount\":%s,\"retry\":%s}";
        String plain = "{\"file\":\"app.test.js\",\"name\":\"group > plain\",\"state\":\"pass\",\"retryCount\":0,\"retry\":null}";
        check("vitest retries: a test vitest ran again before it passed is marked so, beside one that passed at the first try",
                said.apply("{\"tests\":[" + guard.formatted("1", "1") + "," + plain + "]}").equals("PASSED guard (run again); PASSED group > plain"));
        check("vitest retries: a test that passed at the first try is not marked, also where it was given retries",
                said.apply("{\"tests\":[" + guard.formatted("0", "2") + "," + plain + "]}").equals("PASSED guard; PASSED group > plain"));
        check("vitest retries: a version of vitest that does not count retries says nothing of a pass where none was given (no retries, no count)",
                said.apply("{\"tests\":[" + guard.formatted("null", "null") + "," + plain + "]}").equals("PASSED guard; PASSED group > plain"));
        check("vitest retries: where a test was given retries and vitest does not count them, the run says so, once for the file",
                said.apply("{\"tests\":[" + guard.formatted("null", "1") + "," + plain + "]}")
                        .equals("PASSED guard; PASSED group > plain [retries not known: this version of vitest does not say whether a test passed only when it ran it again]"));
        check("vitest retries: where the check's reporter wrote nothing, no pass of the run is taken for one at the first try",
                sdlc.check.Runners.vitestReport(xml, none, "app.test.js").equals("PASSED guard; PASSED group > plain [retries not known: vitest did not write the check's account of its "
                        + "tests (its reporter did not load or did not finish)]"));
        check("vitest retries: an account that does not name a passed test of the report does not say it either",
                said.apply("{\"tests\":[" + plain + "]}").equals("PASSED guard; PASSED group > plain [retries not known: vitest's account of its tests does not name a test its JUnit "
                        + "report lists as passed (guard)]"));
        check("vitest retries: an account of another file says nothing of this one",
                said.apply("{\"tests\":[" + guard.formatted("1", "1").replace("app.test.js", "other.test.js") + "," + plain + "]}").contains("[retries not known: vitest's account of its tests does not name"));
        check("vitest retries: a test the JUnit report lists without a mark that vitest did not run (after a bail) is skipped",
                said.apply("{\"tests\":[" + guard.formatted("null", "null").replace("\"pass\"", "null") + "," + plain + "]}").equals("SKIPPED guard; PASSED group > plain"));
        check("vitest retries: one vitest says failed where the report says passed is not taken for a pass at the first try",
                said.apply("{\"tests\":[" + guard.formatted("0", "null").replace("\"pass\"", "\"fail\"") + "," + plain + "]}").contains("[retries not known: vitest's account of its tests "
                        + "and its JUnit report disagree about guard]"));
        // what the reporter wrote is hostile input: anything but exactly its shape is not used, and says why
        for (String bad : new String[] {"{\"tests\":[" + guard.formatted("1.5", "1") + "]}", "{\"tests\":[" + guard.formatted("-1", "1") + "]}", "{\"tests\":{}}", "[]", "{\"tests\":[1]}",
                "{\"tests\":[{\"file\":\"app.test.js\",\"name\":7}]}", "{\"tests\":[], \"tests\":[]}", "{\"tests\":[]} trailing", "not json"}) {
            check("vitest retries: an account that is not of the reporter's shape is not used: " + bad,
                    said.apply(bad).startsWith("PASSED guard; PASSED group > plain [retries not known: vitest's account of its tests cannot be read ("));
        }
        if (!Proc.WINDOWS) {
            Path elsewhere = repo.resolve("elsewhere.json");
            Files.writeString(elsewhere, "{\"tests\":[" + guard.formatted("0", "0") + "," + plain + "]}");
            Files.delete(retries);
            Files.createSymbolicLink(retries, elsewhere);
            check("vitest retries: an account that is a link is not followed", sdlc.check.Runners.vitestReport(xml, retries, "app.test.js").contains("[retries not known: vitest did not write"));
            Files.delete(retries);
            Files.delete(elsewhere);
        }
        // a test that failed every time vitest ran it fails, marked as run again; a skipped one needs no account
        Files.writeString(xml, """
                <testsuites><testsuite name="app.test.js">
                    <testcase classname="app.test.js" name="guard"><failure message="expected 0 to be 1" type="AssertionError">x</failure></testcase>
                    <testcase classname="app.test.js" name="off"><skipped/></testcase>
                </testsuite></testsuites>
                """);
        check("vitest retries: a test that failed every time vitest ran it fails, and a run with no pass needs no account",
                said.apply("{\"tests\":[" + guard.formatted("1", "1").replace("\"pass\"", "\"fail\"") + "]}").equals("FAILED guard (run again); SKIPPED off")
                && sdlc.check.Runners.vitestReport(xml, none, "app.test.js").equals("FAILED guard; SKIPPED off"));
        String reporter = sdlc.check.Runners.vitestReporter();
        check("vitest retries: the check's reporter is a fixed text that writes vitest's own retry count where the check says, and names the tests as the JUnit reporter does",
                reporter.contains("process.env.SDLC_VITEST_RETRIES") && reporter.contains("r.retryCount") && reporter.contains("base + ' > ' + t.name")
                && reporter.contains("onFinished") && reporter.contains("onTestRunEnd") && !reporter.contains("${"));
    }

    /**
     * CR6-1: vitest's JUnit reporter writes a name into an XML attribute, and reading it changes it
     * (a line end, a tab or a line feed becomes a space; what XML 1.0 forbids or discourages is
     * taken out before it is written). The check's account gives the name vitest gave, and is
     * compared under exactly that, so an ordinary title with a line break does not leave the file
     * without an account of its retries.
     */
    private static void vitestNames(Path xml, Path retries, Path none) throws IOException {
        java.util.function.BiFunction<String, String, String> said = (report, json) -> {
            try {
                Files.writeString(xml, "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<testsuites><testsuite name=\"app.test.js\">\n" + report + "</testsuite></testsuites>\n");
                Files.writeString(retries, json);
                return sdlc.check.Runners.vitestReport(xml, retries, "app.test.js");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        String row = "{\"file\":\"app.test.js\",\"name\":\"%s\",\"state\":\"pass\",\"retryCount\":%s,\"retry\":null}";
        // the audit's case, as Vitest 3.2.4 wrote it: test.each(['a\nb'])('handles %s', ...) beside a new test; the name holds a line feed in the attribute
        String multiline = "<testcase classname=\"app.test.js\" name=\"handles a\nb\" time=\"0\">\n</testcase>\n<testcase classname=\"app.test.js\" name=\"feature\" time=\"0\">\n</testcase>\n";
        check("vitest names: a title with a line break (test.each with \\n) is matched to its case, and the file says which passes were at the first try",
                said.apply(multiline, "{\"tests\":[" + row.formatted("handles a\\nb", "0") + "," + row.formatted("feature", "0") + "]}").equals("PASSED handles a b; PASSED feature"));
        check("vitest names: and a test of such a name that vitest ran again is marked so",
                said.apply(multiline, "{\"tests\":[" + row.formatted("handles a\\nb", "1") + "," + row.formatted("feature", "0") + "]}").equals("PASSED handles a b (run again); PASSED feature"));
        // names as vitest gave them, and as its JUnit report gives them once read: what Vitest 3.2.4 wrote, read by the check's parser
        String[][] shapes = {
                {"nl a\nb", "nl a b"},
                {"tab a\tb", "tab a b"},
                {"cr a\rb", "cr a b"},
                {"crlf a\r\nb", "crlf a b"},
                {"spaces a   b", "spaces a   b"},
                {"  lead and trail  ", "  lead and trail  "},
                {"lt <x>", "lt <x>"},
                {"amp a & b", "amp a & b"},
                {"dq \"q\"", "dq \"q\""},
                {"sq 'q'", "sq 'q'"},
                {"bs a\\b", "bs a\\b"},
                {"ansi \u001B[31mred\u001B[0m", "ansi [31mred[0m"},
                {"nul a\u0000b", "nul ab"},
                {"ctl a\u0001\u007F\u0085\u009Fb", "ctl a\u0085b"},
                {"emoji \uD83D\uDE00", "emoji \uD83D\uDE00"},
                {"lone high \uD800 x", "lone high  x"},
                {"lone low a\uDC00b", "lone low b"},
                {"a > b inside", "a > b inside"},
                {"repl \uFFFD x", "repl  x"},
                {"nonchar \uFFFE x", "nonchar  x"},
                {"fdd0 \uFDD0 x", "fdd0  x"},
                {"vt \u000B ff \u000C", "vt  ff "},
                {"ls \u2028 ps \u2029", "ls \u2028 ps \u2029"},
                {"amp entity &amp; &#10;", "amp entity &amp; &#10;"},
                {"plane1 nonchar \uD83F\uDFFE x", "plane1 nonchar  x"},
                {"outer\nnested\t<&> >   inner  \"x\" > leaf 'y'\r\n", "outer nested <&> >   inner  \"x\" > leaf 'y' "},
                {"dup x\ny", "dup x y"},
                {"dup x y", "dup x y"},
                {"each 'n\\tm' > t", "each 'n\\tm' > t"},
                {"each 'o<p' > t", "each 'o<p' > t"},
                {"obj { a: 'b\\nc' } arr 1,x\ty j %j", "obj { a: 'b\\nc' } arr 1,x y j %j"},
                {"dollar 'v\\nw'", "dollar 'v\\nw'"},
                {"ctl-low \u0001\uDC00 x", "ctl-low \uFFFD x"},
                {"pair-low \uD83D\uDE00\uDC00 x", "pair-low \uFFFD x"},
                {"low-low \uDC00\uDC00 x", "low-low\uFFFD x"},
                {"start", "start"},
                {"p16 \uDBFF\uDFFEy", "p16 "},
                {"p16nl \uDBFF\uDFFF\nz", "p16nl \uDBFF\uDFFF z"},
                {"p16 end \uDBFF\uDFFF", "p16 end \uDBFF\uDFFF"},
                {"p16 pair \uDBFF\uDFFE\uD83D\uDE00", "p16 pair "},
                {"p16 high \uDBFF\uDFFE\uD800 q", "p16 high q"},
                {"p16 nl-low \uDBFF\uDFFE\n\uDC00 r", "p16 nl-low r"},
                {"p15 \uDBBF\uDFFE s", "p15  s"},
                {"nel \u0085 x", "nel \u0085 x"},
                {"del \u007F", "del "},
                {"high-high \uD800\uD800\uDC00 t", "high-high \uD800\uDC00 t"},
                {"\uDC00 at start", " at start"},
                {"x\uDBFF\uDFFF\uDFFF", "x\uFFFD"},
                {"e\u0301 combining", "e\u0301 combining"},
                {"\u00A0 nbsp", "\u00A0 nbsp"},
                {"\t\n\r", "   "}
        };
        java.util.List<String> wrong = new java.util.ArrayList<>();
        for (String[] shape : shapes) if (!shape[1].equals(sdlc.check.Runners.vitestReportedName(shape[0]))) wrong.add(shape[0] + " -> " + sdlc.check.Runners.vitestReportedName(shape[0]));
        check("vitest names: each title shape is compared under the change vitest's JUnit reporter and the XML parser make to it " + wrong, wrong.isEmpty());
        // a name vitest writes so that no XML parser can read its report (a lone low surrogate takes a reference's ';' along): no account of it either
        check("vitest names: a name whose report would not be XML has no name in it",
                sdlc.check.Runners.vitestReportedName("amp-low &\uDC00x") == null && sdlc.check.Runners.vitestReportedName("lt-low <\uDC00") == null);
        check("vitest names: and an account that names one is not used",
                said.apply(multiline, "{\"tests\":[" + row.formatted("amp-low &\\udc00x", "0") + "," + row.formatted("feature", "0") + "]}")
                        .endsWith("[retries not known: vitest's account of its tests names a test that its JUnit report cannot write as XML]"));
        // two titles that the report writes alike: the report has two cases of the name, and the name is not told apart (the check says so of the name, see Witness)
        String dup = "<testcase classname=\"app.test.js\" name=\"dup x\ny\"></testcase>\n<testcase classname=\"app.test.js\" name=\"dup x y\"></testcase>\n";
        check("vitest names: two titles the report writes alike are both of the name, and a retry of either marks both",
                said.apply(dup, "{\"tests\":[" + row.formatted("dup x\\ny", "0") + "," + row.formatted("dup x y", "0") + "]}").equals("PASSED dup x y; PASSED dup x y")
                && said.apply(dup, "{\"tests\":[" + row.formatted("dup x\\ny", "1") + "," + row.formatted("dup x y", "0") + "]}").equals("PASSED dup x y (run again); PASSED dup x y (run again)"));
        String one = "<testcase classname=\"app.test.js\" name=\"dup x y\"></testcase>\n";
        check("vitest names: an account with more tests of a name than the report has cases of it does not say which it is",
                said.apply(one, "{\"tests\":[" + row.formatted("dup x\\ny", "0") + "," + row.formatted("dup x y", "0") + "]}")
                        .equals("PASSED dup x y [retries not known: vitest's account of its tests names more tests dup x y than its JUnit report lists]"));
        // a very long title is compared; one longer than the check reads makes the account unusable, never a wrong match
        String longName = "L".repeat(100_000);
        String longCase = "<testcase classname=\"app.test.js\" name=\"" + longName + "\"></testcase>\n";
        check("vitest names: a title of 100000 characters is matched",
                said.apply(longCase, "{\"tests\":[" + row.formatted(longName, "1") + "]}").equals("PASSED " + longName + " (run again)"));
        String tooLong = "L".repeat(1_000_001);
        check("vitest names: a longer one than the check compares makes the account unusable, with why",
                said.apply(longCase, "{\"tests\":[" + row.formatted(tooLong, "0") + "]}").endsWith("[retries not known: vitest's account of its tests names a test longer than 1000000 characters]"));
        // any code units at all: the transformation never throws, and what it gives an XML parser could have read
        java.util.Random random = new java.util.Random(61);
        char[] alphabet = {'a', ' ', '&', '<', '>', '"', '\'', ';', '\n', '\r', '\t', '\u0000', '\u0001', '\u001b', '\u007f', '\u0085', '\u009f', '\ufdd0', '\ufffd', '\ufffe', '\uffff',
                '\ud800', '\udbff', '\udbbf', '\udc00', '\udfff', '\udffe', '\u2028', 'x'};
        boolean steady = true;
        for (int i = 0; i < 3000 && steady; i++) {
            StringBuilder b = new StringBuilder();
            for (int k = random.nextInt(24); k > 0; k--) b.append(alphabet[random.nextInt(alphabet.length)]);
            try {
                String got = sdlc.check.Runners.vitestReportedName(b.toString());
                if (got != null) for (int k = 0; k < got.length(); k++) {
                    char c = got.charAt(k);
                    boolean pair = Character.isHighSurrogate(c) && k + 1 < got.length() && Character.isLowSurrogate(got.charAt(k + 1));
                    if (pair) k++;
                    else if (Character.isSurrogate(c) || (c < 0x20) || c == 0xFFFE || c == 0xFFFF) steady = false;
                }
            } catch (RuntimeException e) {
                steady = false;
            }
        }
        check("vitest names: any code units give a name without a lone surrogate or a character XML forbids, or none, and never an error", steady);
        // a backslash is a folder's separator only on Windows: elsewhere it is part of a name, and another file is not taken for this one
        Files.writeString(xml, "<testsuites><testsuite name=\"dir\\app.test.js\"><testcase classname=\"dir\\app.test.js\" name=\"t\"/></testsuite></testsuites>");
        String other = sdlc.check.Runners.vitestReport(xml, none, "dir/app.test.js");
        check("vitest names: a report of dir\\app.test.js is of dir/app.test.js on Windows only", Proc.WINDOWS ? other.startsWith("PASSED t") : other.startsWith("no result"));
    }

    /**
     * CR6-3: the check's reporter itself, run under node against task objects of the shape Vitest
     * 3.2.4 hands it (a file, nested groups, retries, a skipped and a to-do test, a test without a
     * result after a bail, names with line breaks and the characters XML changes), through both of
     * its entry points. What it writes is read beside the JUnit report Vitest 3.2.4 wrote for the
     * same tests. Where node is not on the PATH this is said in one line and not counted.
     */
    private static void vitestReporterRuns(Path repo) throws IOException {
        if (Proc.run(repo, "node", "--version").code() == 127) {
            System.out.println("(the check's reporter for vitest was not run: node is not on the PATH)");
            return;
        }
        Path dir = Files.createTempDirectory("sdlc-reporter");
        try {
            Files.writeString(dir.resolve("retries.mjs"), sdlc.check.Runners.vitestReporter());
            Files.writeString(dir.resolve("drive.mjs"), """
                    // the reporter as vitest calls it: onInit, then onFinished (vitest 3) or onTestRunEnd (later versions; vitest 3.2 calls both)
                    import Reporter from './retries.mjs';
                    import { join } from 'node:path';
                    const root = process.cwd();
                    const test = (name, mode, retry, result) => ({ type: 'test', name, mode, retry, result });
                    const suite = (name, tasks) => ({ type: 'suite', name, mode: 'run', result: { state: 'pass' }, tasks });
                    const file = { type: 'suite', name: 'app.test.js', filepath: join(root, 'app.test.js'), mode: 'run', result: { state: 'fail' }, tasks: [
                      test('guard', 'run', 2, { state: 'pass', retryCount: 1 }),
                      suite('group', [suite('inner\\nline', [test('plain\\ttab', 'run', undefined, { state: 'pass', retryCount: 0 })])]),
                      test('off', 'skip', undefined, undefined),
                      test('later', 'todo', undefined, undefined),
                      test('broken', 'run', 1, { state: 'fail', retryCount: 1 }),
                      test('<&>"\\' \\x1b[1m x\\0y \\uD800 a\\uDC00b \\u{1F600} \\u{10FFFE}z \\r\\n', 'run', undefined, { state: 'pass', retryCount: 0 }),
                      test('handles a\\nb', 'run', undefined, { state: 'pass', retryCount: 0 }),
                      test('bailed', 'run', undefined, undefined),
                    ] };
                    const other = { type: 'suite', name: 'dir/b.test.js', filepath: join(root, 'dir', 'b.test.js'), tasks: [test('b', 'run', 3, { state: 'pass', retryCount: 2 })] };
                    const r = new Reporter();
                    r.onInit({ config: { root } });
                    if (process.argv[2] === 'finished') r.onFinished([file, other]); else r.onTestRunEnd([{ task: file }, { task: other }]);
                    """);
            // what Vitest 3.2.4 wrote for these tests (its failure's text shortened); the name of a case holds a line feed, a tab, a CR LF
            Path xml = dir.resolve("report.xml");
            Files.writeString(xml, "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<testsuites name=\"vitest tests\" tests=\"8\" failures=\"1\" errors=\"0\" time=\"0.008\">\n"
                    + "<testsuite name=\"app.test.js\" tests=\"8\" failures=\"1\" errors=\"0\" skipped=\"2\" time=\"0.008\">\n"
                    + "<testcase classname=\"app.test.js\" name=\"guard\" time=\"0.006\">\n</testcase>\n"
                    + "<testcase classname=\"app.test.js\" name=\"group &gt; inner\nline &gt; plain\ttab\" time=\"0.0001\">\n</testcase>\n"
                    + "<testcase classname=\"app.test.js\" name=\"off\" time=\"0\">\n<skipped/>\n</testcase>\n"
                    + "<testcase classname=\"app.test.js\" name=\"later\" time=\"0\">\n<skipped/>\n</testcase>\n"
                    + "<testcase classname=\"app.test.js\" name=\"broken\" time=\"0.0009\">\n<failure message=\"expected 1 to be 2 // Object.is equality\" type=\"AssertionError\">\nAssertionError\n</failure>\n</testcase>\n"
                    + "<testcase classname=\"app.test.js\" name=\"&lt;&amp;&gt;&quot;&apos; [1m xy  b \uD83D\uDE00  \r\n\" time=\"0.0001\">\n</testcase>\n"
                    + "<testcase classname=\"app.test.js\" name=\"handles a\nb\" time=\"0.0001\">\n</testcase>\n"
                    + "<testcase classname=\"app.test.js\" name=\"bailed\" time=\"0\">\n</testcase>\n"
                    + "</testsuite>\n<testsuite name=\"dir/b.test.js\" tests=\"1\"><testcase classname=\"dir/b.test.js\" name=\"b\"/></testsuite>\n</testsuites>\n");
            String want = "PASSED guard (run again); PASSED group > inner line > plain tab; SKIPPED off; SKIPPED later; FAILED broken (run again); PASSED <&>\"' [1m xy  b \uD83D\uDE00   ; "
                    + "PASSED handles a b; SKIPPED bailed";
            String[] written = new String[2];
            for (int i = 0; i < 2; i++) {
                Path json = dir.resolve("retries-" + i + ".json");
                Proc p = Proc.runWithEnv(dir, Map.of("SDLC_VITEST_RETRIES", json.toString()), null, 60, "node", "drive.mjs", i == 0 ? "finished" : "run-end");
                String got = sdlc.check.Runners.vitestReport(xml, json, "app.test.js");
                boolean ok = p.ok() && got.equals(want) && sdlc.check.Runners.vitestReport(xml, json, "dir/b.test.js").equals("PASSED b (run again)");
                check("vitest reporter, run under node (" + (i == 0 ? "onFinished" : "onTestRunEnd") + "): every test is named as the JUnit report gives it, with vitest's own retries", ok);
                if (!ok) System.out.println("    node exit " + p.code() + " " + (p.out() + p.err()).strip() + "\n    got: " + got);
                written[i] = Files.isRegularFile(json) ? Files.readString(json) : "";
            }
            check("vitest reporter: both of its entry points write the same account", !written[0].isEmpty() && written[0].equals(written[1]));
        } finally {
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
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
        write(repo, "checks/hook.t", HOOK);
        write(repo, "checks/grow.t", GROW);
        write(repo, "checks/shy.t", SHY);
        write(repo, "checks/named.t", NAMED);
        write(repo, "checks/off.t", OFF);
        write(repo, "checks/failing.t", FAILING);
        write(repo, "checks/ignored.t", IGNORED);
        write(repo, "checks/plain.t", PLAIN);
        write(repo, "checks/flip.t", FLIP);
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
        write(repo, "checks/rows.t", ROWS);
        write(repo, "checks/varies.t", VARIES);
        write(repo, "checks/retry.t", RETRY);
        write(repo, "checks/spent.t", SPENT);
        write(repo, "checks/kept.t", KEPT);
        write(repo, "checks/fresh.t", FRESH);
        write(repo, "checks/inv.t", INV);
        write(repo, "checks/wob.t", WOB);
        write(repo, "checks/code.t", CODE);
        write(repo, "checks/guarded.t", GUARDED);
        write(repo, "checks/r4.t", R4);
        write(repo, "checks/cut.t", CUT);
        write(repo, "checks/cut2.t", CUT.replace("cut-later", "cut-once"));
        write(repo, "checks/pa.t", PA);
        write(repo, "checks/pb.t", PB);
        // test files for the moves (see moves): named like test files, so that the text check calls a deleted one a problem
        write(repo, "tests/jvm/a/CalcTest.java", CALC);
        write(repo, "tests/a/move.test.t", MOVE);
        write(repo, "tests/a/twin.test.t", MOVE);
        write(repo, "tests/a/fix.test.t", FIX);
        write(repo, "tests/a/odd.test.t", ODD);
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
        // a switch-off and a removal, each in a file of its own: a file whose skips changed cannot say that tests were removed (see unlisted)
        write(repo, "checks/judged.t", JUDGED.replace("turned off: pass", "turned off: skip"));
        write(repo, "checks/named.t", NAMED.replace("goes: pass\n", ""));
        commit(repo, "a test switched off and one removed");
        write(repo, "checks/quiet.t", "asleep: skip\n");
        commit(repo, "a skipped test");
        write(repo, "checks/quiet.t", "asleep: skip\n  still later\n");
        commit(repo, "an edit of a skipped test");
        String later = MENDED.replace("holds: pass", "holds: fails-with later");
        write(repo, "checks/broken.t", later);
        commit(repo, "a test that a later word breaks");
        write(repo, "prod.txt", "v1\nfixed\nmore\nlater\n");
        String keptUp = later.replace("holds: fails-with later", "kept up: pass");
        write(repo, "checks/broken.t", keptUp);
        commit(repo, "a test broken and taken out of the way");
        // and each counted verdict of a file with a least number above one
        write(repo, "tests/old.t", "kept: pass\nsensitive: needs fixed\nidle: pass\nidle too: pass\nidle three: pass\n");
        commit(repo, "two new tests that prove nothing");
        write(repo, "checks/judged.t", JUDGED.replace("turned off: pass", "turned off: skip").replace("stays: pass", "stays: skip").replace("goes: pass", "goes: skip"));
        write(repo, "checks/named.t", "adds %i: pass\nrejectsNull(String)[%i]: pass\nrefund: pass\n");
        commit(repo, "two tests switched off and two removed");
        String latest = keptUp.replace("fragile: pass", "fragile: fails-with latest").replace("steady: pass", "steady: fails-with latest").replace("wobbly: pass", "wobbly: fails-with latest");
        write(repo, "checks/broken.t", latest);
        commit(repo, "tests that a latest word breaks");
        write(repo, "prod.txt", "v1\nfixed\nmore\nlater\nlatest\n");
        write(repo, "checks/broken.t", latest.replace("fragile: fails-with latest", "fragile anew: pass").replace("steady: fails-with latest", "steady anew: pass")
                .replace("wobbly: fails-with latest", "wobbly anew: needs latest"));
        commit(repo, "three tests broken, one rewritten and two taken out of the way");
        String history = report(Map.of("selftest-runner", "true", "runs", "1", "since", "1d"));
        check("check: --since counts each verdict of a file by the tests it proves, not by its lines",
                history.contains("two new tests that prove nothing\n    tests: at least 2 new, pass on both sides\n")
                && history.contains("two tests switched off and two removed\n    tests: at least 4 ran before, do not run after\n")
                && history.contains("three tests broken, one rewritten and two taken out of the way\n    tests: 1 fail before and pass after; at least 2 passed before, fail as they were "
                        + "after, taken out; ")
                && history.contains("\n  at least 3 new tests pass on both sides, so do not test their commit\n")
                && history.contains("\n  at least 6 tests ran before their commit and do not run after it\n")
                && history.contains("\n  at least 3 tests passed before their commit, fail on it as they were, and were taken out of the way\n"));
        check("check: --since names a test its commit broke and took out of the way",
                history.contains("a test broken and taken out of the way\n    tests: 1 passed before, fail as they were after, taken out; 1 could not be judged\n"
                        + "        passed before, fails as it was after, taken out: checks/broken.t  holds\n"));
        check("check: --since says of a commit whose tests were only skipped before and after that they were",
                history.contains("an edit of a skipped test\n    tests: only tests that were already there, skipped as before\n"));
        check("check: --since replays each recent commit", history.contains("commits on this branch since 1 days ago") && history.contains("  the fix")
                && history.contains("tests: 1 fail before and pass after") && history.contains("does not test the change: tests/old.t  the file reports 1 test it did not have: at least 1 "
                        + "new test passes before and after. Under names the file did not have, 1 passes before and after (idle)")
                && code == 0);
        check("check: --since names the files of a commit whose tests that ran do not run after it", history.contains("a test switched off and one removed\n    tests: at least 2 ran "
                + "before, do not run after\n")
                && history.contains("ran before, do not run after: checks/judged.t  the file as it was passed 4 of its tests, and after the change it runs 3: at least 1 test that ran "
                        + "before the change does not run now (skipped now under a name that ran before: turned off)")
                && history.contains("ran before, do not run after: checks/named.t  the file as it was passed 8 of its tests, and after the change it runs 7: at least 1 test that ran "
                        + "before the change does not run now (no longer reported: goes)"));
        check("check: --since wants a time span", Asserts.throwsUsage(() -> Check.run(Map.of("since", "true"))));
        git(repo, "reset", "-q", "--hard", "HEAD~11");
        write(repo, "prod.txt", "v1\nfixed\nmore\n");
        branch = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: commits on the branch and uncommitted edits are one change", branch.contains("2 files changed against main") && branch.contains("1 fails before the change and passes after it"));
    }

    /** A JVM test class of the base (see SelfTestRunner): one test that passes, one that runs only with real.Calc imported, one that fails with other.Calc imported. */
    private static final String CALC = "package a;\n\nimport real.Calc;\n\nadds: pass\nrounds: skip-unless real.Calc\nguards: fails-with other.Calc\n";
    /** A script test file of the base, and a copy of it. */
    private static final String MOVE = "kept: pass\nalso: pass\n";
    /** A test file of the base whose first test fails there: the change that writes "mended" into the code makes it pass. */
    private static final String FIX = "repairs: needs mended\nkept: pass\n";
    /** As FIX, with a test skipped in every other run on the code that holds "mended". */
    private static final String ODD = "repairs: needs mended\nsways: skip-odd mended\n";

    /**
     * A test file that is only moved is a deleted file and an added one. Where the text check's
     * rule says it moved (Integrity.moves) and the runner that runs it now ran it where it was, its
     * tests are the tests it had: none is new, and the file as it was is compared with it by
     * counts only, as its names changed with the move (a JUnit class name holds its package, a
     * Vitest suite is its path). Comes in with the fix and a sensitive test in tests/old.t.
     */
    private static void moves(Path repo) throws IOException {
        String sensitiveOld = "  1 fails before the change and passes after it\n      tests/old.t  [self test]\n        sensitive\n";
        // a JVM class moved to another package, its package line alone changed
        Files.delete(repo.resolve("tests/jvm/a/CalcTest.java"));
        write(repo, "tests/jvm/b/CalcTest.java", CALC.replace("package a;", "package b;"));
        String repackaged = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("moves: a JVM class moved to another package is a moved file: its tests are the tests it had, none new, nothing lost, nothing that could not be judged, exit 0",
                code == 0 && repackaged.contains(sensitiveOld + "  1 test file moved (not counted)\n      tests/jvm/b/CalcTest.java  [self test]\n        "
                        + movedLine("tests/jvm/a/CalcTest.java", "its lines are the same but for its package line") + "\n"
                        + "  4 tests that were already in the edited or moved files pass before and after (not counted)\n")
                && repackaged.contains("note     test file moved: tests/jvm/a/CalcTest.java  the same file is now at tests/jvm/b/CalcTest.java, in another package") && clean(repackaged));
        // the same with its imports changed, every test still running and passing
        write(repo, "tests/jvm/b/CalcTest.java", CALC.replace("package a;", "package b;").replace("import real.Calc;", "import real.Calc;\nimport real.Extra;"));
        String reimported = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("moves: moved with its imports changed, every test still running and passing: a moved file, whose line says what no run shows, exit 0",
                code == 0 && reimported.contains("  1 test file moved (not counted)\n      tests/jvm/b/CalcTest.java  [self test]\n        "
                        + movedLine("tests/jvm/a/CalcTest.java", "its lines are the same but for its package and import lines")
                        + ". Its imports changed: an import may change what a test tests while it passes, which no run shows\n")
                && reimported.contains("  4 tests that were already in the edited or moved files pass before and after (not counted)\n")
                && reimported.contains("note     test file moved and its imports changed: tests/jvm/a/CalcTest.java  the same tests are now at tests/jvm/b/CalcTest.java, with other "
                        + "imports: its run judges it") && clean(reimported));
        // an import swapped, under which a test is skipped: fewer tests run
        write(repo, "tests/jvm/b/CalcTest.java", CALC.replace("package a;", "package b;").replace("import real.Calc;", "import lite.Calc;"));
        String fewer = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("moves: a move whose import skips a test runs fewer tests than the file as it was passed: said by the counts, definite, and the check fails",
                code == 1 && fewer.contains(L1 + "      tests/jvm/b/CalcTest.java  [self test]\n        the file as it was passed 3 of its tests, and after the change it runs 2: at "
                        + "least 1 test that ran before the change does not run now. The file moved from tests/jvm/a/CalcTest.java and is not run as it was on the code after the change, "
                        + "so whether the move or the change of the code took them is not known; either way they ran before the change and do not run now\n")
                && fewer.contains("  1 is skipped\n      tests/jvm/b/CalcTest.java  [self test]\n        rounds\n") && fewer.contains("  1 test file moved (not counted)\n")
                && !fewer.contains("could not be judged") && !fewer.contains("new test pass"));
        // an import added, under which a test fails
        write(repo, "tests/jvm/b/CalcTest.java", CALC.replace("package a;", "package b;").replace("import real.Calc;", "import real.Calc;\nimport other.Calc;"));
        String failing = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("moves: a move whose import makes a test fail fails the check, as any failure after the change",
                code == 1 && failing.contains("  1 fails after the change\n      tests/jvm/b/CalcTest.java  [self test]\n        guards: fails: expected something else\n")
                && failing.contains("  1 test file moved (not counted)\n") && !failing.contains("could not be judged") && !failing.contains("new test pass") && !lostSaid(failing));
        // a test file deleted and another added with other lines: no move. The deletion is a problem and the new test is judged as new
        Files.delete(repo.resolve("tests/jvm/b/CalcTest.java"));
        write(repo, "tests/jvm/b/OtherTest.java", "package b;\n\nidle: pass\n");
        String unrelated = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("moves: a test file deleted and an unrelated one added is no move: the deletion is a problem and the new test does not test the change",
                code == 1 && !unrelated.contains("moved") && unrelated.contains("  1 new test passes before and after, so it does not test the change\n"
                        + "      tests/jvm/b/OtherTest.java  [self test]\n        idle\n")
                && unrelated.contains("PROBLEM  test file deleted: tests/jvm/a/CalcTest.java  the file is gone"));
        Files.delete(repo.resolve("tests/jvm/b/OtherTest.java"));
        write(repo, "tests/jvm/a/CalcTest.java", CALC);

        // a script test file moved to another folder, its lines the same: its suite is its path, so every name changed
        Files.delete(repo.resolve("tests/a/move.test.t"));
        write(repo, "tests/b/move.test.t", MOVE);
        String script = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("moves: a script test file moved to another folder with the same lines is a moved file, exit 0",
                code == 0 && script.contains(sensitiveOld + "  1 test file moved (not counted)\n      tests/b/move.test.t  [self test]\n        "
                        + movedLine("tests/a/move.test.t", "its lines are the same") + "\n  3 tests that were already in the edited or moved files pass before and after (not counted)\n")
                && script.contains("note     test file moved: tests/a/move.test.t  the same file is now at tests/b/move.test.t") && clean(script));
        // ordinary work stays quiet: a pure move, in three runs, the reports in another order from run to run
        for (int order = 0; order <= 2; order++) {
            Check.order(order);
            String calm = report(Map.of("selftest-runner", "true", "runs", "3"));
            Check.order(0);
            check("moves: a pure move prints nothing that could not be judged and nothing unstable, in three runs" + (order == 0 ? "" : ", in report order " + order),
                    code == 0 && calm.contains("  1 test file moved (not counted)\n") && clean(calm) && !calm.contains("from run to run"));
        }
        // moved and edited on the way: a deleted file and an added one, as before this rule
        write(repo, "tests/b/move.test.t", MOVE.replace("also: pass", "also changed: pass"));
        String edited = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("moves: a file moved and edited on the way is a deleted test file (a problem) and an added one whose tests are new",
                code == 1 && !edited.contains("moved") && edited.contains("  2 new tests pass before and after, so they do not test the change\n      tests/b/move.test.t  [self test]\n"
                        + "        also changed\n        kept\n") && edited.contains("PROBLEM  test file deleted: tests/a/move.test.t  the file is gone"));
        // two deleted copies, one added: one moved, the other is deleted
        write(repo, "tests/b/move.test.t", MOVE);
        Files.delete(repo.resolve("tests/a/twin.test.t"));
        String twoGone = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("moves: of two deleted copies and one added, the first by path moved and the other is a test file deleted",
                code == 1 && twoGone.contains("      tests/b/move.test.t  [self test]\n        " + movedLine("tests/a/move.test.t", "its lines are the same") + "\n")
                && twoGone.contains("PROBLEM  test file deleted: tests/a/twin.test.t  the file is gone") && !twoGone.contains("moved from tests/a/twin.test.t"));
        write(repo, "tests/a/twin.test.t", MOVE);
        // one deleted, two added copies: one moved, the other is new
        write(repo, "tests/c/move.test.t", MOVE);
        String twoAdded = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("moves: of one deleted file and two added copies, the first by path is the moved file and the other's tests are new",
                code == 1 && twoAdded.contains("  2 new tests pass before and after, so they do not test the change\n      tests/c/move.test.t  [self test]\n        also\n        kept\n")
                && twoAdded.contains("  1 test file moved (not counted)\n      tests/b/move.test.t  [self test]\n") && !twoAdded.contains("PROBLEM"));
        Files.delete(repo.resolve("tests/c/move.test.t"));
        // moved to where its runner does not run it: no move for the run (the text check's rule calls it one; see the guide's limits)
        Files.delete(repo.resolve("tests/b/move.test.t"));
        write(repo, "tests/b/move.test.txt", MOVE);
        String away = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("moves: a test file renamed to another kind of file has not moved: its deletion is a problem",
                code == 1 && !away.contains("test file moved") && away.contains("PROBLEM  test file deleted: tests/a/move.test.t"));
        Files.delete(repo.resolve("tests/b/move.test.txt"));
        // moved, as a file of the same kind, to where no runner runs it: the tests it ran do not run now
        Files.delete(repo.resolve("tests/jvm/a/CalcTest.java"));
        write(repo, "attic/CalcTest.java", CALC);
        String attic = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("moves: a test file moved to where no runner runs it fails: its tests ran before the change and do not run now",
                code == 1 && attic.contains("moved to attic/CalcTest.java, where no runner of the check runs it") && attic.contains("not run now"));
        Files.delete(repo.resolve("attic/CalcTest.java"));
        write(repo, "tests/jvm/a/CalcTest.java", CALC);
        write(repo, "tests/a/move.test.t", MOVE);

        // moved, and the code changed so that a test of it that failed before passes now: sensitive
        write(repo, "prod.txt", "v1\nfixed\nmended\n");
        Files.delete(repo.resolve("tests/a/fix.test.t"));
        write(repo, "tests/b/fix.test.t", FIX);
        String sensitive = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("moves: a moved file's test that fails before the change and passes after it is sensitive",
                code == 0 && sensitive.contains("  2 fail before the change and pass after it\n      tests/b/fix.test.t  [self test]\n        repairs\n      tests/old.t  [self test]\n"
                        + "        sensitive\n") && sensitive.contains("  1 test file moved (not counted)\n") && clean(sensitive));
        Files.delete(repo.resolve("tests/b/fix.test.t"));
        write(repo, "tests/a/fix.test.t", FIX);
        // a moved file whose runs after the change differ from run to run: said
        Files.delete(repo.resolve("tests/a/odd.test.t"));
        write(repo, "tests/b/odd.test.t", ODD);
        String odd = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("moves: a moved file reported otherwise from run to run after the change is said to be unstable",
                code == 0 && odd.contains("  1 gave different results from run to run\n      tests/b/odd.test.t  [self test]\n        the file was reported differently from run to run, "
                        + "in ways no rule of the check judged: sways (after the change: skipped, passed)\n")
                && odd.contains("      tests/b/odd.test.t  [self test]\n        repairs\n") && !odd.contains("could not be judged"));
        Files.delete(repo.resolve("tests/b/odd.test.t"));
        write(repo, "tests/a/odd.test.t", ODD);
        write(repo, "prod.txt", "v1\nfixed\n");
    }

    /** The line of a moved file (see Witness.moved). */
    private static String movedLine(String from, String how) {
        return "moved from " + from + " (" + how + "): its tests are the tests it had, none of them new. Their names changed with the move, so the file as it was is compared with it "
                + "by how many of its tests run, not by their names";
    }

    /** True when a report says nothing uncertain, of new tests, or of tests that do not run now. */
    private static boolean clean(String report) {
        return !report.contains("could not be judged") && !report.contains("new test pass") && !report.contains("new tests pass") && !lostSaid(report) && !report.contains("removed");
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
        check("check: a test that ran before the change and is skipped now is a test that ran and does not run now",
                off.contains("at least 1 test that ran before the change does not run now\n      checks/judged.t  [self test]\n        the file as it was passed 4 of its tests, and after "
                        + "the change it runs 3: at least 1 test that ran before the change does not run now (skipped now under a name that ran before: turned off)\n") && code == 1);
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
                    && reasons.toString(StandardCharsets.UTF_8).contains("tests that ran before your change do not run now: checks/judged.t (the file as it was passed 4 of its tests, "
                            + "and after the change it runs 3: at least 1 test that ran before the change does not run now (skipped now under a name that ran before: turned off)"));
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

        // one gone and none new: a test that ran does not run now, said of the file with the name it no longer reports
        write(repo, "checks/judged.t", JUDGED.replace("goes too: pass\n", ""));
        String one = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test the file had, where it reports no new one, ran and does not run now",
                one.contains("at least 1 test that ran before the change does not run now\n      checks/judged.t  [self test]\n        the file as it was passed 4 of its tests, and after "
                        + "the change it runs 3: at least 1 test that ran before the change does not run now (no longer reported: goes too)\n") && code == 1);
        // a skipped case that only a repeat run reports: what runs is the same in every run, so the test deleted does not run in any of them
        write(repo, "checks/judged.t", JUDGED.replace("goes too: pass\n", "") + "late: skip-later\n");
        String recounted = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a file that reports another skipped case in a repeat run still runs a test fewer in every run: said, not unstable",
                recounted.contains("at least 1 test that ran before the change does not run now\n      checks/judged.t  [self test]\n        the file as it was passed 4 of its tests, and "
                        + "after the change it runs 3: at least 1 test that ran before the change does not run now (no longer reported: goes too)\n")
                && !recounted.contains("from run to run") && code == 1);
        // the same where the counts say new tests pass on both sides: they are made again too, and must say it in every run
        write(repo, "checks/judged.t", JUDGED + "idle: pass\nlate: skip-later\n");
        String renumbered = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a file whose counts say a new test passes on both sides, and that reports another number of tests in a repeat run, is unstable",
                renumbered.contains("1 gave different results from run to run\n      checks/judged.t  [self test]\n        the file reports 1 test it did not have: at least 1 new test "
                        + "passes before and after, but what the file reports, after the change or as it was, is not the same in every run\n")
                && !renumbered.contains("so it does not test the change") && code == 0);
        // a test removed, said in every run, and a test of the file skipped in the repeat run only: that its skips differ from run to run is said too
        write(repo, "checks/judged.t", JUDGED.replace("goes too: pass\n", "").replace("stays: pass", "stays: pass-once"));
        String skippedLater = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a file that runs a test fewer in every run and two fewer in one: the least is said, with the most, in one line",
                skippedLater.contains("at least 1 test that ran before the change does not run now\n      checks/judged.t  [self test]\n        the file as it was passed 4 of its tests, and "
                        + "after the change it runs 3 (of every run as it was the one that passed the fewest, and of every run after the change the one that runs the most): at least 1 "
                        + "test that ran before the change does not run now (no longer reported: goes too), and up to 2 in some runs: what the file reports is not the same in every run\n")
                && !skippedLater.contains("from run to run") && code == 1);
        write(repo, "checks/judged.t", JUDGED.replace("goes too: pass\n", ""));
        reasons.reset();
        try {
            System.setErr(new PrintStream(reasons, true, StandardCharsets.UTF_8));
            System.setIn(new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
            int blocked = Check.run(Map.of("selftest-runner", "true", "stop-hook", "true"));
            check("check: the stop hook blocks on a test removed", blocked == 2
                    && reasons.toString(StandardCharsets.UTF_8).contains("tests that ran before your change do not run now: checks/judged.t (the file as it was passed 4 of its tests, "
                            + "and after the change it runs 3: at least 1 test that ran before the change does not run now (no longer reported: goes too)"));
        } finally {
            System.setIn(in);
            System.setErr(err);
        }

        // two gone and one new in their place: the file lost a test, and which one cannot be told
        write(repo, "checks/judged.t", "stays: pass\nturned off: pass\nwas off: skip\nidle: pass\n");
        String fewer = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a file that runs fewer tests than passed as it was lost tests that ran, with what the reports show",
                fewer.contains("at least 1 test that ran before the change does not run now\n      checks/judged.t  [self test]\n        the file as it was passed 4 of its tests, and after "
                        + "the change it runs 3: at least 1 test that ran before the change does not run now (no longer reported: goes, goes too; reported for the first time: idle)\n")
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
                    && reasons.toString(StandardCharsets.UTF_8).contains("tests that ran before your change do not run now: checks/judged.t (the file as it was passed 4 of its tests"));
        } finally {
            System.setIn(in);
            System.setErr(err);
        }

        // as many new as gone, but one that ran is skipped under its new name: fewer run than before
        write(repo, "checks/judged.t", JUDGED.replace("goes: pass", "went: skip"));
        String renamedOff = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a gone test that ran, where the new one is skipped, is a test that no longer runs",
                renamedOff.contains("at least 1 test that ran before the change does not run now\n      checks/judged.t  [self test]\n        the file as it was passed 4 of its tests, and "
                        + "after the change it runs 3: at least 1 test that ran before the change does not run now (no longer reported: goes; skipped under a name the file did not report "
                        + "before: went)\n") && !renamedOff.contains("The reports cannot tell") && code == 1);

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
                && nested.contains("        the file no longer reports 3 tests under the names it had (demo.Outer.top, demo.Outer$A.works, demo.Outer$B.works), and reports 3 under names it did not have "
                        + "(demo.Renamed.top, demo.Renamed$A.works, demo.Renamed$B.works). The reports cannot tell a test renamed from a test deleted and another written\n")
                && nested.contains("        demo.Renamed$A.works: passes before and after, but it cannot be told whether it is a new test: the file reports 3 tests it did not have and no longer "
                        + "reports 3 it had. It may be a new test that does not test the change, or a test the file had: one of the 3 tests it no longer reports, under a new name\n")
                && nested.contains("4 could not be judged") && !nested.contains("new test pass"));
        write(repo, "checks/nested.t", NESTED);

        renamedOrNot(repo);
        switchedOff(repo);
        oldTestsOnTheChange(repo);
        unlisted(repo);
        lost(repo);
        everyDifference(repo);
        retriesAndCompanions(repo);
        moves(repo);

        // a test that went to another changed file: each file is judged by its own counts, and a title is not read to pair them
        write(repo, "checks/judged.t", JUDGED.replace("goes too: pass\n", ""));
        write(repo, "checks/moved.t", "goes too: needs fixed\n");
        String moved = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test now in another changed file does not run in its own, and is judged as a new test in the other", code == 1 && !moved.contains("moved to another")
                && moved.contains(L1 + "      checks/judged.t  [self test]\n        the file as it was passed 4 of its tests, and after the change it runs 3: at least 1 test that ran "
                        + "before the change does not run now (no longer reported: goes too)")
                && moved.contains("      checks/moved.t  [self test]\n        goes too\n"));
        Files.delete(repo.resolve("checks/moved.t"));
        write(repo, "checks/judged.t", JUDGED);

        // the file as it was gives no result (its slow test is cut off): whether a test is new cannot be told
        write(repo, "checks/legacy.t", "held: pass\nadded: pass\n");
        String legacy = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: when the file as it was cannot be run, a test that passes on both sides could not be judged",
                legacy.contains("added: passes before and after, but run as it was before the change, the file timed out after 900 s, so it cannot be told whether this test is new")
                && legacy.contains("held: passes before and after, but run as it was before the change")
                && legacy.contains("checks/legacy.t  [self test]\n        added: ") && !legacy.contains("so it does not test the change") && code == 0);
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
                && renamed.contains("        the file no longer reports 6 tests under the names it had (adds 1, adds 2, has a name, it's a name, rejectsNull(String)[1], rejectsNull(String)[2]), "
                        + "and reports 6 under names it did not have (has a title, it's a title, refusesNull(String)[1], refusesNull(String)[2], sums 1, sums 2). "
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
                        + "and no longer reports 1 it had: at least 1 new test passes before and after. Under names the file did not have, 2 pass before and after (extra, went)")
                && beside.contains("(goes), and reports 2 under names it did not have (extra, went). The reports cannot tell") && !beside.contains("removed") && code == 1);

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
                        + "at least 3 new tests pass before and after. Under names the file did not have, 3 pass before and after (rejectsBlank(String)[1], rejectsBlank(String)[2], "
                        + "rejectsNull(String)[3]); the names do not say which tests are new") && code == 1);
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
        check("check: a row taken out of a parameterized test is a test that ran and does not run now, said of the file with the name the file no longer reports",
                fewerRows.contains("at least 1 test that ran before the change does not run now\n      checks/named.t  [self test]\n        the file as it was passed 8 of its tests, and "
                        + "after the change it runs 7: at least 1 test that ran before the change does not run now (no longer reported: rejectsNull(String)[2])\n")
                && !fewerRows.contains("        rejectsNull(String)[2]\n") && code == 1);
        write(repo, "checks/named.t", NAMED);

        // every test of the file removed: the file runs and reports none. Whether they were deleted, or hidden by a set-up that stops on an assumption,
        // they ran and do not run now: all of them, said
        write(repo, "checks/named.t", "  nothing left\n");
        String emptied = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a file that reports no test after the change, where its tests passed, lost every one of them",
                emptied.contains("at least 8 tests that ran before the change do not run now\n      checks/named.t  [self test]\n        the file as it was passed 8 of its tests, and after "
                        + "the change it runs 0: at least 8 tests that ran before the change do not run now (no longer reported: adds 1, adds 2, goes, has a name, it's a name, refund, "
                        + "rejectsNull(String)[1], rejectsNull(String)[2])\n") && !emptied.contains("could not be judged") && code == 1);
        write(repo, "checks/named.t", NAMED);
        // a new file with no test: nothing to judge, and the report says so
        write(repo, "checks/empty.t", "  nothing yet\n");
        String none = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a new file with no test is said to report none", none.contains("checks/empty.t  [self test]\n        the file ran after the change and reports no tests\n") && code == 0);
        Files.delete(repo.resolve("checks/empty.t"));
    }

    /** The heading of tests that ran before the change and do not run now, with a least number of one. */
    private static final String L1 = "at least 1 test that ran before the change does not run now\n";

    /** A test file of the base for "ran before the change, skipped now": tests that passed, a parameterized one, two of one name. */
    private static final String OFF = "target: pass\nparams(int)[1]: pass\nparams(int)[2]: skip\ntwin: skip\ntwin: pass\n";

    /** A test file of the base whose report holds failures: a test that fails and one that throws on the base's code, beside OFF's. */
    private static final String FAILING = "failing: needs fixed\nthrowing: error-unless fixed\n" + OFF;

    /** Which skipped tests the change switched off, and which it did not. */
    private static void switchedOff(Path repo) throws IOException {
        write(repo, "checks/judged.t", JUDGED);
        // a case that failed or threw before the change and is skipped now, under its name: the case that failed need not be a test that ran. JUnit 4
        // reports a failing @BeforeClass as one case of the class, and an assumption that fails there as one skipped case of the class: a change of how
        // the set-up fails, not a test switched off. Could not be judged, with the reason
        write(repo, "checks/failing.t", FAILING.replace("failing: needs fixed", "failing: skip").replace("throwing: error-unless fixed", "throwing: skip"));
        String failed = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a case that failed or threw before the change and is skipped now under its name could not be judged: it need not be a test that ran",
                failed.contains("      checks/failing.t  [self test]\n        twin: more than one test reports this name, so they cannot be told apart\n        the file runs 3 of its tests where it ran 5 before the change, and skips 4 where it "
                        + "skipped 2 (skipped now under names that ran before: failing, throwing). But the file as it was reported failures (a failure outside a test's body, in its "
                        + "set-up, tear-down or loading, may be reported in place of the file's tests or as a case of its own), so that report may not list every test: whether a test "
                        + "that ran does not run now is not known\n") && !failed.contains("ran before the change and") && code == 0);
        write(repo, "checks/failing.t", FAILING);

        // two cases of one name before the change, one of which ran: both skipped now
        write(repo, "checks/off.t", OFF.replace("twin: pass", "twin: skip"));
        String twins = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: of two tests of one name, one ran and both are skipped now: a test that ran does not run now, with the counts",
                twins.contains(L1 + "      checks/off.t  [self test]\n        the file as it was passed 3 of its tests, and after the change it runs 2: at least 1 test that ran "
                        + "before the change does not run now (skipped now under a name that ran before: twin)\n") && code == 1);

        // a second test of the name exists only before the change: it does not hide the one switched off
        write(repo, "checks/off.t", OFF.replace("target: pass", "target: skip\ntarget: skip-without fixed"));
        String hidden = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: two tests of one name on the side before the change do not hide a test switched off",
                hidden.contains(L1 + "      checks/off.t  [self test]\n        the file as it was passed 3 of its tests, and after the change it runs 2: at least 1 test that ran "
                        + "before the change does not run now (skipped now under a name that ran before: target)\n") && code == 1);

        // two cases exchange which of them is skipped: a test switched off and another switched on, or rows that changed places
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass\nparams(int)[2]: skip", "params(int)[1]: skip\nparams(int)[2]: pass"));
        String reordered = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: two cases that exchange which of them is skipped could not be judged, with the counts and both stories",
                reordered.contains("could not be judged\n") && reordered.contains("\n        the file runs 3 of its tests where it ran 3 before the change, and skips 2 where it skipped 2 "
                        + "(skipped now under a name that ran before: params(int)[1]), and its list of cases changed as well (0 names it did not report, 0 it no longer reports, "
                        + "1 skipped before and run now). It cannot be told whether a test that ran was switched off, or cases changed names")
                && !reordered.contains("ran before the change and is skipped now") && code == 0);
        // one invocation that ran is skipped now, and a new one that runs is added: the new one does not make up for it, and the
        // reports cannot tell this from a skipped case added in front of the others. Not passed, not accused: could not be judged.
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass", "params(int)[1]: skip").replace("params(int)[2]: skip", "params(int)[2]: skip\nparams(int)[3]: pass"));
        String paid = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: an invocation switched off is not made up for by a new one that runs: it could not be judged, in every run",
                paid.contains("could not be judged\n") && paid.contains("\n        the file runs 3 of its tests where it ran 3 before the change, and skips 3 where it skipped 2 "
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
                shifted.contains("\n        the file runs 4 of its tests where it ran 3 before the change, and skips 2 where it skipped 2 (skipped under a name the file did not report "
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
                appended.contains("\n        the file runs 3 of its tests where it ran 3 before the change, and skips 3 where it skipped 2 (skipped under a name the file did not report "
                        + "before: params(int)[3]). So more are skipped while as many or more run") && !appended.contains(" is skipped\n")
                && !appended.contains("ran before the change and is skipped now") && code == 0);
        write(repo, "checks/off.t", OFF.replace("params(int)[1]: pass", "params(int)[1]: skip"));
        String fewer = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: when more invocations are skipped than before, a test that ran does not run now, with the counts",
                fewer.contains(L1 + "      checks/off.t  [self test]\n        the file as it was passed 3 of its tests, and after the change it runs 2: at least 1 test that ran "
                        + "before the change does not run now (skipped now under a name that ran before: params(int)[1])\n") && code == 1);

        // a skip that comes and goes is unstable, not switched off; one run alone cannot tell
        write(repo, "checks/off.t", OFF.replace("target: pass", "target: skip-once fixed"));
        String wavering = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a test skipped in one run after the change and not in another is unstable, not a test that does not run now",
                !lostSaid(wavering) && wavering.contains("1 gave different results from run to run\n      checks/off.t  [self test]\n        the file as it was "
                        + "passed 3 of its tests, and after the change it runs 2 to 3, from run to run (skipped now under a name that ran before: target): whether tests that ran before the "
                        + "change do not run now is not the same in every run: what the file reports, after the change or as it was, differs from run to run\n") && code == 0);
        // a case that only a repeat run reports: the decision is made again from that run's whole report, and it is another one
        write(repo, "checks/off.t", OFF.replace("target: pass", "target: skip\nlate: skip-later"));
        String late = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test switched off, in one run", late.contains(L1 + "      checks/off.t  [self test]\n        the file as it was passed 3 of its tests, and after the change it "
                + "runs 2: at least 1 test that ran before the change does not run now (skipped now under a name that ran before: target)") && code == 1);
        late = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a skipped case that only a repeat run reports does not change how many tests run: the test switched off does not run in any run",
                late.contains(L1 + "      checks/off.t  [self test]\n        the file as it was passed 3 of its tests, and after the change it runs 2: at least 1 test that ran "
                        + "before the change does not run now (skipped now under a name that ran before: target)") && !late.contains("from run to run") && code == 1);
        write(repo, "checks/off.t", OFF);

        // which of two nested classes' tests of one name is skipped changes from run to run: the counts are the same, the tests are not
        write(repo, "checks/nested.t", NESTED.replace("demo.Outer$A::works: pass", "demo.Outer$A::works: skip-once fixed").replace("demo.Outer$B::works: pass", "demo.Outer$B::works: pass-once"));
        String alternate = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test of a nested class skipped now is shown with its class where another class has a test of its name",
                alternate.contains(L1 + "      checks/nested.t  [self test]\n        the file as it was passed 3 of its tests, and after the change it runs 2: at least 1 test that ran "
                        + "before the change does not run now (skipped now under a name that ran before: demo.Outer$A.works)") && code == 1);
        alternate = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a skip that moves between two nested classes' tests of one name from run to run: in every run a test that ran does not run now, and that is said",
                alternate.contains(L1 + "      checks/nested.t  [self test]\n        the file as it was passed 3 of its tests, and after the change it runs 2: at least 1 test that ran "
                        + "before the change does not run now") && !alternate.contains("from run to run") && code == 1);
        write(repo, "checks/nested.t", NESTED);

        // the same kind and count, the same names that ran before and are skipped now, the same counts of names gone and new: only the words of the
        // answer differ between the runs (one case of b runs in the first, two in the repeat). Another answer all the same: unstable
        write(repo, "checks/flip.t", "a: skip\nb 1: pass\nb 2: skip-once fixed\n");
        String worded = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a repeat run whose answer differs from the first only in its words is unstable",
                worded.contains("1 gave different results from run to run\n      checks/flip.t  [self test]\n        whether tests that ran before the change are skipped now differs "
                        + "from run to run") && !worded.contains("1 skipped before and run now") && code == 0);
        write(repo, "checks/flip.t", FLIP);

        // the file as it was ran the test in its first run and skipped it in a later one: the repeat runs of the old side count. The line speaks of the
        // runs as it was and after the change; that the file as it was, on the code after the change, also differs from run to run is said beside it
        write(repo, "checks/once.t", "target: skip\n");
        String once = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a test that ran as the file was in one run and was skipped there in another is unstable, not a test that does not run now",
                once.contains("2 gave different results from run to run\n      checks/once.t  [self test]\n        the file as it was passed 0 to 1 of its tests, and after the change "
                        + "it runs 0, from run to run (skipped now under a name that ran before: target): whether tests that ran before the change do not run now is not the same in every run")
                && once.contains("\n        the file was reported differently from run to run, in ways no rule of the check judged: target (as it was, on the code after the change: "
                        + "passed, skipped)\n") && !lostSaid(once) && code == 0);
        write(repo, "checks/once.t", ONCE);

        // a name is the test, brackets and all: "rate" and "rate[3]" are two names, and one does not stand in for the other
        write(repo, "checks/titles.t", "rate: skip\nrate[3]: skip\n");
        String titles = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a test whose name differs from another's only by a number in brackets is judged on its own",
                titles.contains(L1 + "      checks/titles.t  [self test]\n        the file as it was passed 1 of its tests, and after the change it runs 0: at least 1 test that ran "
                        + "before the change does not run now (skipped now under a name that ran before: rate)") && code == 1);
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
                grown.contains("\n        fragile, holds, rows(int)[1], wobbly passed before the change and fail on the code after it as the file was. A test of each name still runs after the "
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
                && renamed.contains("1 gave different results from run to run\n      checks/broken.t  [self test]\n        wobbly: passed before the change, and as the test was, failed on the "
                        + "code after the change in one run and not in another\n")
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
        // a test that passes renamed, beside a test the change breaks skipped: the skip is all said as a test taken out of the way, and the rename
        // beside it could not be judged, as always
        write(repo, "checks/broken.t", MENDED.replace("steady: pass", "steadfast: pass").replace("fragile: pass", "fragile: skip"));
        String renamedBesideOff = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a rename beside a broken test switched off could not be judged, though the count of the skips is all said as the broken test",
                renamedBesideOff.contains("        fragile: passed before the change; as the test was, on the code after the change fragile fails: expected something else, and the change "
                        + "skips it now\n") && renamedBesideOff.contains("        the file no longer reports 1 test under the name it had (steady), and reports 1 under a name it did "
                        + "not have (steadfast). The reports cannot tell a test renamed from a test deleted and another written\n")
                && !renamedBesideOff.contains("ran before the change and is skipped now") && code == 1);
        // two tests the change breaks, one deleted and one skipped: two tests taken out of the way, and nothing more. The skip is not counted again
        // beside them, and as a name is skipped now that ran, whether the deleted one was removed is not said either (it may be in the skipped case)
        write(repo, "checks/broken.t", MENDED.replace("holds: pass\n", "").replace("fragile: pass", "fragile: skip"));
        String deletedAndSkipped = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: two old tests the change breaks, one deleted and one skipped, are two tests taken out of the way, not counted again as skipped now",
                deletedAndSkipped.contains("2 tests passed before the change and fail on the code after it as the test was, and the change took them out of the way: removed, renamed or "
                        + "switched off\n      checks/broken.t  [self test]\n        fragile: passed before the change; as the test was, on the code after the change fragile "
                        + "fails: expected something else, and the change skips it now\n        holds: passed before the change; as the test was, on the code after the change holds fails: "
                        + "expected something else. The edited file does not report it now\n")
                && !deletedAndSkipped.contains("ran before the change and is skipped now") && !deletedAndSkipped.contains("removed: the edited file") && code == 1);
        // deleted, and a test that passes deleted too: two fewer, one of them said to be taken out of the way, so at least one more removed
        write(repo, "checks/broken.t", MENDED.replace("holds: pass\n", "").replace("steady: pass\n", ""));
        String twoGone = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: two tests deleted, one of which the change breaks: taken out of the way, and at least one more removed besides it",
                twoGone.contains("        holds: passed before the change; as the test was, on the code after the change holds fails")
                && twoGone.contains(L1 + "      checks/broken.t  [self test]\n        the file as it was passed 6 of its tests, and after the change it runs 4: at least 2 tests that "
                        + "ran before the change do not run now (no longer reported: holds, steady). At most 1 of them is an old test said above to be taken out of the way, so at least 1 "
                        + "more\n") && code == 1);

        // two nested classes each lose their method of one name, and the change breaks only the first: the second is not taken for the first
        write(repo, "checks/broken.t", BROKEN);
        write(repo, "checks/pair.t", "demo.Pair::top: pass\n");
        String pair = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: of two nested classes' tests of one name, the one the change breaks is taken out of the way, and the other is still counted as not run now",
                pair.contains("      checks/pair.t  [self test]\n        demo.Pair$A.works: passed before the change; as the test was, on the code after the change demo.Pair$A.works "
                        + "fails: expected something else. The edited file does not report it now\n")
                && pair.contains(L1 + "      checks/pair.t  [self test]\n        the file as it was passed 3 of its tests, and after the change it runs 1: at least 2 tests that ran "
                        + "before the change do not run now (no longer reported: demo.Pair$A.works, demo.Pair$B.works). At most 1 of them is an old test said above to be taken out "
                        + "of the way, so at least 1 more\n") && code == 1);
        write(repo, "checks/pair.t", PAIR);
        // the same two methods renamed in their classes: the first, which the change breaks, is taken out of the way; the second is not judged by the
        // first's name, and the two names gone beside two new ones could not be judged as a rename
        write(repo, "checks/pair.t", "demo.Pair$A::kept: pass\ndemo.Pair$B::kept: pass\ndemo.Pair::top: pass\n");
        String pairRenamed = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: an old test taken out of the way does not stand for another class's test of its name: the rename beside it could not be judged",
                pairRenamed.contains("      checks/pair.t  [self test]\n        demo.Pair$A.works: passed before the change; as the test was, on the code after the change "
                        + "demo.Pair$A.works fails: expected something else. The edited file does not report it now\n")
                && pairRenamed.contains("        the file no longer reports 2 tests under the names it had (demo.Pair$A.works, demo.Pair$B.works), and reports 2 under names it did not "
                        + "have (demo.Pair$A.kept, demo.Pair$B.kept). The reports cannot tell a test renamed from a test deleted and another written\n") && code == 1);
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

        // the audit's shape: a test the change breaks, skipped in the first run after the change, run in the repeat of the skips and skipped again in
        // the repeat of the fourth run. Every run seen counts, whichever repeat made it: the passing run in the middle disproves "skipped in every run"
        write(repo, "checks/broken.t", MENDED.replace("holds: pass", "holds: skip-odd fixed"));
        String skippedTwice = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: an old test the change breaks, skipped in the first and the last run after the change and run in the middle one, is unstable, not taken out of the way",
                skippedTwice.contains("      checks/broken.t  [self test]\n        holds: passed before the change; as the test was, on the code after the change holds fails: "
                        + "expected something else, and the change skips it, or the file does not report it, in one run after the change and runs it in another\n")
                && !skippedTwice.contains("out of the way: removed") && !skippedTwice.contains("ran before the change and is skipped now") && code == 0);
        // and a case that can be a rewrite of the broken test only in the middle run: it counts in M all the same
        write(repo, "checks/broken.t", MENDED.replace("holds: pass", "holds anew: skip-odd fixed"));
        String rewriteMiddle = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("check: a case that can be a rewrite of a broken test only in the repeat of the skips keeps it from being said taken out of the way",
                rewriteMiddle.contains("        holds passed before the change and fails on the code after it as the file was, and the file does not report it now or skips it. It has "
                        + "1 test that passes after the change and does not pass on both sides (holds anew), so it cannot be told whether the old test was rewritten")
                && !rewriteMiddle.contains("out of the way: removed") && code == 0);

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
        check("check: the last row taken out of a parameterized test whose first row the change breaks: a test that ran does not run now, and the first row could not be judged",
                rowGone.contains(L1 + "      checks/broken.t  [self test]\n        the file as it was passed 6 of its tests, and after the change it runs 5: at least 1 test that ran "
                        + "before the change does not run now (no longer reported: rows(int)[2])\n")
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
                        + "renamed or switched off\n      checks/broken.t  [self test]\n        fragile, holds passed before the change and fail on the code after it as the file "
                        + "was, and the file does not report them now or skips them. It has 1 test that passes after the change and does not pass on both sides (holds anew), "
                        + "and at most 1 of the 2 old tests can be among them, rewritten for the changed behaviour: at least 1 was taken out of the way\n") && code == 1);
        // three broken tests renamed, and only one sensitive test that can be a rewrite: at least two were taken out of the way
        write(repo, "checks/broken.t", MENDED.replace("holds: pass", "holds anew: needs fixed").replace("fragile: pass", "sturdy: pass").replace("rows(int)[1]: pass", "rows(int)[0]: pass"));
        String two = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a file whose counts prove two tests taken out of the way is counted as two",
                two.contains("at least 2 tests passed before the change and fail on the code after it as the test was, and the change took them out of the way: removed, "
                        + "renamed or switched off\n      checks/broken.t  [self test]\n        fragile, holds, rows(int)[1] passed before the change and fail on the code after it as "
                        + "the file was") && two.contains("and at most 1 of the 3 old tests can be among them, rewritten for the changed behaviour: at least 2 were taken out of the way\n")
                && code == 1);
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
                        + "from a test deleted and another written\n        the old tests could not be run on the code after the change: there the file as it was does not compile, so it "
                        + "is not known whether the change broke any of the 2 old tests that passed before it (era, lasting) and took it out of the way or replaced it\n")
                && !era.contains("out of the way:") && code == 0);
        write(repo, "checks/era.t", ERA);

        // the audit's shape: a row the change breaks taken out while the file grows, [a, b] to [b, d, c] named by place, and the change also takes out
        // what the old file needs to load. Nothing is gone, skipped or short, and no other verdict of the file is there to carry why the old tests
        // could not be judged: the file gets a verdict of its own
        write(repo, "checks/grow.t", "rows(int)[1]: pass\nrows(int)[2]: pass\nrows(int)[3]: needs fixed\nloads: pass\n");
        String outgrown = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a file whose old version does not load on the change, where nothing else is said of it, could not be judged: the old tests could not be run on the change",
                outgrown.contains("      checks/grow.t  [self test]\n        the old tests could not be run on the code after the change: there the file as it was does not compile, so it "
                        + "is not known whether the change broke any of the 3 old tests that passed before it (loads, rows(int)[1], rows(int)[2]) and took it out of the way or replaced it\n")
                && outgrown.contains("      checks/grow.t  [self test]\n        rows(int)[3]\n") && !outgrown.contains("out of the way:") && code == 0);
        write(repo, "checks/grow.t", GROW);
        write(repo, "prod.txt", "v1\nfixed\n");

        // the file as it was runs on the change, and skips one of the old tests asked about: that one is not judged, and the file says so
        write(repo, "checks/shy.t", "steady: pass\nshy: pass\nfresh: needs fixed\n");
        String shy = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: an old test that the fourth run does not report as passed or failed could not be judged, said of its file",
                shy.contains("      checks/shy.t  [self test]\n        the old tests could not all be judged on the code after the change: there the file as it was gave no clean "
                        + "result, passed or failed, for one of them (not reported, reported more than once, skipped, or run again by the build with another result), so it is not known "
                        + "whether the change broke the old test that passed before it (shy) and took it out of the way or replaced it, or, where it is not reported there, whether the "
                        + "change of the code, not of the tests, took it away, as it does a row of a parameterized test whose source it shortened\n")
                && shy.contains("      checks/shy.t  [self test]\n        fresh\n") && code == 0);
        write(repo, "checks/shy.t", SHY);
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
        check("check: a failing case gone from a file whose report as it was held a failure, its tests all running, is not a test lost: could not be judged, with the reason",
                hook.contains("      checks/hooked.t  [self test]\n        the file no longer reports 1 test under the name it had (afterAll hook), and reports none under a name it "
                        + "did not have. As many tests run as passed as it was (2 where 2 passed), so no test that ran is lost by count. But the file as it was reported failures (a "
                        + "failure outside a test's body, in its set-up, tear-down or loading, may be reported in place of the file's tests or as a case of its own), so that report "
                        + "may not list every test: whether tests that did not pass as it was were removed is not known\n")
                && !lostSaid(hook) && code == 0);
        write(repo, "checks/hooked.t", HOOKED);

        // the class's set-up mended: its two tests come out from behind the one case that stood for them, and neither is a new test
        write(repo, "checks/init.t", "works: pass\nalso works: pass\n");
        String init = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: tests that come out from behind a failure reported in their place are not new tests that pass on both sides: could not be judged, with the reason",
                init.contains("      checks/init.t  [self test]\n        also works: passes before and after, but it cannot be told whether it is a new test: the file reports 2 tests it did "
                        + "not have and no longer reports 1 it had. It may be a new test that does not test the change, or a test the file had: the test it no longer reports, under a "
                        + "new name, or a test the file had that its report as it was did not list: the file as it was reported failures")
                && !init.contains("new test pass") && code == 0);
        write(repo, "checks/init.t", INIT);

        // a file whose tests failed as it was: one test gone and one skipped now. Neither count is said, as the report as it was may not list every test
        write(repo, "checks/failing.t", FAILING.replace("target: pass\n", "").replace("params(int)[1]: pass", "params(int)[1]: skip"));
        String failedBefore = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: in a file whose report as it was held failures, fewer cases run and one is gone, while as many run as passed: could not be judged, with the reason",
                failedBefore.contains("        the file runs 3 of its tests where it ran 5 before the change, and skips 3 where it skipped 2 (skipped now under a name that ran before: "
                        + "params(int)[1]). But the file as it was reported failures") && failedBefore.contains("whether a test that ran does not run now is not known")
                && failedBefore.contains("As many tests run as passed as it was (3 where 3 passed)") && failedBefore.contains("were removed is not known")
                && !lostSaid(failedBefore) && code == 0);
        write(repo, "checks/failing.t", FAILING);

        // after the change a test fails and another is skipped: the skipped one ran and does not run now, whatever skipped it; a failure can make the
        // build skip the tests after it (Surefire's skipAfterFailureCount), and that is said beside it. The case that fails counts as one that runs
        write(repo, "checks/off.t", OFF.replace("target: pass", "target: skip").replace("params(int)[1]: pass", "params(int)[1]: fail"));
        String failedAfter = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a skip beside a failure after the change is a test that ran and does not run now, with the failure's possible part said, and the failure fails the check",
                failedAfter.contains(L1 + "      checks/off.t  [self test]\n        the file as it was passed 3 of its tests, and after the change it runs 2: at least 1 test that ran "
                        + "before the change does not run now (skipped now under a name that ran before: target). After the change the file reports failures, and a build that stops "
                        + "after a failure (Surefire's skipAfterFailureCount) skips the tests after it, so some of them may not run for that\n")
                && failedAfter.contains("1 fails after the change") && code == 1);
        write(repo, "checks/off.t", OFF);

        // after the change a failure stands in place of the tests: it fails the check, and counts as a case that runs, so the two tests that passed as
        // it was are at least one that does not run now, not two: the failure is not counted again as a test that does not run
        write(repo, "checks/side/both.t", "initializationError: error-unless never\n");
        String standIn = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a failure reported in place of a file's tests after the change fails the check, and only the tests beyond it are said not to run now",
                standIn.contains("1 fails after the change\n      checks/side/both.t  [self test]\n        initializationError: throws IllegalStateException")
                && standIn.contains(L1 + "      checks/side/both.t  [self test]\n        the file as it was passed 2 of its tests, and after the change it runs 1: at least 1 test that ran "
                        + "before the change does not run now (no longer reported: goes, stays; reported for the first time: initializationError)") && code == 1);
        write(repo, "checks/side/both.t", SIDE);

        // and an old test the change breaks, gone from such a report, is not said to be taken out of the way
        write(repo, "checks/broken.t", MENDED.replace("holds: pass\n", "") + "initializationError: error-unless never\n");
        String brokenStandIn = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a broken old test gone from a report that holds a failure after the change could not be judged, not taken out of the way",
                brokenStandIn.contains("        holds passed before the change and fails on the code after it as the file was, and the file does not report it now or skips it. But after "
                        + "the change the file reports failures") && !brokenStandIn.contains("out of the way: removed") && brokenStandIn.contains("1 fails after the change") && code == 1);
        write(repo, "checks/broken.t", BROKEN);

        // the audit's shape: an ignored class, reported as one skipped case, is switched on, and its three tests run and pass on both sides. No test
        // was added: the skipped case gone stood for them. Could not be judged, not new tests that pass on both sides
        write(repo, "checks/ignored.t", "demo.Ignored::first: pass\ndemo.Ignored::second: pass\ndemo.Ignored::third: pass\n");
        String enabled = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: tests that come out from behind a skipped case gone are not new tests that pass on both sides, nor a test lost: could not be judged, with the reason",
                !lostSaid(enabled) && enabled.contains("4 could not be judged\n") &&
                enabled.contains("      checks/ignored.t  [self test]\n        first: passes before and after, but it cannot be told whether it is a new test: the file reports 3 tests "
                        + "it did not have and no longer reports 1 it had. It may be a new test that does not test the change, or a test the file had: the test it no longer reports, "
                        + "under a new name, or a test the file had that its report as it was did not list: the file as it was skipped a case under a name it does not skip as often "
                        + "after the change (demo.Ignored) (a skipped class, factory or template is reported as one case, whatever number of tests it holds), so that report may not "
                        + "list every test\n") && !enabled.contains("new test pass") && code == 0);
        write(repo, "checks/ignored.t", IGNORED);

        // the other way: a class of three tests that ran is ignored now, one skipped case in their place. Whatever the skipped case holds, none of the
        // three runs now: all three are said, in one line
        write(repo, "checks/plain.t", "demo.Plain::demo.Plain: skip\n");
        String ignored = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: a class switched off as one skipped case: each of its tests that ran does not run now",
                ignored.contains("at least 3 tests that ran before the change do not run now\n      checks/plain.t  [self test]\n        the file as it was passed 3 of its tests, and "
                        + "after the change it runs 0: at least 3 tests that ran before the change do not run now (no longer reported: first, second, third; skipped under a name the "
                        + "file did not report before: demo.Plain)\n") && !ignored.contains("could not be judged") && code == 1);
        write(repo, "checks/plain.t", PLAIN);

        // and where the change breaks the tests of the class it switches off, the fourth run says so by name, whatever the skipped case hides
        write(repo, "checks/broken.t", "demo.Broken::demo.Broken: skip\n");
        String brokenOff = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("check: old tests the change breaks, switched off with their class as one skipped case, were taken out of the way, by name",
                brokenOff.contains("      checks/broken.t  [self test]\n        fragile: passed before the change; as the test was, on the code after the change fragile fails: "
                        + "expected something else. The edited file does not report it now\n        holds: passed before the change; as the test was, on the code after the change "
                        + "holds fails: expected something else. The edited file does not report it now\n") && code == 1);
        write(repo, "checks/broken.t", BROKEN);
    }

    /** A test file of the base with a parameterized test whose last row the code gives only where prod.txt holds v1, as an enum value does. */
    private static final String ROWS = "rows[1]: pass\nrows[2]: pass\nrows[3]: pass-with v1\n";

    /** A test file of the base whose last row its source gives only from the second run in a checkout on. */
    private static final String VARIES = "a: pass\nb: pass\nr: pass-later\n";

    /**
     * The one rule for tests that ran before the change and do not run now, case by case: what
     * each change of a file's tests says, with its kind, its count and the exit code.
     */
    private static void lost(Path repo) throws IOException {
        // a class ignored as a whole that already held a skipped method: the method that ran does not run now, whatever the skips hide
        write(repo, "checks/flip.t", "demo.Flip::demo.Flip: skip\n");
        String flip = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("lost: a class ignored as a whole beside a method already skipped: its test that ran does not run now, one, and the check fails",
                flip.contains(L1 + "      checks/flip.t  [self test]\n        the file as it was passed 1 of its tests, and after the change it runs 0: at least 1 test that ran "
                        + "before the change does not run now (no longer reported: a, b 1, b 2; skipped under a name the file did not report before: demo.Flip)\n")
                && !flip.contains("could not be judged") && code == 1);
        write(repo, "checks/flip.t", FLIP);

        // a skipped test deleted: as many tests run as passed before, so nothing that ran is lost; the skipped case gone could not be judged
        write(repo, "checks/titles.t", "rate: pass\n");
        String skippedGone = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("lost: a skipped test deleted loses no test that ran: the case gone could not be judged, and the check passes",
                skippedGone.contains("1 could not be judged\n      checks/titles.t  [self test]\n        the file no longer reports 1 test under the name it had (rate[3]), and reports "
                        + "none under a name it did not have. As many tests run as passed as it was (1 where 1 passed), so no test that ran is lost by count. But the file as it was "
                        + "skipped a case under a name it does not skip as often after the change (rate[3])")
                && !lostSaid(skippedGone) && code == 0);
        write(repo, "checks/titles.t", TITLES);

        // a failing set-up turned into an assumption (JUnit 4: one case of the class, errored as it was, skipped now): no test passed as it was,
        // so none is lost; what the reports cannot tell could not be judged
        write(repo, "checks/init.t", "demo.Init::demo.Init: skip\n");
        String assumed = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("lost: a failing set-up turned into an assumption is not a test lost: could not be judged, and the check passes",
                assumed.contains("2 could not be judged\n      checks/init.t  [self test]\n") && assumed.contains("But the file as it was reported failures")
                && !lostSaid(assumed) && code == 0);
        write(repo, "checks/init.t", INIT);
        // a test deleted from a file whose report as it was held a failure: fewer run than passed, but the file did not pass as it was: not said definite
        write(repo, "checks/hooked.t", "adds: pass\n");
        String brokenFile = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("lost: a test deleted from a file that failed as it was could not be judged, said once, and the check passes",
                brokenFile.contains("1 could not be judged\n      checks/hooked.t  [self test]\n        the file as it was passed 2 of its tests, and after the change it runs 1 "
                        + "(no longer reported: afterAll hook, refunds). But the file as it was reported failures (a failure outside a test's body, in its set-up, tear-down or "
                        + "loading, may be reported in place of the file's tests or as a case of its own): a file that did not pass as it was is not said to have lost tests, so "
                        + "whether a test that ran does not run now is not known\n") && !lostSaid(brokenFile) && code == 0);
        write(repo, "checks/hooked.t", HOOKED);

        // rows put in, at the end or before the others, and tests that change places: no test that ran is lost
        write(repo, "checks/named.t", NAMED.replace("rejectsNull(String)[%i]: pass\n", "rejectsNull(String)[%i]: pass\nrejectsNull(String)[3]: pass\n"));
        String appended = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("lost: a row put in a parameterized test loses no test", !lostSaid(appended) && appended.contains("at least 1 new test passes"));
        write(repo, "checks/named.t", NAMED.replace("refund: pass\ngoes: pass\n", "goes: pass\nrefund: pass\n"));
        String reordered = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("lost: tests that change places lose no test, and the check passes", !lostSaid(reordered) && !reordered.contains("could not be judged") && code == 0);
        write(repo, "checks/named.t", NAMED);

        // two tests merged into one: one test that ran does not run now. A stance, said in the guide: the count cannot tell a merge from a test deleted
        write(repo, "checks/judged.t", JUDGED.replace("goes: pass\ngoes too: pass\n", "goes and goes too: pass\n"));
        String merged = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("lost: two tests merged into one are one test that ran and does not run now, and the check fails",
                merged.contains(L1 + "      checks/judged.t  [self test]\n        the file as it was passed 4 of its tests, and after the change it runs 3: at least 1 test that ran "
                        + "before the change does not run now (no longer reported: goes, goes too; reported for the first time: goes and goes too)\n") && code == 1);
        write(repo, "checks/judged.t", JUDGED);

        // a source that gives another number of rows each run, and a test deleted: in one run as many run as passed as it was, in another not. The line
        // answers for the counts as it was and after the change only: the row that the file as it was, on the code after the change, gives in one run
        // and not in another is said beside it
        write(repo, "checks/varies.t", "a: pass\nr: pass-later\n");
        String varies = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("lost: a file whose rows differ in number from run to run is unstable, not a test lost, and the check passes",
                varies.contains("3 gave different results from run to run\n      checks/varies.t  [self test]\n        the file as it was passed 2 to 3 of its tests, and after the "
                        + "change it runs 1 to 2, from run to run (no longer reported: b): whether tests that ran before the change do not run now is not the same in every run")
                && varies.contains("\n        the file was reported differently from run to run, in ways no rule of the check judged: r (as it was, on the code after the change: "
                        + "not reported, passed)\n") && !lostSaid(varies) && code == 0);
        write(repo, "checks/varies.t", VARIES);

        // an enum value removed in the production code (the row the code gives only with v1): with the test file untouched it is not run at all
        write(repo, "prod.txt", "fixed\n");
        String untouched = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("lost: a row the code no longer gives, with its test file untouched, is not judged: the file is not part of the change",
                !untouched.contains("checks/rows.t") && !lostSaid(untouched) && code == 0);
        // the test file edited elsewhere: as it was, on the code after the change, it runs that row no more either. The code took it, not the tests:
        // could not be judged, said once, by the old tests' own line
        write(repo, "checks/rows.t", ROWS + "  edited\n");
        String enumEdited = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("lost: a row the code no longer gives, in an edited test file, is not said to be lost by the change of the tests: could not be judged, once",
                enumEdited.contains("1 could not be judged\n      checks/rows.t  [self test]\n        the old tests could not all be judged on the code after the change: there the "
                        + "file as it was gave no clean result, passed or failed, for one of them (not reported, reported more than once, skipped, or run again by the build with another "
                        + "result), so it is not known whether the change broke the old test that passed before it (rows[3]) and took it out of the way or replaced it, or, where it is "
                        + "not reported there, whether the change of the code, not of the tests, took it away")
                && !lostSaid(enumEdited) && code == 0);
        // and a row deleted from the test file beside it: on the code after the change the file as it was runs 2 and the file as the change has it 1, so
        // the change of the tests runs fewer: the two that ran before and do not run now are said, and which change took which is not claimed
        write(repo, "checks/rows.t", "rows[1]: pass\nrows[3]: pass-with v1\n");
        String enumAndRow = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("lost: a row deleted beside a row the code no longer gives: the tests that ran and do not run now are said, without saying which change took them",
                enumAndRow.contains("at least 2 tests that ran before the change do not run now\n      checks/rows.t  [self test]\n        the file as it was passed 3 of its tests, and after "
                        + "the change it runs 1: at least 2 tests that ran before the change do not run now (no longer reported: rows[2], rows[3]). As it was, on the code after the "
                        + "change, the file runs 2, and as the change has it 1: the change of the code may have taken some of them, and on that code the change of the tests runs "
                        + "fewer tests than the file as it was\n")
                && !enumAndRow.contains("taken by the change of the tests")
                && enumAndRow.contains("1 could not be judged\n      checks/rows.t  [self test]\n        the old tests could not all be judged") && code == 1);
        write(repo, "checks/rows.t", ROWS);
        // the file as it was does not compile on the code after the change (it needs v1), and a test is deleted: what took it cannot be told, and
        // it ran and does not run now all the same. Said, with why the cause is not known
        write(repo, "checks/era.t", "era: pass\n");
        String unrun = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("lost: a test deleted where the file as it was cannot run on the code after the change is still a test lost, with why its cause is not known",
                unrun.contains(L1 + "      checks/era.t  [self test]\n        the file as it was passed 2 of its tests, and after the change it runs 1: at least 1 test that ran "
                        + "before the change does not run now (no longer reported: lasting). The file as it was gave no report usable for this count on the code after the change "
                        + "(there it does not compile), so whether the change of the tests or of the code took them is not known; either way they ran before the change and do not "
                        + "run now\n")
                && code == 1);
        write(repo, "checks/era.t", ERA);
        // CR6-2: as it was, on the code after the change, the file ran and passed its old tests, then a tear-down failed (a case of its own): that run did
        // run, and its report is not usable for the count. Said so, with the reason, not as a file that could not be run
        write(repo, "checks/hook.t", "a: pass\n");
        String torn = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("lost: a fourth run whose report is not usable for the count (a tear-down failed after the tests ran) is said so, with why, not as a run that could not be made",
                torn.contains(L1 + "      checks/hook.t  [self test]\n        the file as it was passed 2 of its tests, and after the change it runs 1: at least 1 test that ran "
                        + "before the change does not run now (no longer reported: b). The file as it was gave no report usable for this count on the code after the change (there it "
                        + "reported a failure that is not one of its old tests (teardown)")
                && !torn.contains("could not be run on the code after the change") && code == 1);
        write(repo, "checks/hook.t", HOOK);
        write(repo, "prod.txt", "v1\nfixed\n");
    }

    /** A test file of the base whose second test failed and passed when the build ran it again (Surefire's flakyFailure). */
    private static final String RETRY = "steady: pass\nflaky: pass-retried\n";

    /** A test file of the base whose class set-up threw every time the build ran it (error, then rerunError), in place of its tests. */
    private static final String SPENT = "initializationError: error-retried\n";

    /** A test file of the base with one test. */
    private static final String KEPT = "kept: pass\n";

    /** A test file of the base with one test. */
    private static final String FRESH = "a: pass\n";

    /** A test file of the base whose hook passes the first time it runs in a checkout whose code holds v1, and fails later there. */
    private static final String INV = "a: pass\nhook: fails-later v1\n";

    /** The same, a file of its own. */
    private static final String WOB = "a: pass\nb: fails-later v1\n";

    /** A test file of the base with rows the code gives: B, C and D without the fix, E and F with it. */
    private static final String CODE = "A: pass\nB: pass-without fixed\nC: pass-without fixed\nD: pass-without fixed\nE: pass-with fixed\nF: pass-with fixed\n";

    /** Why a failure as it was need not be a test, as the reports say it. */
    private static final String FAILED = " (a failure outside a test's body, in its set-up, tear-down or loading, may be reported in place of the file's tests or as a case of its own)";

    /** The words of the line said where the reports differ in a way no rule judged (see Witness.reconcile). */
    private static final String NO_RULE = "no rule of the check judged";

    /**
     * Every difference among a file's reports gets an answer: the shapes of the third audit (a
     * retried test switched off, a repeat that loses or breaks an existing test, a repeat that
     * undoes a new-test count, an exhausted retry, rows the code took), what answers where a
     * rule says nothing, and that ordinary work stays quiet.
     */
    private static void everyDifference(Path repo) throws IOException {
        // CR3-1: a test that failed and passed when the build ran it again, switched off. A case that passed only when run again may be a set-up that
        // failed and passed then, not a test, so it is not counted as one that ran: could not be judged, with why, never silence
        write(repo, "checks/retry.t", "steady: pass\nflaky: skip\n");
        String retried = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("every difference: a test that passed only when the build ran it again, switched off, could not be judged, with why",
                retried.contains("1 could not be judged\n      checks/retry.t  [self test]\n        the file as it was passed 1 of its tests, and after the change it runs 1, and passed "
                        + "1 more only when the build ran it again (skipped now under a name that ran before: flaky): a case that passed only when run again may be a set-up that failed "
                        + "and then passed, not a test, so whether a test that ran does not run now is not known\n")
                && !lostSaid(retried) && !retried.contains(NO_RULE) && code == 0);
        Check.mute(java.util.Set.of("lost"));
        String retriedMuted = report(Map.of("selftest-runner", "true", "runs", "1"));
        Check.mute(java.util.Set.of());
        check("every difference: without the rule for lost tests, the retried test switched off is said all the same, by the line for what no rule judged",
                retriedMuted.contains("1 could not be judged\n      checks/retry.t  [self test]\n        the reports of this file as it was and after the change differ in ways " + NO_RULE
                        + ": flaky (as it was: passed only when the build ran it again; after the change: skipped). Whether this test was lost, switched off, broken or replaced is not known\n")
                && code == 0);
        // the same, where the steady test is not reported in the repeat run after the change: the line that could not be judged speaks of the
        // list as it was and after the change, and the repeat that lost the steady test is said beside it
        write(repo, "checks/retry.t", "steady: gone-later fixed\nflaky: skip\n");
        String retriedRepeat = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("every difference: beside the line of a retried test switched off, a test a repeat run does not report is said unstable",
                retriedRepeat.contains("passed 1 more only when the build ran it again") && retriedRepeat.contains("\n        the file was reported differently from run to run, in "
                        + "ways " + NO_RULE + ": steady (after the change: passed, not reported)\n") && code == 0);
        // CR4-3: every test switched off, the flaky one among them, the reports alike in every run: the steady test is lost, and the line does not
        // say the reports vary. The case that passed only when run again is not counted as a test that ran, there or in how the runs compare
        write(repo, "checks/retry.t", "steady: skip\nflaky: skip\n");
        String allOff = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("every difference: a test lost beside a retried one switched off, alike in every run, is said without saying the runs differ, and the check fails",
                allOff.contains(L1 + "      checks/retry.t  [self test]\n        the file as it was passed 1 of its tests, and after the change it runs 0: at least 1 test that "
                        + "ran before the change does not run now (skipped now under names that ran before: flaky, steady)\n")
                && !allOff.contains("in some runs") && !allOff.contains("from run to run") && code == 1);
        write(repo, "checks/retry.t", RETRY);

        // CR3-4: a class set-up that threw every time the build ran it, in place of three tests, mended: not three new tests, and not a pass of the
        // set-up. Counting a retried case as passed would make the old report a full list
        write(repo, "checks/spent.t", "first: pass\nsecond: pass\nthird: pass\n");
        String spent = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("every difference: tests that come out from behind a set-up that failed every time the build ran it are not new tests that pass on both sides",
                spent.contains("4 could not be judged\n") && spent.contains("      checks/spent.t  [self test]\n        first: passes before and after, but it cannot be told whether it is a new "
                        + "test: the file reports 3 tests it did not have and no longer reports 1 it had. It may be a new test that does not test the change, or a test the file had: the "
                        + "test it no longer reports, under a new name, or a test the file had that its report as it was did not list: the file as it was reported failures" + FAILED
                        + ", so that report may not list every test\n")
                && !spent.contains("new test pass") && !spent.contains(NO_RULE) && code == 0);
        write(repo, "checks/spent.t", SPENT);
        // after the change, a test that failed every time the build ran it fails; one that passed only when run again is unstable
        write(repo, "tests/again.t", "spent: fail-retried\nflaky: pass-retried\n");
        String again = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("every difference: a new test that failed every time the build ran it fails after the change, and one that passed only when run again is unstable",
                again.contains("1 fails after the change\n      tests/again.t  [self test]\n        spent: fails: expected something else\n")
                && again.contains("1 gave different results from run to run\n      tests/again.t  [self test]\n        flaky: the build ran it again and got another result\n") && code == 1);
        Files.delete(repo.resolve("tests/again.t"));

        // CR3-2: an existing test passes in the first run after the change and is not reported in the repeat (a new sensitive test beside it is what
        // makes the repeat). No rule reads that: the line for what no rule judged says it, and the test is not counted as passing on both sides
        write(repo, "checks/kept.t", "kept: gone-later fixed\nsensitive: needs fixed\n");
        String vanished = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("every difference: an existing test that a repeat run after the change does not report is unstable, not a test that passes on both sides",
                vanished.contains("1 gave different results from run to run\n      checks/kept.t  [self test]\n        the file was reported differently from run to run, in ways "
                        + NO_RULE + ": kept (after the change: passed, not reported)\n")
                && vanished.contains("2 fail before the change and pass after it, in 2 of 2 runs\n") && vanished.contains("1 test that was already in the edited files passes before and after")
                && code == 0);
        // and where it fails in the repeat instead: a failure after the change in any run is a failure
        write(repo, "checks/kept.t", "kept: fails-later fixed\nsensitive: needs fixed\n");
        String brokeLater = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("every difference: an existing test that fails in a repeat run after the change fails after the change, with in how many runs",
                brokeLater.contains("1 fails after the change\n      checks/kept.t  [self test]\n        kept: fails: expected something else (in 1 of the 2 runs after the change)\n")
                && !brokeLater.contains(NO_RULE) && code == 1);
        // CR4-1: the file compiles in the first run after the change and not in the repeat: code that does not compile or load after the change is
        // the change's own failure in any run, as in the first, said of the file with in how many runs (of each file run with it: nothing of that run compiles)
        write(repo, "checks/kept.t", "kept: pass\nsensitive: compile-later fixed\n");
        String unbuilt = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("every difference: a file that does not compile in a repeat run after the change fails after the change, with in how many runs, and the check fails",
                unbuilt.contains("2 fail after the change\n      checks/kept.t  [self test]\n        after the change it does not compile (in 1 of the 2 runs after the change)\n"
                        + "      tests/old.t  [self test]\n        after the change it does not compile (in 1 of the 2 runs after the change)\n")
                && unbuilt.contains("sensitive: passed after the change in one run and not in another") && !unbuilt.contains(NO_RULE) && code == 1);
        // a repeat that gives no result for a reason outside the code (a timeout) says nothing of the code: from run to run, not a failure
        write(repo, "checks/kept.t", "kept: pass\nsensitive: timeout-later fixed\n");
        String cutOff = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("every difference: a repeat run after the change that times out is said from run to run, not as a failure, and the check passes",
                !cutOff.contains("fails after the change") && cutOff.contains("\n        the file was reported differently from run to run, in ways " + NO_RULE
                        + ": the file after the change (one run gave no result and another gave one)\n") && code == 0);
        write(repo, "checks/kept.t", KEPT);

        // CR3-3: a new test passes on both sides in the first run after the change and is skipped in the repeat: the count is made again in every
        // combination of the runs, and it says a new test passes on both sides in one only: unstable, not accused
        write(repo, "checks/fresh.t", "a: pass\nidle: pass-once\n");
        String onceIdle = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("every difference: a new test that passes on both sides in one run and is skipped in another is unstable, not said to pass on both sides",
                onceIdle.contains("1 gave different results from run to run\n      checks/fresh.t  [self test]\n        the file reports 1 test it did not have: at least 1 new test passes "
                        + "before and after, but what the file reports, after the change or as it was, is not the same in every run\n")
                && !onceIdle.contains("so it does not test the change") && !onceIdle.contains(NO_RULE) && code == 0);
        write(repo, "checks/fresh.t", FRESH);

        // and where the report as it was lists every test in the first run and holds a failure in the repeat (here the code as it was holds v1, the
        // code after the change does not): whether that report lists every test is asked of every run too
        write(repo, "prod.txt", "fixed\n");
        write(repo, "checks/inv.t", INV + "idle: pass\n");
        String unlistedLater = report(Map.of("selftest-runner", "true", "runs", "2"));
        // the line speaks of the new test; the hook that failed in the repeat as it was is what made the count differ, and is said by name beside it
        check("every difference: a new-test count whose report as it was holds a failure in a repeat run is unstable, not said",
                unlistedLater.contains("2 gave different results from run to run\n      checks/inv.t  [self test]\n        the file reports 1 test it did not have: at least 1 new test passes "
                        + "before and after, but what the file reports, after the change or as it was, is not the same in every run\n        the file was reported differently from run "
                        + "to run, in ways no rule of the check judged: hook (as it was: passed, failed)\n")
                && !unlistedLater.contains("so it does not test the change") && code == 0);
        write(repo, "checks/inv.t", INV);
        // a test of the file as it was that passes in one run there and fails in another, where nothing else of the file reads the repeat: said by
        // the line for what no rule judged (the skipped test under a new name is what makes the repeat)
        write(repo, "checks/wob.t", WOB + "later: skip\n");
        String wobbled = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("every difference: a test of the file as it was whose result differs from run to run there, read by no rule, is said unstable",
                wobbled.contains("      checks/wob.t  [self test]\n        the file was reported differently from run to run, in ways " + NO_RULE + ": b (as it was: passed, failed)\n")
                && wobbled.contains("(skipped under a name the file did not report before: later). So more are skipped while as many or more run") && code == 0);
        write(repo, "checks/wob.t", WOB);
        write(repo, "prod.txt", "v1\nfixed\n");

        // CR3-5: the code takes B, C, D and gives E, F; the change of the tests drops F. As it was, on the code after the change, the file runs 3, and
        // as the change has it 2: the change of the tests runs fewer on the same code. What is said is only what counting proves: at least 2 tests that
        // ran before do not run now. Which change took them is not claimed
        write(repo, "checks/code.t", CODE.replace("F: pass-with fixed\n", ""));
        String code5 = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("every difference: rows the code took beside a row the tests dropped: tests that ran do not run now, said without saying which change took them",
                code5.contains("at least 2 tests that ran before the change do not run now\n      checks/code.t  [self test]\n        the file as it was passed 4 of its tests, and after "
                        + "the change it runs 2: at least 2 tests that ran before the change do not run now (no longer reported: B, C, D; reported for the first time: E). As it was, on "
                        + "the code after the change, the file runs 3, and as the change has it 2: the change of the code may have taken some of them, and on that code the change of "
                        + "the tests runs fewer tests than the file as it was\n")
                && !code5.contains("taken by the change of the tests") && code == 1);
        // the same rows taken by the code, with the test file edited elsewhere: as it was, on the code after the change, the file runs as many as now,
        // so the code may have taken all of them: could not be judged, said once, by the fourth run's own line
        write(repo, "checks/code.t", CODE + "  edited\n");
        String honest = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("every difference: rows the code took, with the test file only edited, could not be judged, not tests lost by the change",
                honest.contains("\n        the old tests could not all be judged on the code after the change: there the file as it was gave no clean "
                        + "result, passed or failed, for 3 of them") && !lostSaid(honest) && !honest.contains(NO_RULE) && code == 0);
        // the same rows taken by the code, and the change of the tests switches off A and adds G: as many run on the code after the change as the file
        // as it was runs there, so the fourth run's line speaks of B, C and D. It does not name A: A is said by the line for what no rule judged
        write(repo, "checks/code.t", CODE.replace("A: pass", "A: skip") + "G: pass\n");
        String offBeside = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("every difference: a test switched off beside rows the code took is named, though the fourth run's line speaks for the file's count",
                offBeside.contains("\n        the old tests could not all be judged on the code after the change") && offBeside.contains("\n        the reports of this file as it was "
                        + "and after the change differ in ways " + NO_RULE + ": A (as it was: passed; after the change: skipped).") && !lostSaid(offBeside) && code == 0);
        write(repo, "checks/code.t", CODE);

        // each rule says what it answers for: where it speaks, the line for what no rule judged does not; where it is made to say nothing, that line
        // says what it would have answered for
        String[][] speaks = {
            {"new tests", "checks/judged.t", JUDGED + "idle: pass\n", "idle (as it was: not reported; after the change: passed)"},
            {"skips", "checks/judged.t", JUDGED + "later: skip\n", "later (as it was: not reported; after the change: skipped)"},
            {"gone", "checks/judged.t", JUDGED.replace("stays: pass", "group > stays: pass"), "stays (as it was: passed; after the change: not reported)"},
            // the line of new tests that pass on both sides speaks of the names the file did not have, not of the one it no longer has
            {"gone", "checks/named.t", NAMED.replace("goes: pass\n", "went: pass\nextra: pass\n"), "goes (as it was: passed; after the change: not reported)"},
            {"broken,gone", "checks/broken.t", MENDED.replace("holds: pass", "kept up: pass"), "holds (as it was: passed; after the change: not reported)"},
            {"per test", "checks/failing.t", FAILING + "  edited\n", "throwing (as it was: threw; after the change: passed)"},
            {"lost", "checks/judged.t", JUDGED.replace("turned off: pass", "turned off: skip"), "turned off (as it was: passed; after the change: skipped)"},
        };
        for (String[] e : speaks) {
            String path = e[1], was = Files.readString(repo.resolve(path));
            write(repo, path, e[2]);
            String spoken = report(Map.of("selftest-runner", "true", "runs", "2"));
            Check.mute(java.util.Set.of(e[0].split(",")));
            String silent = report(Map.of("selftest-runner", "true", "runs", "2"));
            Check.mute(java.util.Set.of());
            check("every difference: the rule '" + e[0] + "' says what it answers for, and without it the line for what no rule judged says " + e[3],
                    !spoken.contains(NO_RULE) && silent.contains("      " + path + "  [self test]\n")
                    && silent.contains("\n        the reports of this file as it was and after the change differ in ways " + NO_RULE + ": " + e[3]));
            write(repo, path, was);
        }

        // ordinary work stays quiet: changes that should print no problem and nothing that could not be judged print none, in three runs
        Map<String, String> quiet = new java.util.LinkedHashMap<>();
        quiet.put("checks/broken.t", MENDED);
        quiet.put("checks/named.t", NAMED.replace("refund: pass\ngoes: pass\n", "goes: pass\nrefund: pass\n"));
        quiet.put("checks/judged.t", JUDGED + "  edited\n");
        quiet.put("checks/kept.t", KEPT + "sensitive: needs fixed\n");
        for (Map.Entry<String, String> e : quiet.entrySet()) {
            String was = Files.readString(repo.resolve(e.getKey()));
            write(repo, e.getKey(), e.getValue());
            for (int order = 0; order <= 2; order++) {
                // and where each report lists its cases in another order from run to run, as a build that runs tests concurrently writes them
                Check.order(order);
                String calm = report(Map.of("selftest-runner", "true", "runs", "3"));
                Check.order(0);
                check("every difference: ordinary work in " + e.getKey() + " prints nothing that could not be judged and nothing unstable" + (order == 0 ? "" : ", in report order " + order),
                        !calm.contains("could not be judged") && !calm.contains("from run to run") && code == 0);
            }
            write(repo, e.getKey(), was);
        }

        // a test the change adds throws before the change in every run, another exception in each (a sensitive test beside it makes the repeat): the
        // results do not differ, the reasons do. Could not be judged, with both, never left unsaid
        write(repo, "tests/varied.t", "guard: needs fixed\nlater: error-varies fixed\n");
        String varied = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("every difference: a test that throws another exception before the change in each run could not be judged, with each reason",
                varied.contains("      tests/varied.t  [self test]\n        later: before the change it threw IllegalStateException, not an assertion; in another run, before the "
                        + "change it threw UnsupportedOperationException, not an assertion\n") && varied.contains("\n        guard\n") && code == 0);
        Files.delete(repo.resolve("tests/varied.t"));

        // CR4-2: the same cases in another order are the same answer. Two tests skipped under new names beside one that ran, listed in the second run
        // after the change in the other order: could not be judged, as in one run, and nothing from run to run
        write(repo, "checks/fresh.t", "a: pass\np: skip\nq: skip\n");
        String inOrder = report(Map.of("selftest-runner", "true", "runs", "2"));
        Check.order(1);
        String swapped = report(Map.of("selftest-runner", "true", "runs", "2"));
        Check.order(0);
        check("every difference: tests skipped under new names, reported in another order in a repeat run, could not be judged and are not unstable",
                swapped.contains("2 could not be judged\n      checks/fresh.t  [self test]\n        the file runs 1 of its tests where it ran 1 before the change, and skips 2 where it "
                        + "skipped 0 (skipped under names the file did not report before: p, q)") && !swapped.contains("from run to run") && code == 0 && swapped.equals(inOrder));
        write(repo, "checks/fresh.t", FRESH);
        // every report of a multi-run fixture in another order, within a run and from run to run: the output is the same, byte for byte
        Map<String, String> many = new java.util.LinkedHashMap<>();
        many.put("checks/fresh.t", "a: pass\np: skip\nq: skip\nz: pass\n");
        many.put("checks/judged.t", JUDGED.replace("stays: pass", "group > stays: pass").replace("turned off: pass", "turned off: skip") + "idle: pass\n");
        many.put("checks/broken.t", MENDED.replace("holds: pass", "kept up: pass") + "wavers: pass-once\n");
        many.put("checks/code.t", CODE.replace("A: pass", "A: skip") + "G: pass\n");
        many.put("checks/varies.t", "a: pass\nr: pass-later\n");
        many.put("checks/named.t", NAMED.replace("goes: pass\n", "went: pass\nextra: pass\n"));
        many.put("checks/retry.t", "steady: gone-later fixed\nflaky: skip\n");
        many.put("checks/kept.t", "kept: fails-later fixed\nsensitive: needs fixed\nother: needs fixed\n");
        Map<String, String> kept = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : many.entrySet()) {
            kept.put(e.getKey(), Files.readString(repo.resolve(e.getKey())));
            write(repo, e.getKey(), e.getValue());
        }
        String asListed = report(Map.of("selftest-runner", "true", "runs", "3"));
        int asListedCode = code;
        for (int order = 1; order <= 2; order++) {
            Check.order(order);
            String reordered = report(Map.of("selftest-runner", "true", "runs", "3"));
            Check.order(0);
            check("every difference: every report of " + many.size() + " files in another order (" + order + ") prints the very same output, with the same exit code",
                    reordered.equals(asListed) && code == asListedCode && asListedCode == 1 && asListed.contains("from run to run") && asListed.contains(NO_RULE));
        }
        for (Map.Entry<String, String> e : kept.entrySet()) write(repo, e.getKey(), e.getValue());

        // A line that does not fail the check answers only for what it names, so that another difference beside it is still said. Where the rule
        // for each test speaks, it answers for that other difference; made silent here, the line for what no rule judged must say it instead
        String[][] beside = {
            // the file as it was reported failures, and fewer run: could not be judged, for the names it shows; a test switched on is not among them
            {"lost, failures as it was", "checks/failing.t", "throwing: error-unless fixed\nparams(int)[2]: pass\ntwin: skip\n", "1",
                "a file that did not pass as it was is not said to have lost tests", "the reports of this file as it was and after the change differ in ways " + NO_RULE
                + ": params(int)[2] (as it was: skipped; after the change: passed), throwing (as it was: threw; after the change: passed)."},
            // only a test that passed when run again makes the count: could not be judged, for the names it shows; a test now run again is not among them
            {"lost, only with tests run again", "checks/retry.t", "steady: pass-retried\nflaky: skip\n", "1", "passed 1 more only when the build ran it again",
                "the reports of this file as it was and after the change differ in ways " + NO_RULE + ": steady (as it was: passed; after the change: passed only when the build "
                + "ran it again)."},
            // the skips' answer differs from run to run: unstable, for the tests whose skips or number differ; a test that failed in one run is not among them
            {"skips, an answer that differs", "checks/flip.t", "a: skip\nb 1: fails-later fixed\nb 2: skip-once fixed\n", "2",
                "whether tests that ran before the change are skipped now differs from run to run", "the file was reported differently from run to run, in ways " + NO_RULE
                + ": b 1 (after the change: passed, failed)"},
            // the names gone and new differ in number from run to run: unstable, for those names and the tests whose number differs; a test that failed in
            // one run is not among them
            {"gone, counts that differ", "checks/titles.t", "rate: fails-later fixed\nr: pass-later\n", "2", "is not the same in every run",
                "the file was reported differently from run to run, in ways " + NO_RULE + ": rate (after the change: passed, failed)"},
        };
        for (String[] e : beside) {
            String was = Files.readString(repo.resolve(e[1]));
            write(repo, e[1], e[2]);
            String spoken = report(Map.of("selftest-runner", "true", "runs", e[3]));
            Check.mute(java.util.Set.of("per test"));
            String alone = report(Map.of("selftest-runner", "true", "runs", e[3]));
            Check.mute(java.util.Set.of());
            check("every difference: the line '" + e[0] + "' answers for what it names, and another difference beside it is said: " + e[5],
                    spoken.contains(e[4]) && !spoken.contains(NO_RULE) && alone.contains(e[4]) && alone.contains("      " + e[1] + "  [self test]\n") && alone.contains("\n        " + e[5]));
            write(repo, e[1], was);
        }
        // the skips said nothing in the first run and something in the repeat: unstable, for the tests whose skips or number differ. Here the rule of
        // names gone is made silent too, as it would say the file differs from run to run itself
        write(repo, "checks/judged.t", JUDGED.replace("was off: skip\n", "").replace("stays: pass", "stays: pass-once").replace("goes: pass", "goes: fails-later fixed")
                + "r: pass-later\n");
        Check.mute(java.util.Set.of("gone"));
        String laterOnly = report(Map.of("selftest-runner", "true", "runs", "2"));
        Check.mute(java.util.Set.of("gone", "per test"));
        String laterAlone = report(Map.of("selftest-runner", "true", "runs", "2"));
        Check.mute(java.util.Set.of());
        String unsteadyLine = "\n        whether tests that ran before the change are skipped now differs from run to run: what the file reports, after the change or as it was, is not the "
                + "same in every run\n";
        check("every difference: the line of skips that differ only in a repeat answers for what it names, and a test that failed in one run beside it is said",
                laterOnly.contains(unsteadyLine) && !laterOnly.contains("(after the change: passed, failed)") && laterAlone.contains(unsteadyLine)
                && laterAlone.contains("the file was reported differently from run to run, in ways " + NO_RULE + ": goes (after the change: passed, failed)"));
        write(repo, "checks/judged.t", JUDGED);
    }

    /** A test file of the base whose guard, on the code of the fix, fails at the first try and passes when the runner runs it again (vitest's retry). */
    private static final String GUARDED = "guard: retried-with fixed\n";

    /** A test file of the base with two tests; on the code of the fix the second passes only when the runner runs it again. */
    private static final String R4 = "a: pass\nb: retried-with fixed\n";

    /** A test file of the base with a row the code of the fix no longer gives (b), and a test whose file, as it was, gives no result from the second run on the code of the fix. */
    private static final String CUT = "a: pass\nb: pass-without fixed\nslow: cut-later fixed\n";

    /** A test file of the base whose guard compares the code with what a helper in another test file (pb.t) expects. */
    private static final String PA = "guard: agrees checks/pb.t two\nother: pass\n";

    /** That other test file of the base: its helper does not expect "two". */
    private static final String PB = "steady: pass\n";

    /**
     * The fifth audit: retries a runner does not report (CR5-1), a file run alone before the change
     * whose companions changed (CR5-2), a fourth run that gave no result beside one that did
     * (CR5-3), and a test that passed only when run again in the fourth run (CR5-4).
     */
    private static void retriesAndCompanions(Path repo) throws IOException {
        // CR5-1: the guard replaced by a vacuous test beside a sensitive one. Its old version fails at the first try on the code of the fix and passes
        // when run again: not a clean pass. The fourth run's own line says the old guard could not be judged
        write(repo, "checks/guarded.t", "guard: pass\nnew feature: needs fixed\n");
        String replaced = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("retries: a guard replaced beside a sensitive test, whose old version passes on the change only when run again, is not a clean pass",
                replaced.contains("      checks/guarded.t  [self test]\n        the old tests could not all be judged on the code after the change: there the file as it was gave no clean "
                        + "result, passed or failed, for one of them (not reported, reported more than once, skipped, or run again by the build with another result), so it is not known "
                        + "whether the change broke the old test that passed before it (guard)")
                && replaced.contains("        new feature\n") && code == 0);
        write(repo, "checks/guarded.t", GUARDED);
        // an honest new test that passes before the change only when run again: not said to pass before and after
        write(repo, "tests/honest.t", "fix: retried-without fixed\n");
        String honest = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("retries: a new test that passes before the change only when run again is not said to pass before and after",
                honest.contains("      tests/honest.t  [self test]\n        fix: before the change the build ran it again and got another result\n")
                && !honest.contains("so it does not test the change") && code == 0);
        Files.delete(repo.resolve("tests/honest.t"));
        // a run that does not say which passes were at the first try (a vitest that does not count retries): the file is judged by its failures only,
        // with one line for the file, and a new test that passes on both sides there is not accused
        write(repo, "tests/blind.t", "idle: pass\nalso idle: pass\nretries not said\n");
        String blind = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("retries: a file whose runs do not say which passes were at the first try is not judged by its passes: one line for the file, and the check passes",
                blind.contains("      tests/blind.t  [self test]\n        the stand-in runner does not say whether a test passed only when it ran it again: so it is not known whether a "
                        + "test of the file passed at the first try or only when it was run again, and nothing is said of the file but its failures after the change\n")
                && !blind.contains("so it does not test the change") && !blind.contains("        idle") && code == 0);
        write(repo, "tests/blind.t", "idle: pass\nbroken: fail\nretries not said\n");
        String blindFails = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("retries: a failure after the change in such a file is a failure all the same, and the check fails",
                blindFails.contains("1 fails after the change\n      tests/blind.t  [self test]\n        broken: fails: expected something else\n")
                && blindFails.contains("nothing is said of the file but its failures after the change") && code == 1);
        Files.delete(repo.resolve("tests/blind.t"));
        // the same for an edited file whose old version says it and whose new one does not: a test deleted is said by what no rule judged, not passed in silence
        write(repo, "checks/r4.t", "a: pass\nretries not said\n");
        String blindEdited = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("retries: in an edited file judged by its failures only, a test that is no longer reported is still said",
                blindEdited.contains("nothing is said of the file but its failures after the change") && blindEdited.contains("b (as it was: passed; after the change: not reported)")
                && !lostSaid(blindEdited) && code == 0);
        write(repo, "checks/r4.t", R4);

        // CR5-4: a test deleted beside one that, as it was, passes on the code after the change only when run again: R4 counts what passed at the first
        // try only, so the old file runs no more tests there than the new one: not a definite line
        write(repo, "checks/r4.t", "a: pass\n");
        String r4 = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("R4: a case that passes only when run again in the fourth run is not counted there, so a test deleted beside it is not said definite",
                !lostSaid(r4) && r4.contains("      checks/r4.t  [self test]\n        the old tests could not all be judged on the code after the change") && code == 0);
        write(repo, "checks/r4.t", R4);

        // CR5-3: a row the code took and a test deleted; the fourth run counts as many as run now, and a repeat of it gives no result. Every usable
        // fourth report counts: the one that shows R4 <= R1 keeps it from being definite, in either order, and the run that gave none is said apart
        for (String path : new String[] {"checks/cut.t", "checks/cut2.t"}) {
            String was = Files.readString(repo.resolve(path));
            write(repo, path, "a: pass\nslow: pass\n");
            String mixedR4 = report(Map.of("selftest-runner", "true", "runs", "2"));
            check("R4: a usable fourth run that counts no more than run now keeps the line from being definite, whatever another run of it gave (" + path + ")",
                    !lostSaid(mixedR4) && mixedR4.contains("      " + path + "  [self test]\n        the old tests could not") && code == 0);
            check("R4: and the fourth run that gave no result is said apart (" + path + ")",
                    mixedR4.contains("the file was reported differently from run to run, in ways " + NO_RULE + ": the file as it was, on the code after the change (one run gave no result "
                            + "and another gave one)"));
            write(repo, path, was);
        }
        // where every usable fourth report counts more than run now, the line is definite, and says the run that gave none
        write(repo, "checks/cut.t", "a: pass\n");
        String definite = report(Map.of("selftest-runner", "true", "runs", "2"));
        check("R4: where every usable fourth run counts more than run now, the line is definite and says the run that gave no result",
                definite.contains("at least 2 tests that ran before the change do not run now\n      checks/cut.t  [self test]\n") && definite.contains(". In 1 of the 2 runs of the file "
                        + "as it was on the code after the change it timed out after 900 s; the count there is that of the other runs") && code == 1);
        write(repo, "checks/cut.t", CUT);

        // CR5-2: tests that compare the code with a helper in another changed test file, while a new test file stops the others from compiling
        // before the change, so each is run alone there, beside the other changed test files as the base has them
        write(repo, "prod.txt", "v1\nfixed\ntwo\n");
        write(repo, "checks/pc.t", "new api: compile-needs two\n");
        // a new test that passes before the change alone, beside the old helper, and after beside the new: not a test that passes on both sides
        write(repo, "checks/pb.t", PB + "new case: needs two\n");
        write(repo, "checks/pd.t", "check: agrees checks/pb.t two\n");
        String isolated = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("isolation: a new test that passes on both sides only beside the old helper before and the new after is not said to pass on both sides",
                isolated.contains("      checks/pd.t  [self test]\n        check: after the change it passes only together with the other changed test files, and before the change the "
                        + "file could be run only without them (as the base has them), so what it did there may come from them, not from the change\n")
                && !isolated.contains("so it does not test the change") && isolated.contains("        new case\n") && code == 0);
        Files.delete(repo.resolve("checks/pd.t"));
        // the fifth audit's shape: the guard only renamed, the helper and the code change together, the helper's file gains a sensitive test. The
        // renamed guard is not a test that passes on both sides, and the old guard, which fails on the change only beside the old helper, is not accused
        write(repo, "checks/pa.t", PA.replace("guard:", "renamed guard:"));
        String renamed = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("isolation: a guard only renamed, beside a helper changed with the code, is not accused",
                !renamed.contains("taken out") && renamed.contains("        renamed guard: after the change it passes only together with the other changed test files")
                && renamed.contains("        guard: passed before the change; as the test was, on the code after the change, it fails beside the other edited test files as they were, "
                        + "and passes beside them as the change has them") && code == 0);
        Files.delete(repo.resolve("checks/pc.t"));
        // the fourth run alone: the guard deleted beside the helper's change. It fails as it was on the change only beside the old helper: it is not said
        // to be broken and taken out of the way, and it is a test that ran and does not run now
        write(repo, "checks/pa.t", "other: pass\n");
        String deleted = report(Map.of("selftest-runner", "true", "runs", "1"));
        check("fourth run: an old test that fails on the change only beside the other edited test files as they were is not said to be broken, and its deletion is still said",
                !deleted.contains("taken out") && deleted.contains(L1 + "      checks/pa.t  [self test]\n")
                && deleted.contains("        guard: passed before the change; as the test was, on the code after the change, it fails beside the other edited test files as they were, "
                        + "and passes beside them as the change has them, so its failure there may come from the change of those files, which is part of the change: whether the change "
                        + "broke it is not known\n") && code == 1);
        write(repo, "checks/pa.t", PA);
        write(repo, "checks/pb.t", PB);
        write(repo, "prod.txt", "v1\nfixed\n");
    }

    /** A test file of the base with two nested classes that have a method of one name, the first of which the fix breaks. */
    private static final String PAIR = "demo.Pair$A::works: fails-with fixed\ndemo.Pair$B::works: pass\ndemo.Pair::top: pass\n";

    /** A test file of the base as JUnit 4 reports an ignored class: one skipped case of the class, whatever number of tests it holds. */
    private static final String IGNORED = "demo.Ignored::demo.Ignored: skip\n";

    /** A test file of the base with a test that runs and two cases of one method that are skipped. */
    private static final String FLIP = "a: pass\nb %i: skip\n";

    /** A test file of the base with a class of three tests that pass. */
    private static final String PLAIN = "demo.Plain::first: pass\ndemo.Plain::second: pass\ndemo.Plain::third: pass\n";

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

    /** A test file of the base whose tear-down fails once the code holds "fixed", after its tests ran (a failing afterAll in vitest). */
    private static final String HOOK = "a: pass\nb: pass\nteardown: tears-down-with fixed\n";

    /** A test file of the base with a parameterized test whose first row the fix breaks, that compiles only on the base's code. */
    private static final String GROW = "rows(int)[1]: fails-with fixed\nrows(int)[2]: pass\nloads: compile-needs v1\n";

    /** A test file of the base whose second test, as it is there, is skipped the first time it runs on the code of the fix. */
    private static final String SHY = "steady: pass\nshy: skip-once fixed\n";

    private static int code;

    /** True when a report says of a file that tests that ran before the change do not run now. */
    private static boolean lostSaid(String report) {
        return report.contains(" that ran before the change does not run now\n") || report.contains(" that ran before the change do not run now\n");
    }

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
