package sdlc.selftest;

import static sdlc.selftest.Asserts.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import sdlc.check.Change;
import sdlc.check.Finding;
import sdlc.check.Finding.Level;
import sdlc.check.Integrity;
import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.core.Trusted;
import sdlc.util.Proc;

/** Self test: the integrity scan of sdlc check, one detector at a time, in a repository of its own. */
final class IntegrityTests {
    private IntegrityTests() {}

    private static final String CALC_TEST = """
            package demo;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            class CalcTest {
                @Test
                void adds() {
                    assertEquals(2, 1 + 1);
                    assertEquals(3, 1 + 2);
                }

                @Test
                void subtracts() {
                    assertEquals(0, 1 - 1);
                }

                private int helper() {
                    return 1;
                }
            }
            """;

    private static final String SUM_TEST = """
            import { it, expect } from 'vitest';

            it('adds', () => {
              expect(1 + 1).toBe(2);
            });

            it('subtracts', () => {
              expect(1 - 1).toBe(0);
            });
            """;

    private static final String POM = """
            <project>
              <dependencies>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>32.0</version>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                    </configuration>
                  </plugin>
                  <plugin>
                    <artifactId>maven-deploy-plugin</artifactId>
                    <configuration>
                    </configuration>
                  </plugin>
                  <plugin>
                    <artifactId>jacoco-maven-plugin</artifactId>
                    <configuration>
                      <minimum>0.80</minimum>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    private static final String PACKAGE = """
            {
              "name": "web",
              "scripts": { "test": "vitest run", "build": "tsc" },
              "dependencies": { "lodash": "4.17.20" },
              "devDependencies": { "vitest": "1.0.0" }
            }
            """;

    private static final String VITEST = """
            export default { test: { coverage: { thresholds: { lines: 80, branches: 70 } } } };
            """;

    static void run() throws IOException {
        Path savedRoot = Ctx.root;
        Config savedCfg = Ctx.cfg;
        Path repo = Files.createTempDirectory("sdlc-integritytest").toRealPath();
        try {
            Trusted.env = name -> null;
            setUp(repo);
            tests(repo);
            build(repo);
            notes(repo);
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

    private static void setUp(Path repo) throws IOException {
        write(repo, "src/main/java/demo/Calc.java", "package demo;\nclass Calc {}\n");
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST);
        write(repo, "web/sum.test.ts", SUM_TEST);
        write(repo, "src/test/resources/data.json", "{}\n");
        write(repo, "pom.xml", POM);
        write(repo, "package.json", PACKAGE);
        write(repo, "package-lock.json", "{ \"lockfileVersion\": 3 }\n");
        write(repo, "vitest.config.ts", VITEST);
        write(repo, "README.md", "# demo\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "base");
        Ctx.init(repo);
        check("integrity: nothing changed, nothing found", scan(repo).isEmpty());
    }

    private static void tests(Path repo) throws IOException {
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("helper()", "renamedHelper()"));
        check("integrity: renaming a helper method in a test file reports nothing", scan(repo).isEmpty());
        reset(repo);

        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("    private int helper", """
                    @Test
                    void multiplies() {
                        assertEquals(4, 2 * 2);
                    }

                    private int helper"""));
        check("integrity: an added test with an assertion reports nothing", scan(repo).isEmpty());
        reset(repo);

        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("    private int helper", """
                    @Test
                    void divides() {
                        new Calc();
                    }

                    private int helper"""));
        write(repo, "web/sum.test.ts", SUM_TEST + "\nit('multiplies', () => {\n  const x = 2 * 2;\n});\n");
        List<Finding> empty = scan(repo);
        check("integrity: a new test, Java or script, is not read for its assertions: its run judges it", empty.isEmpty());
        reset(repo);

        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("    @Test\n    void subtracts", "    @Disabled\n    @Test\n    void subtracts"));
        write(repo, "web/sum.test.ts", SUM_TEST.replace("it('adds'", "it.only('adds'"));
        List<Finding> off = scan(repo);
        check("integrity: a test switched off is a problem, reported once", count(off, "test switched off") == 1 && only(off, Level.PROBLEM, "test switched off", "src/test/java/demo/CalcTest.java", "@Disabled"));
        check("integrity: a focused test is a problem", only(off, Level.PROBLEM, "test focused", "web/sum.test.ts", "it.only('adds', () => {"));
        reset(repo);

        write(repo, "src/main/resources/application.properties", "db.password=hunter2hunter2\n");
        check("integrity: a possible secret is a problem", only(scan(repo), Level.PROBLEM, "possible secret", "src/main/resources/application.properties", "(masked)"));
        reset(repo);

        Files.delete(repo.resolve("web/sum.test.ts"));
        Files.delete(repo.resolve("src/test/resources/data.json"));
        List<Finding> deleted = scan(repo);
        check("integrity: a deleted test file is a problem", only(deleted, Level.PROBLEM, "test file deleted", "web/sum.test.ts", "the file is gone"));
        check("integrity: a deleted fixture beside the tests is only a note", only(deleted, Level.NOTE, "test support file deleted", "src/test/resources/data.json", "no test cases"));
        reset(repo);

        // a script file under a test folder, named like no test: whether it held tests is linted from its lines
        write(repo, "web/__tests__/cases.ts", "describe('sum', () => {\n  it('adds', () => { expect(1 + 1).toBe(2) })\n})\n");
        write(repo, "web/__tests__/helpers.ts", "export const two = () => 2;\n// not it('x')\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "script files in a test folder");
        Files.delete(repo.resolve("web/__tests__/cases.ts"));
        Files.delete(repo.resolve("web/__tests__/helpers.ts"));
        List<Finding> goneScripts = scan(repo);
        check("integrity: a deleted script file that calls describe or it is a test file deleted; one that does not is a support file",
                only(goneScripts, Level.PROBLEM, "test file deleted", "web/__tests__/cases.ts", "the file is gone")
                && only(goneScripts, Level.NOTE, "test support file deleted", "web/__tests__/helpers.ts", "no test cases"));
        git(repo, "reset", "-q", "--hard", "HEAD~1");

        // a move is a deletion plus an addition: a note when the file, or every test case it had, is found again
        Files.move(repo.resolve("web/sum.test.ts"), repo.resolve("web/math.test.ts"));
        List<Finding> moved = scan(repo);
        check("integrity: a test file moved as it is is a note, not a problem", problems(moved) == 0 && only(moved, Level.NOTE, "test file moved", "web/sum.test.ts", "web/math.test.ts"));
        reset(repo);
        Files.delete(repo.resolve("src/test/java/demo/CalcTest.java"));
        write(repo, "src/test/java/demo/calc/CalcTest.java", CALC_TEST.replace("package demo;", "package demo.calc;"));
        List<Finding> movedEdited = scan(repo);
        check("integrity: a Java test file moved and edited on the way is a test file deleted", only(movedEdited, Level.PROBLEM, "test file deleted", "src/test/java/demo/CalcTest.java", "the file is gone"));
        reset(repo);

        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("void subtracts()", "void minus()"));
        check("integrity: a Java test renamed is not read from the source: nothing is said (its run judges it)", scan(repo).isEmpty());
        reset(repo);
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replaceFirst("(?s)    @Test\\s+void subtracts\\(\\) \\{.*?\\n    \\}\\n", ""));
        List<Finding> gone = scan(repo);
        check("integrity: nor is a Java test removed: no problem is said (its run judges it)", problems(gone) == 0 && count(gone, "test removed") == 0);
        reset(repo);
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("    private int helper", "    /* flaky */ @Disabled(\"later\") @Test void off() {}\n    private int helper"));
        check("integrity: @Disabled after a comment on the same line is still found", count(scan(repo), "test switched off") == 1);
        reset(repo);
        write(repo, "src/app.test.ts", "it('a', () => { expect(1).toBe(1) })\nconst s = `${it.skip('b', () => {})}`\n");
        check("integrity: it.skip( inside the ${...} of a template string is code", count(scan(repo), "test switched off") == 1);
        Files.delete(repo.resolve("src/app.test.ts"));
        reset(repo);
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("    private int helper", "    // a note about @Disabled and it.skip( in a comment\n    private int helper"));
        check("integrity: @Disabled on a line that is wholly a comment switches nothing off", problems(scan(repo)) == 0);
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("    private int helper", "    String s = \"@Disabled\"; // it.skip(\n    private int helper"));
        check("integrity: inside a string or a trailing comment on a line of code it is reported: the lint errs on the safe side",
                only(scan(repo), Level.PROBLEM, "test switched off", "src/test/java/demo/CalcTest.java", "String s = \"@Disabled\"; // it.skip("));
        reset(repo);
        write(repo, "pom.xml", POM.replace("</project>", "<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration><skip>true</skip></configuration></plugin></plugins></build>\n<properties><skipTests>\n  true\n</skipTests></properties></project>"));
        List<Finding> compact = scan(repo);
        check("integrity: skip settings are found in a pom whatever its line breaks", count(compact, "tests skipped by the build") == 2
                && compact.stream().anyMatch(f -> f.detail().startsWith("Surefire is told to skip: <skip>true</skip> in build, plugin maven-surefire-plugin"))
                && compact.stream().anyMatch(f -> f.detail().equals("<skipTests>true</skipTests> in properties")));
        reset(repo);
        String inProfile = POM.replace("</project>", "<profiles><profile><id>quick</id><properties><skipTests>true</skipTests></properties></profile></profiles></project>");
        write(repo, "pom.xml", inProfile);
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "a profile that skips tests");
        write(repo, "pom.xml", inProfile.replace("</project>", "<properties><skipTests>true</skipTests></properties></project>"));
        check("integrity: the same skip setting added outside the profile that already had it is a new setting",
                scan(repo).stream().anyMatch(f -> f.level() == Level.PROBLEM && f.detail().equals("<skipTests>true</skipTests> in properties")));
        git(repo, "reset", "-q", "--hard", "HEAD~1");
        write(repo, "package.json", "{\"scripts\": {\"lint\": \"" + "x".repeat(5000) + "\", \"test\": \"vitest run || true\"}}\n");
        check("integrity: what follows a very long stretch of a line is still scanned", count(scan(repo), "test failures ignored by the build") >= 1);
        write(repo, "package.json", "{\"scripts\": {\"test\": \"vitest run" + " ".repeat(5000) + "|| true\"}}\n");
        check("integrity: a test command and the || true that hides it are found however far apart", count(scan(repo), "test failures ignored by the build") == 1);
        write(repo, "package.json", "{\"scripts\": {\"test\": \"env A=" + "x".repeat(2500) + " npm test -- " + "y".repeat(2500) + " || true\"}}\n");
        check("integrity: also when the test command sits in the middle of a long command", count(scan(repo), "test failures ignored by the build") == 1);
        write(repo, "package.json", "{\"scripts\": {\"x\": \"" + "a || true; ".repeat(20000) + "\"}}\n");
        long began = System.nanoTime();
        scan(repo);
        check("integrity: twenty thousand || true on one line are walked once", (System.nanoTime() - began) / 1_000_000 < 3000);
        reset(repo);
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("    private int helper", "    /* flaky,\n     * for now */ @Disabled @Test void off() {}\n    private int helper"));
        check("integrity: @Disabled after the end of a block comment is still found", count(scan(repo), "test switched off") == 1);
        reset(repo);
        write(repo, "src/app.test.ts", "it('a', () => { expect(1).toBe(1) })\nconst s = `${fn({a: 1}, it.skip('b', () => {}))}`\n");
        check("integrity: it.skip( after a nested brace inside ${...} is still found", count(scan(repo), "test switched off") == 1);
        write(repo, "src/app.test.ts", "it('a', () => { expect(1).toBe(1) })\nconst s = `${fn(\"}\", it.skip('b', () => {}))}`\n");
        check("integrity: a closing brace inside a string does not end the ${...}", count(scan(repo), "test switched off") == 1);
        write(repo, "src/app.test.ts", "it('a', () => { expect(1).toBe(1) })\nconst s = `${\"it.skip(\"}`\n");
        check("integrity: the text it.skip( inside a string inside ${...} is reported: the lint errs on the safe side", count(scan(repo), "test switched off") == 1);
        write(repo, "src/app.test.ts", "it('old name', () => {\n  expect(1 + 1).toBe(2)\n})\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "a script test");
        write(repo, "src/app.test.ts", "it('new name', () => {\n  expect(1 + 1).toBe(2)\n})\n");
        check("integrity: a script test renamed is not read from the source: nothing is said (its run judges it)", scan(repo).isEmpty());
        write(repo, "src/app.test.ts", "it('outer > old name', () => {\n  expect(1 + 1).toBe(2)\n})\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "a name with a separator");
        // a switch that no single line shows
        write(repo, "src/app.test.ts", "it('outer > old name', () => {\n  expect(1 + 1).toBe(2)\n})\nit\n  .skip('later', () => {\n  expect(1).toBe(1)\n})\n");
        check("integrity: a script switch written over two added lines is found by the line lint", count(scan(repo), "test switched off") == 1);
        write(repo, "src/app.test.ts", "it('outer > old name', () => {\n  expect(1 + 1).toBe(2)\n})\nit.concurrent.skip('later', () => {\n  expect(1).toBe(1)\n})\n");
        check("integrity: it.concurrent.skip is a test switched off", count(scan(repo), "test switched off") == 1);
        write(repo, "src/app.test.ts", "it('outer > old name', () => {\n  expect(1 + 1).toBe(2)\n})\n// it\n//   .skip('in a comment')\n# it.skip('nor a comment of a script')\n");
        check("integrity: the same words on lines that are wholly comments switch nothing off", problems(scan(repo)) == 0);
        write(repo, "src/app.test.ts", "it('outer > old name', () => {\n  expect(1 + 1).toBe(2)\n})\nconst example = `\nit\n  .skip('documentation example')\n`;\n");
        check("integrity: the line lint does not know a template string that runs over lines: a switch over two of its lines is reported (it errs on the safe side)", count(scan(repo), "test switched off") == 1);
        // the line lint of scripts: a skip on a line the change adds is reported as that line, whatever the change did with the test
        write(repo, "src/app.test.ts", "it('outer > old name', () => {\n  expect(1 + 1).toBe(2)\n})\nit('later', () => {})\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "a later test");
        write(repo, "src/app.test.ts", "it('outer > old name', () => {\n  expect(1 + 1).toBe(2)\n})\nit.skip('later', () => {})\n");
        check("integrity: a script skip on a line the change adds is found, named by the line",
                count(scan(repo), "test switched off") == 1 && only(scan(repo), Level.PROBLEM, "test switched off", "src/app.test.ts", "it.skip('later', () => {})"));
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "a skipped test");
        write(repo, "src/app.test.ts", "it.skip('later', () => {})\nit('outer > old name', () => {\n  expect(1 + 1).toBe(2)\n})\n");
        check("integrity: a skipped script test moved as a whole is not analysed: its added line is reported as a switch",
                count(scan(repo), "test switched off") == 1 && only(scan(repo), Level.PROBLEM, "test switched off", "src/app.test.ts", "it.skip('later', () => {})"));
        write(repo, "src/app.test.ts", "it('outer > old name', () => {\n  expect(1 + 1).toBe(2)\n})\nit.skip(\"sooner\", () => {})\n");
        check("integrity: nor is a skipped script test given another title or other quotes: its line is reported",
                count(scan(repo), "test switched off") == 1 && only(scan(repo), Level.PROBLEM, "test switched off", "src/app.test.ts", "it.skip(\"sooner\", () => {})"));
        write(repo, "src/app.test.ts", "it('outer > old name', () => {\n  expect(1 + 1).toBe(2)\n})\nit.skip('later', () => { done() })\n");
        check("integrity: a script test edited on a line that keeps its skip is reported too: the lint reads lines, not tests",
                count(scan(repo), "test switched off") == 1);
        write(repo, "src/app.test.ts", "it('outer > old name', () => {\n  expect(1 + 1).toBe(2)\n})\nit.skip('later', () => {})\n// later\n");
        check("integrity: a skip on a line the change did not write is not reported again", count(scan(repo), "test switched off") == 0
                && scan(repo).stream().noneMatch(f -> f.kind().equals("could not be read with confidence") || f.kind().equals("switch in a comment or a string")));
        git(repo, "reset", "-q", "--hard", "HEAD~2");
        // a test taken out of a script file: the reading does not say it; the run of the file does
        write(repo, "src/app.test.ts", "describe('A', () => {\n  it('works', () => { check(1) })\n})\ndescribe('B', () => {\n  it('works', () => { check(2) })\n})\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "two groups");
        write(repo, "src/app.test.ts", "describe('A', () => {\n  it('works', () => { check(1) })\n})\n");
        check("integrity: a script test removed is not read from the source: nothing is said (its run judges it)", scan(repo).isEmpty());
        git(repo, "reset", "-q", "--hard", "HEAD~1");
        // other test files are linted by the lines the change adds, as scripts are
        write(repo, "tests/test_calc.py", "import pytest\n\n@pytest.mark.skip(reason=\"later\")\ndef test_adds():\n    assert 1 + 1 == 2\n");
        check("integrity: a skip in a Python test file is found by the line lint", only(scan(repo), Level.PROBLEM, "test switched off", "tests/test_calc.py", "@pytest.mark.skip(reason="));
        Files.delete(repo.resolve("tests/test_calc.py"));
        // a folder the repository itself names as tests (paths.test), where the built-in idea of a test file does not reach
        write(repo, "specs/foo.ts", "it('a', () => { expect(1).toBe(1) })\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "a test in a folder of the repository's own");
        write(repo, "specs/foo.ts", "it.skip('a', () => { expect(1).toBe(1) })\n");
        Config before = Ctx.cfg;
        Ctx.cfg = Config.parse("stack: node\npaths:\n  test: [\"specs/**\"]\n");
        try {
            check("integrity: a test switched off in a folder the repository names as tests is found", only(scan(repo), Level.PROBLEM, "test switched off", "specs/foo.ts", "it.skip('a', () =>"));
        } finally {
            Ctx.cfg = before;
        }
        git(repo, "reset", "-q", "--hard", "HEAD~1");
        // Java test files are linted by the lines the change adds, as every other test file is
        check("integrity: the lint finds @Disabled, @Ignore and an assumption that never holds on added Java lines",
                Integrity.lintedSwitches(List.of("  @Disabled(\"flaky\")", "  @org.junit.Ignore", "    assumeTrue(false, \"later\");", "    Assume.assumeTrue(\"later\", false);",
                        "  @ org.junit.jupiter.api . Disabled", "    assumeFalse(true);", "    Assumptions.abort(\"later\");"))
                        .equals(List.of("@Disabled(\"flaky\")", "@org.junit.Ignore", "assumeTrue(false, \"later\");", "Assume.assumeTrue(\"later\", false);",
                                "@ org.junit.jupiter.api . Disabled", "assumeFalse(true);", "Assumptions.abort(\"later\");")));
        check("integrity: an annotation written over two added lines is found, joined",
                Integrity.lintedSwitches(List.of("  @", "  Disabled", "  @Test void later() {}")).equals(List.of("@ Disabled")));
        check("integrity: an abort with type arguments, imported, or called alone is found; another object's abort is not",
                Integrity.lintedSwitches(List.of("    Assumptions.<Void>abort(\"later\");", "    Assumptions . <Void> abort(\"later\");", "import static org.junit.jupiter.api.Assumptions.abort;",
                        "    abort(\"later\");", "    Assumptions.abort<Unit>(\"later\")", "    controller.abort();", "    new AbortController().abort();")).size() == 5
                && !Integrity.lintedSwitch("    controller.abort();") && Integrity.lintedSwitch("    Assumptions.<Void>abort(\"later\");"));
        // L1-2: a line cannot tell its quotes for certain; what it shows as written is never hidden by the masking
        check("integrity: a quote inside a regular expression does not hide a switch on the same line",
                Integrity.lintedSwitch("const r = /'/; it.skip('a', () => { expect(false).toBe(true); }); const x = 'ok';")
                && Integrity.lintedSwitch("const r = /x\\//; it.skip('b', () => {})")
                && Integrity.lintedSwitch("* 2; it.skip('c', () => {})")
                && Integrity.lintedSwitch("`; it.skip('d', () => {}); const y = `")
                && Integrity.lintedSwitch("#f = it.skip('e', () => {})")
                && Integrity.lintedSwitch("    String t = \"\"\"; @Disabled class X {} //\"")
                && Integrity.lintedSwitch("@/* a */Disabled"));
        check("integrity: lines wholly comments are not read as written; others are",
                !Integrity.lintedSwitch("  // @Disabled until PAY-12") && !Integrity.lintedSwitch("# @pytest.mark.skip(reason=\"x\")")
                && Integrity.lintedSwitch("  * @Disabled until PAY-12") && Integrity.lintedSwitch("assertTrue(ok); // it.skip later"));
        check("integrity: a switch over two lines, with a quote in a regular expression on one and a comment between its words on the other, is found",
                Integrity.lintedSwitches(List.of("const r = /'/; it", "/**/.skip('a', () => {})")).equals(List.of("const r = /'/; it /**/.skip('a', () => {})")));
        check("integrity: an assumption with a matcher or a computed condition is not a switch",
                Integrity.lintedSwitches(List.of("    Assume.assumeThat(false, is(false));", "    assumeTrue(ready(false));", "    @DisplayName(\"x\")")).isEmpty());
        // L1-1: an unconditional assumption put beside a conditional one is a line the change adds
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("        assertEquals(0, 1 - 1);", "        if (Boolean.getBoolean(\"skip.integration\")) assumeTrue(false);\n        assertEquals(0, 1 - 1);"));
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "a conditional assumption");
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("        assertEquals(0, 1 - 1);", "        if (Boolean.getBoolean(\"skip.integration\")) assumeTrue(false);\n        assumeTrue(false);\n        assertEquals(0, 1 - 1);"));
        check("integrity: a second assumption that never holds, beside a conditional one, is found by its added line",
                only(scan(repo), Level.PROBLEM, "test switched off", "src/test/java/demo/CalcTest.java", "assumeTrue(false);") && count(scan(repo), "test switched off") == 1);
        git(repo, "reset", "-q", "--hard", "HEAD~1");
        // L1-4: overloads that change places. The diff shows one of them moved: its lines are added lines. When that is the one
        // switched off, its @Disabled line is reported (the lint reads lines, not tests); when it is the other one, nothing is.
        String shortOff = "class OrderTest {\n    @Disabled\n    @Test\n    void a() {}\n\n    @ParameterizedTest\n    @ValueSource(strings = {\"x\", \"y\"})\n    void a(String s) {\n        assertNotNull(s);\n        assertFalse(s.isEmpty());\n    }\n}\n";
        String shortOffSwapped = "class OrderTest {\n    @ParameterizedTest\n    @ValueSource(strings = {\"x\", \"y\"})\n    void a(String s) {\n        assertNotNull(s);\n        assertFalse(s.isEmpty());\n    }\n\n    @Disabled\n    @Test\n    void a() {}\n}\n";
        String longOff = shortOff.replace("    @Disabled\n    @Test\n    void a() {}\n", "").replace("    @ParameterizedTest\n", "    @Disabled\n    @ParameterizedTest\n")
                .replace("class OrderTest {\n", "class OrderTest {\n    @Test\n    void a() {}\n");
        String longOffSwapped = "class OrderTest {\n    @Disabled\n    @ParameterizedTest\n    @ValueSource(strings = {\"x\", \"y\"})\n    void a(String s) {\n        assertNotNull(s);\n        assertFalse(s.isEmpty());\n    }\n    @Test\n    void a() {}\n}\n";
        write(repo, "src/test/java/demo/OrderTest.java", shortOff);
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "overloads");
        write(repo, "src/test/java/demo/OrderTest.java", shortOffSwapped);
        check("integrity: overloads that change places, where the diff shows the switched-off one moved: its @Disabled line is reported",
                only(scan(repo), Level.PROBLEM, "test switched off", "src/test/java/demo/OrderTest.java", "@Disabled"));
        write(repo, "src/test/java/demo/OrderTest.java", longOff);
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "overloads, the other one off");
        write(repo, "src/test/java/demo/OrderTest.java", longOffSwapped);
        check("integrity: overloads that change places, where the diff shows the other one moved: nothing is reported", problems(scan(repo)) == 0);
        git(repo, "reset", "-q", "--hard", "HEAD~2");
        Config own = Ctx.cfg;
        Ctx.cfg = Config.parse("stack: node\ntests:\n  disabledPatterns: [\"testWithDb\\\\.skip\", \"\\\\b(it|test)\\\\.skip\\\\(\"]\n");
        try {
            check("integrity: a repository's own switch on a line that also holds a built-in one is still the repository's to judge",
                    sdlc.workflow.Checks.containsDisabled(sdlc.check.Integrity.withoutBuiltInSwitches("it.skip('legacy', a); testWithDb.skip('critical', b);"))
                    && !sdlc.workflow.Checks.containsDisabled(sdlc.check.Integrity.withoutBuiltInSwitches("it.skip('legacy', a); testWithDb('critical', b);")));
        } finally {
            Ctx.cfg = own;
        }
        git(repo, "reset", "-q", "--hard", "HEAD~2");
        reset(repo);
        write(repo, "package.json", "{\"scripts\": {\"test\": \"vitest run" + " ".repeat(20000) + "|| true\"}}\n");
        long started = System.nanoTime();
        scan(repo);
        check("integrity: a very long line does not keep the scan busy", (System.nanoTime() - started) / 1_000_000 < 3000);
        reset(repo);

        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("        assertEquals(3, 1 + 2);\n", ""));
        check("integrity: fewer assertions in an edited test file is a note", only(scan(repo), Level.NOTE, "fewer assertions", "src/test/java/demo/CalcTest.java", "3 assertions before, 2 after"));
        reset(repo);
    }

    private static void build(Path repo) throws IOException {
        write(repo, "pom.xml", POM.replaceFirst("<configuration>\n", "<configuration>\n          <skipTests>true</skipTests>\n          <testFailureIgnore>true</testFailureIgnore>\n"));
        List<Finding> pom = scan(repo);
        check("integrity: skipTests in the pom is a problem", only(pom, Level.PROBLEM, "tests skipped by the build", "pom.xml", "<skipTests>true</skipTests>"));
        check("integrity: testFailureIgnore in the pom is a problem", only(pom, Level.PROBLEM, "test failures ignored by the build", "pom.xml", "<testFailureIgnore>"));
        reset(repo);

        write(repo, "pom.xml", POM.replace("<configuration>\n", "<configuration>\n          <skip>true</skip>\n"));
        List<Finding> skip = scan(repo);
        check("integrity: <skip> in Surefire is a problem, in another plugin it is not", count(skip, "tests skipped by the build") == 1 && only(skip, Level.PROBLEM, "tests skipped by the build", "pom.xml", "Surefire"));
        reset(repo);

        write(repo, "package.json", PACKAGE.replace("\"vitest run\"", "\"vitest run --passWithNoTests\""));
        write(repo, "Jenkinsfile", "pipeline { stages { stage('b') { steps { sh './mvnw -B verify -DskipTests' } } } }\n");
        write(repo, ".github/workflows/ci.yml", "jobs:\n  t:\n    steps:\n      - run: npm test || true\n      - run: rm -rf coverage || true\n      - run: ./mvnw verify -DskipTests=false\n");
        write(repo, "README.md", "# demo\nBuild without tests: ./mvnw verify -DskipTests\n");
        List<Finding> flags = scan(repo);
        check("integrity: --passWithNoTests in a script is a problem", only(flags, Level.PROBLEM, "tests skipped by the build", "package.json", "finds no tests"));
        check("integrity: -DskipTests in a pipeline is a problem", only(flags, Level.PROBLEM, "tests skipped by the build", "Jenkinsfile", "-DskipTests"));
        check("integrity: || true after a test command is a problem, after another command it is not",
                count(flags, "test failures ignored by the build") == 1 && only(flags, Level.PROBLEM, "test failures ignored by the build", ".github/workflows/ci.yml", "npm test || true"));
        check("integrity: -DskipTests=false and the same flag in a README are not", flags.stream().noneMatch(f -> f.path().equals("README.md"))
                && count(flags, "tests skipped by the build") == 2);
        check("integrity: a pipeline change is a note", only(flags, Level.NOTE, "pipeline changed", "Jenkinsfile", "added") && only(flags, Level.NOTE, "pipeline changed", ".github/workflows/ci.yml", "added"));
        check("integrity: a script change in package.json names the script", only(flags, Level.NOTE, "build configuration changed", "package.json", "scripts changed: test"));
        boolean ordered = true;
        for (int i = 1; i < flags.size(); i++) ordered &= !(flags.get(i - 1).level() == Level.NOTE && flags.get(i).level() == Level.PROBLEM);
        check("integrity: problems come before notes", ordered);
        check("integrity: every detail is one line of at most 140 characters", flags.stream().allMatch(f -> f.detail().length() <= 140 && !f.detail().contains("\n")));
        reset(repo);
    }

    private static void notes(Path repo) throws IOException {
        write(repo, "pom.xml", POM.replace("<version>32.0</version>", "<version>33.0</version>"));
        List<Finding> bump = scan(repo);
        check("integrity: a dependency version bump is shown once", count(bump, "dependency changed") == 1 && count(bump, "dependency added") == 0 && count(bump, "dependency removed") == 0
                && only(bump, Level.NOTE, "dependency changed", "pom.xml", "guava: 32.0 to 33.0"));
        check("integrity: and the pom is build configuration", only(bump, Level.NOTE, "build configuration changed", "pom.xml", "edited") && problems(bump) == 0);
        reset(repo);

        write(repo, "package.json", PACKAGE.replace("\"lodash\": \"4.17.20\"", "\"lodash\": \"4.17.21\", \"axios\": \"1.6.0\"").replace("{ \"vitest\": \"1.0.0\" }", "{}"));
        List<Finding> npm = scan(repo);
        check("integrity: package.json dependencies are compared by name and version",
                only(npm, Level.NOTE, "dependency changed", "package.json", "lodash (dependencies): 4.17.20 to 4.17.21")
                && only(npm, Level.NOTE, "dependency added", "package.json", "axios (dependencies) 1.6.0")
                && only(npm, Level.NOTE, "dependency removed", "package.json", "vitest (devDependencies) 1.0.0"));
        check("integrity: a package.json whose scripts did not change is not build configuration", count(npm, "build configuration changed") == 0);
        reset(repo);

        write(repo, "package-lock.json", "{ \"lockfileVersion\": 3, \"packages\": {} }\n");
        check("integrity: a lock file changed alone is a note", only(scan(repo), Level.NOTE, "lock file changed without its manifest", "package-lock.json", "package.json"));
        reset(repo);

        write(repo, "src/main/resources/db/migration/V2__add_column.sql", "alter table t add column c int;\n");
        check("integrity: a migration is a note", only(scan(repo), Level.NOTE, "database migration changed", "src/main/resources/db/migration/V2__add_column.sql", "added"));
        reset(repo);

        write(repo, "pom.xml", POM.replace("<minimum>0.80</minimum>", "<minimum>0.60</minimum>"));
        write(repo, "vitest.config.ts", VITEST.replace("lines: 80, branches: 70", "lines: 60, branches: 75"));
        List<Finding> cover = scan(repo);
        check("integrity: a lowered JaCoCo minimum is a note", only(cover, Level.NOTE, "coverage threshold lowered", "pom.xml", "0.8 to 0.6"));
        check("integrity: a lowered vitest threshold is a note, a raised one is not", count(cover, "coverage threshold lowered") == 2
                && only(cover, Level.NOTE, "coverage threshold lowered", "vitest.config.ts", "lines coverage 80 to 60"));
        reset(repo);

        write(repo, "CLAUDE.md", "# rules\n");
        write(repo, ".github/CODEOWNERS", "* @someone\n");
        List<Finding> steer = scan(repo);
        check("integrity: files that steer agents and reviews are notes", only(steer, Level.NOTE, "agent or review instructions changed", "CLAUDE.md", "added")
                && only(steer, Level.NOTE, "agent or review instructions changed", ".github/CODEOWNERS", "added") && problems(steer) == 0);
        reset(repo);
    }

    private static List<Finding> scan(Path repo) {
        return Integrity.scan(Change.of(null));
    }

    /** Exactly one finding of this kind on this path, at this level, whose detail contains the text. */
    private static boolean only(List<Finding> all, Level level, String kind, String path, String detail) {
        List<Finding> hits = all.stream().filter(f -> f.kind().equals(kind) && f.path().equals(path)).toList();
        boolean ok = hits.size() == 1 && hits.get(0).level() == level && hits.get(0).detail().contains(detail);
        if (!ok) System.out.println("    integrity findings: " + all);
        return ok;
    }

    private static long count(List<Finding> all, String kind) {
        return all.stream().filter(f -> f.kind().equals(kind)).count();
    }

    private static long problems(List<Finding> all) {
        return all.stream().filter(f -> f.level() == Level.PROBLEM).count();
    }

    private static void reset(Path repo) {
        git(repo, "reset", "-q", "--hard");
        git(repo, "clean", "-fdq");
    }

    private static void write(Path repo, String rel, String text) throws IOException {
        Path f = repo.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private static void git(Path repo, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Proc p = Proc.run(repo, cmd);
        if (!p.ok()) throw new IllegalStateException("git " + String.join(" ", args) + ": " + p.err());
    }
}
