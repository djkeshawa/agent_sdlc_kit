package sdlc.selftest;

import static sdlc.selftest.Asserts.check;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import sdlc.check.Change;
import sdlc.check.Check;
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
            callers(repo);
            deletedCost(repo);
            movedCost(repo);
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
        write(repo, "web/__tests__/helpers.ts", "export const two = () => 2; // not it('x')\n");
        write(repo, "web/__tests__/util.ts", "export const three = () => 3;\n");
        write(repo, "src/test/resources/Sample.java.txt", "class Sample {\n    @Test void x() {}\n}\n");
        write(repo, "specs/cases.ts", "it('a', () => { expect(1).toBe(1) })\n");
        write(repo, "web/__tests__/example.ts", "/*\nit('example')\n*/\nexport const value = 1;\n");
        write(repo, "web/__tests__/doc.ts", "export const doc = `\nit('example', () => {})\n`;\n");
        write(repo, "web/__tests__/real.ts", "/* the cases */\nconst s = `x`;\ndescribe('real', () => {\n  it('a', () => {})\n})\n");
        write(repo, "src/test/java/demo/Samples.java", "package demo;\n\nclass Samples {\n    static final String EXAMPLE = \"\"\"\n        @Test void example() {}\n        \"\"\";\n}\n");
        write(repo, "src/test/java/demo/Checked.java", "package demo;\n\nclass Checked {\n    static final String EXAMPLE = \"\"\"\n        text\n        \"\"\";\n    @Test void real() {}\n}\n");
        write(repo, "tests/helpers.py", "EXAMPLE = '''\ndef test_example():\n    assert True\n'''\n\n\ndef helper():\n    \"\"\"A helper.\n\n    def test_doc(): ...\n    \"\"\"\n    return 1\n");
        write(repo, "tests/checks.py", "\"\"\"Shared checks.\"\"\"\n\n\ndef test_real():\n    assert True\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "script files in a test folder");
        Files.delete(repo.resolve("web/__tests__/cases.ts"));
        Files.delete(repo.resolve("web/__tests__/helpers.ts"));
        Files.delete(repo.resolve("web/__tests__/util.ts"));
        Files.delete(repo.resolve("src/test/resources/Sample.java.txt"));
        Files.delete(repo.resolve("specs/cases.ts"));
        Files.delete(repo.resolve("web/__tests__/example.ts"));
        Files.delete(repo.resolve("web/__tests__/doc.ts"));
        Files.delete(repo.resolve("web/__tests__/real.ts"));
        Files.delete(repo.resolve("src/test/java/demo/Samples.java"));
        Files.delete(repo.resolve("src/test/java/demo/Checked.java"));
        Files.delete(repo.resolve("tests/helpers.py"));
        Files.delete(repo.resolve("tests/checks.py"));
        List<Finding> goneScripts = scan(repo);
        // audit round 4, finding 5: a Java text block or a Python string in three quotes is text over its lines; a test after one is code
        check("integrity: a deleted helper whose example stands in a Java text block or a Python string in three quotes is a note",
                only(goneScripts, Level.NOTE, "test support file deleted", "src/test/java/demo/Samples.java", "only a comment or a string in it mentions a test")
                && only(goneScripts, Level.NOTE, "test support file deleted", "tests/helpers.py", "only a comment or a string in it mentions a test"));
        check("integrity: a test declared after a text block or a docstring that closes is a test file deleted",
                only(goneScripts, Level.PROBLEM, "test file deleted", "src/test/java/demo/Checked.java", "the file is gone")
                && only(goneScripts, Level.PROBLEM, "test file deleted", "tests/checks.py", "the file is gone"));
        check("integrity: a deleted helper whose example stands in a comment or a template string over several lines is a note; real tests after them are found",
                only(goneScripts, Level.NOTE, "test support file deleted", "web/__tests__/example.ts", "only a comment or a string in it mentions a test")
                && only(goneScripts, Level.NOTE, "test support file deleted", "web/__tests__/doc.ts", "only a comment or a string in it mentions a test")
                && only(goneScripts, Level.PROBLEM, "test file deleted", "web/__tests__/real.ts", "the file is gone"));
        check("integrity: a deleted script file whose code calls describe or it is a test file deleted; one that does not is a support file",
                only(goneScripts, Level.PROBLEM, "test file deleted", "web/__tests__/cases.ts", "the file is gone")
                && only(goneScripts, Level.NOTE, "test support file deleted", "web/__tests__/util.ts", "no test cases"));
        check("integrity: a deleted helper whose only test call is in a comment is a note, and so is a fixture that holds sample source",
                only(goneScripts, Level.NOTE, "test support file deleted", "web/__tests__/helpers.ts", "only a comment or a string in it mentions a test")
                && only(goneScripts, Level.NOTE, "test support file deleted", "src/test/resources/Sample.java.txt", "no test cases"));
        check("integrity: outside the configured test paths a deleted file is not a test file", goneScripts.stream().noneMatch(f -> f.path().equals("specs/cases.ts")));
        Config plain = Ctx.cfg;
        Ctx.cfg = Config.parse("stack: node\npaths:\n  test: [\"specs/**\"]\n");
        try {
            check("integrity: a deleted test file in a folder the repository names as tests (paths.test) is a test file deleted",
                    only(scan(repo), Level.PROBLEM, "test file deleted", "specs/cases.ts", "the file is gone"));
        } finally {
            Ctx.cfg = plain;
        }
        git(repo, "reset", "-q", "--hard", "HEAD~1");

        // a move is a deletion plus an addition: a note when the file, or every test case it had, is found again
        Files.move(repo.resolve("web/sum.test.ts"), repo.resolve("web/math.test.ts"));
        List<Finding> moved = scan(repo);
        check("integrity: a test file moved as it is is a note, not a problem", problems(moved) == 0 && only(moved, Level.NOTE, "test file moved", "web/sum.test.ts", "web/math.test.ts"));
        reset(repo);
        Files.delete(repo.resolve("src/test/java/demo/CalcTest.java"));
        write(repo, "src/test/java/demo/calc/CalcTest.java", CALC_TEST.replace("package demo;", "package demo.calc;"));
        List<Finding> repackaged = scan(repo);
        check("integrity: a Java test class moved to another package, only its package line changed, is a move: a note",
                problems(repackaged) == 0 && only(repackaged, Level.NOTE, "test file moved", "src/test/java/demo/CalcTest.java", "src/test/java/demo/calc/CalcTest.java, in another package"));
        write(repo, "src/test/java/demo/calc/CalcTest.java", CALC_TEST.replace("package demo;", "package demo.calc;").replace("import org.junit.jupiter.api.Test;", "import org.junit.jupiter.api.Test;\nimport demo.Calc;"));
        List<Finding> reimported = scan(repo);
        check("integrity: moved with its imports changed is a note too: its run judges it",
                problems(reimported) == 0 && only(reimported, Level.NOTE, "test file moved and its imports changed", "src/test/java/demo/CalcTest.java", "its run judges it"));
        write(repo, "src/test/java/demo/calc/CalcTest.java", CALC_TEST.replace("package demo;", "package demo.calc;").replace("assertEquals(0, 1 - 1);", "assertEquals(0, 0);"));
        check("integrity: a Java test file moved and edited in its body on the way is a test file deleted",
                only(scan(repo), Level.PROBLEM, "test file deleted", "src/test/java/demo/CalcTest.java", "the file is gone"));
        reset(repo);
        // one rule with the run (Integrity.moves): an added file is the move of one deleted file at most, and the other way round
        write(repo, "web/copy.test.ts", SUM_TEST);
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "a copy");
        Files.delete(repo.resolve("web/sum.test.ts"));
        Files.delete(repo.resolve("web/copy.test.ts"));
        write(repo, "web/all/sum.test.ts", SUM_TEST);
        List<Finding> twoGone = scan(repo);
        check("integrity: of two deleted copies of a test file and one added, the first by path moved and the second is a test file deleted",
                only(twoGone, Level.NOTE, "test file moved", "web/copy.test.ts", "the same file is now at web/all/sum.test.ts")
                && only(twoGone, Level.PROBLEM, "test file deleted", "web/sum.test.ts", "the file is gone") && count(twoGone, "test file moved") == 1);
        git(repo, "reset", "-q", "--hard", "HEAD~1");
        reset(repo);
        Files.delete(repo.resolve("web/sum.test.ts"));
        write(repo, "web/a/sum.test.ts", SUM_TEST);
        write(repo, "web/b/sum.test.ts", SUM_TEST);
        List<Finding> twoAdded = scan(repo);
        check("integrity: one deleted test file and two added copies of it: one move, said once, and nothing deleted",
                only(twoAdded, Level.NOTE, "test file moved", "web/sum.test.ts", "the same file is now at web/a/sum.test.ts") && problems(twoAdded) == 0);
        reset(repo);
        // the exact lines first: a JVM class deleted in two packages, one added copy as the first was and one in a third package
        Files.delete(repo.resolve("src/test/java/demo/CalcTest.java"));
        write(repo, "src/test/java/demo/b/CalcTest.java", CALC_TEST.replace("package demo;", "package demo.calc;"));
        write(repo, "src/test/java/demo/a/CalcTest.java", CALC_TEST);
        List<Finding> tiers = scan(repo);
        check("integrity: a deleted file pairs with the added file of exactly its lines before one that differs in its package line",
                only(tiers, Level.NOTE, "test file moved", "src/test/java/demo/CalcTest.java", "the same file is now at src/test/java/demo/a/CalcTest.java") && problems(tiers) == 0
                && !tiers.toString().contains("in another package"));
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
        // audit round 5: an annotation right after a quote is not the whole annotation (it stands in a string): a note, never sure
        check("integrity: inside a string or a trailing comment on a line of code it is reported as a note: the lint errs on the safe side without blocking",
                only(scan(repo), Level.NOTE, "may switch a test off", "src/test/java/demo/CalcTest.java", "String s = \"@Disabled\"; // it.skip(")
                && problems(scan(repo)) == 0);
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
        // audit round 6: a declaration passed to a call is not a statement of its own, so these are notes; they are still found
        write(repo, "src/app.test.ts", "it('a', () => { expect(1).toBe(1) })\nconst s = `${fn({a: 1}, it.skip('b', () => {}))}`\n");
        check("integrity: it.skip( after a nested brace inside ${...} is still found, a note (an argument of fn)",
                count(scan(repo), "test switched off") == 0 && only(scan(repo), Level.NOTE, "may switch a test off", "src/app.test.ts", "it.skip('b'"));
        write(repo, "src/app.test.ts", "it('a', () => { expect(1).toBe(1) })\nconst s = `${fn(\"}\", it.skip('b', () => {}))}`\n");
        check("integrity: a closing brace inside a string does not end the ${...}: still found, a note",
                count(scan(repo), "test switched off") == 0 && only(scan(repo), Level.NOTE, "may switch a test off", "src/app.test.ts", "it.skip('b'"));
        write(repo, "src/app.test.ts", "it('a', () => { expect(1).toBe(1) })\nconst s = `${\"it.skip(\"}`\n");
        check("integrity: the text it.skip( inside a string inside ${...}, a call whose arguments never end, is a note: the lint errs on the safe side without blocking",
                count(scan(repo), "test switched off") == 0 && only(scan(repo), Level.NOTE, "may switch a test off", "src/app.test.ts", "the run of the file judges it: const s = `${\"it.skip(\"}`"));
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
        Integrity.Lint jvm = Integrity.lint(List.of("  @Disabled(\"flaky\")", "  @org.junit.Ignore", "    assumeTrue(false, \"later\");", "    Assume.assumeTrue(\"later\", false);",
                "  @ org.junit.jupiter.api . Disabled", "    assumeFalse(true);", "    Assumptions.abort(\"later\");"));
        check("integrity: the lint is sure of @Disabled, @Ignore and an assumption that never holds on added Java lines; Assumptions.abort is a note",
                jvm.sure().equals(List.of("@Disabled(\"flaky\")", "@org.junit.Ignore", "assumeTrue(false, \"later\");", "Assume.assumeTrue(\"later\", false);",
                        "@ org.junit.jupiter.api . Disabled", "assumeFalse(true);"))
                && jvm.notes().equals(List.of("Assumptions.abort(\"later\");")));
        check("integrity: an annotation written over two added lines is found, joined",
                Integrity.lintedSwitches(List.of("  @", "  Disabled", "  @Test void later() {}")).equals(List.of("@ Disabled")));
        Integrity.Lint aborts = Integrity.lint(List.of("    Assumptions.<Void>abort(\"later\");", "    Assumptions . <Void> abort(\"later\");",
                "import static org.junit.jupiter.api.Assumptions.abort;", "    abort(\"later\");", "    Assumptions.abort<Unit>(\"later\")", "    controller.abort();", "    new AbortController().abort();"));
        check("integrity: an abort with type arguments, imported, or called alone is a note (a condition may hold it); another object's abort is nothing",
                aborts.sure().isEmpty() && aborts.notes().size() == 5 && !Integrity.lintedNote("    controller.abort();") && Integrity.lintedNote("    Assumptions.<Void>abort(\"later\");"));
        // L1-2: a line cannot tell its quotes for certain; what it shows as written is never hidden by the masking
        check("integrity: a quote inside a regular expression does not hide a switch on the same line",
                Integrity.lintedSwitch("const r = /'/; it.skip('a', () => { expect(false).toBe(true); }); const x = 'ok';")
                && Integrity.lintedSwitch("const r = /x\\//; it.skip('b', () => {})")
                && Integrity.lintedSwitch("* 2; it.skip('c', () => {})")
                && Integrity.lintedSwitch("`; it.skip('d', () => {}); const y = `")
                && Integrity.lintedSwitch("#f = 1; it.skip('e', () => {})")
                && Integrity.lintedSwitch("    String t = \"\"\"; @Disabled class X {} //\""));
        // audit round 6: the written reading decides what is sure; a comment between a switch's words is seen by the code only: a note
        check("integrity: a comment between @ and the name, and a declaration assigned to a name, are found as notes",
                Integrity.lintedNote("@/* a */Disabled") && Integrity.lintedNote("#f = it.skip('e', () => {})"));
        check("integrity: lines wholly comments are not read as written; others are",
                !Integrity.lintedSwitch("  // @Disabled until PAY-12") && !Integrity.lintedSwitch("# @pytest.mark.skip(reason=\"x\")")
                && Integrity.lintedNote("  * @Disabled until PAY-12") && Integrity.lintedNote("assertTrue(ok); // it.skip later")
                && !Integrity.lintedSwitch("assertTrue(ok); // it.skip later"));
        check("integrity: a switch over two lines, with a quote in a regular expression on one and a comment between its words on the other, is found, a note",
                Integrity.lint(List.of("const r = /'/; it", "/**/.skip('a', () => {})")).equals(noted("const r = /'/; it /**/.skip('a', () => {})")));
        // the lint reads a line two ways only (audit round 3, the step back): a block comment between a switch's words on a line
        // whose quotes cannot be told is not seen (the guide lists it); the run of the file is the guard
        check("integrity: a block comment between a switch's words, after a quote in a regular expression, is not seen; a URL is no switch",
                !Integrity.lintedSwitch("const r = /'/; it /* ' */ .skip('a', () => {})")
                && !Integrity.lintedSwitch("const url = 'http://example.com/it'; expect(skip).toBe(1)"));
        // the lint is a helper, not a guard: a quote in a regular expression before a trailing comment between a switch's words, over two
        // lines, is not seen (the guide lists it); the run of the file is the guard
        check("integrity: what the lint does not see is not seen: a regular expression's quote before a comment between a switch's words",
                Integrity.lintedSwitches(List.of("const r = /'\\//; it //'", ".skip('a', () => {})")).isEmpty());
        // the honest user's forms (audit, findings 8 and 2)
        check("integrity: a declared abort, and another object's abort however spaced, is nothing; abort called alone is a note, never sure",
                Integrity.lint(List.of("function abort() { return 1; }", "    @Override public void abort() {}", "    controller . abort();", "    signal?.abort();",
                        "  abort() { this.done = true; }", "    def abort(self):", "    this.#abort();")).equals(new Integrity.Lint(List.of(), List.of()))
                && Integrity.lintedNote("    abort(\"later\");") && Integrity.lintedNote("    if (late) abort();") && Integrity.lintedNote("    return abort(\"later\");")
                && !Integrity.lintedSwitch("    abort(\"later\");"));
        check("integrity: an assumption with a plain false and a computed message is a switch; one whose condition is computed is not",
                Integrity.lintedSwitch("    Assumptions.assumeTrue(false, () -> \"later\");") && Integrity.lintedSwitch("    Assume.assumeTrue(String.format(\"later %s\", reason), false);")
                && Integrity.lintedSwitch("    assumeFalse(true, () -> String.valueOf(x));") && !Integrity.lintedSwitch("    assumeTrue(x == false);")
                && !Integrity.lintedSwitch("    assumeTrue(ready(false), \"x\");") && !Integrity.lintedSwitch("    assumeTrue(isOn(), () -> \"false\");"));
        // the direct spellings an honest author writes (audit, findings 5, 4 and 1)
        check("integrity: it?.skip, a parenthesised decorator and white space of other kinds are the same switches",
                Integrity.lintedSwitch("it?.skip('x', () => {});") && Integrity.lintedSwitch("@(pytest.mark.skip(reason='later'))")
                && Integrity.lintedSwitch("it\u00A0.\u00A0skip('x', () => {});") && Integrity.lintedSwitch("it\uFEFF.skip('x', () => {});")
                && Integrity.lintedSwitch("describe?.only('x', () => {});"));
        check("integrity: a block comment that opens on one added line and closes on the next does not hide a switch over the two: a note",
                Integrity.lint(List.of("it /* explanation", "continued */ .skip('x', () => {});")).equals(noted("it /* explanation continued */ .skip('x', () => {});")));
        check("integrity: nor does a Python backslash continuation",
                Integrity.lintedSwitches(List.of("@pytest.mark \\", "    .skip(reason='later')", "def test_later():")).size() == 1);
        check("integrity: Unicode escapes are read as the compiler reads them",
                Integrity.lintedSwitch("\\u0040Disabled") && Integrity.lintedSwitch("@Dis\\u0061bled") && Integrity.lintedSwitch("@Dis\\uuu0061bled")
                && Integrity.lintedSwitch("// comment \\u000a @Disabled") && Integrity.lintedSwitch("it.sk\\u0069p('a', () => {});")
                && Integrity.lintedSwitch("it.sk\\u{69}p('a', () => {});"));
        check("integrity: a line terminator inside a line ends a comment there, as JavaScript reads it",
                Integrity.lintedSwitch("// comment\u2028it.skip('a', () => {});") && !Integrity.lintedSwitch("// comment it.skip('a', () => {});"));
        check("integrity: an assumption with a matcher or a computed condition is not a switch",
                Integrity.lintedSwitches(List.of("    Assume.assumeThat(false, is(false));", "    assumeTrue(ready(false));", "    @DisplayName(\"x\")")).isEmpty());
        honestUsers();
        usualSpellings();
        sureOrNoted();
        gate();
        round6();
        bounded();
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
        // an added line longer than the lint reads is not read, and the report says so: a note, its run judges the file
        write(repo, "src/test/java/demo/LongTest.java", "@" + " ".repeat(10_000) + "Disabled class LongTest {}\n");
        List<Finding> longLine = scan(repo);
        check("integrity: a switch on an added line longer than the lint reads is not read; a note says the line was not read",
                count(longLine, "test switched off") == 0 && only(longLine, Level.NOTE, "lines too long for the switch lint", "src/test/java/demo/LongTest.java", "1 added line is longer than 2000 characters"));
        write(repo, "src/test/java/demo/LongTest.java", "class LongTest { String s = \"" + "x".repeat(50_000) + "\"; }\n@Disabled class Other {}\n");
        List<Finding> beside = scan(repo);
        check("integrity: a long line beside a switch does not hide it, and is said as not read",
                only(beside, Level.PROBLEM, "test switched off", "src/test/java/demo/LongTest.java", "@Disabled class Other {}")
                && only(beside, Level.NOTE, "lines too long for the switch lint", "src/test/java/demo/LongTest.java", "not read for switches"));
        Files.delete(repo.resolve("src/test/java/demo/LongTest.java"));
        write(repo, "web/long.test.ts", "it.".repeat(300_000) + "x;\nit\n" + "it.".repeat(300_000) + "x;\n");
        long linted = System.nanoTime();
        List<Finding> chain = scan(repo);
        check("integrity: a very long added line of a test file does not keep the lint busy", (System.nanoTime() - linted) / 1_000_000 < 5000 && count(chain, "test switched off") == 0
                && only(chain, Level.NOTE, "lines too long for the switch lint", "web/long.test.ts", "2 added lines are longer"));
        reset(repo);
        // a test file is never read whole by the scan, whatever it does: each whole read is seen
        java.util.Set<String> wholly = new java.util.TreeSet<>();
        Change.wholeRead = wholly::add;
        try {
            write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("    private int helper", "    @Disabled @Test void off() {}\n    private int helper"));
            write(repo, "web/new.test.ts", "it.skip('later', () => {})\n");
            Files.delete(repo.resolve("web/sum.test.ts"));
            write(repo, "pom.xml", POM.replace("<version>32.0</version>", "<version>33.0</version>"));
            scan(repo);
        } finally {
            Change.wholeRead = path -> {};
        }
        check("integrity: no test file is read whole, only build and configuration files",
                wholly.contains("pom.xml") && wholly.stream().noneMatch(f -> f.endsWith("CalcTest.java") || f.endsWith(".test.ts")));
        reset(repo);
        write(repo, "package.json", "{\"scripts\": {\"test\": \"vitest run" + " ".repeat(20000) + "|| true\"}}\n");
        long started = System.nanoTime();
        scan(repo);
        check("integrity: a very long line does not keep the scan busy", (System.nanoTime() - started) / 1_000_000 < 3000);
        reset(repo);

        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("        assertEquals(3, 1 + 2);\n", ""));
        check("integrity: fewer assertions in an edited test file is a note, counted on the lines the change removes and adds",
                only(scan(repo), Level.NOTE, "fewer assertions", "src/test/java/demo/CalcTest.java", "the lines the change removes hold 1 assertion, the lines it adds 0"));
        reset(repo);

        // L1-11: a test file is never read whole, so its size does not matter: only the lines the change adds and removes are read
        String fixture = "const rows = [\n" + ("  { a: 1, b: 2, c: 'some fixture text to make the file large' },\n").repeat(40_000) + "];\nit('reads', () => { expect(rows.length).toBe(40000) });\n";
        write(repo, "web/big.test.ts", fixture);
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "a large fixture");
        write(repo, "web/big.test.ts", "// rows of the export\n" + fixture);
        List<Finding> large = scan(repo);
        check("integrity: a comment added to a script test that holds a 2 MB fixture is nothing to report", fixture.length() > 2_000_000 && large.isEmpty());
        Files.delete(repo.resolve("web/big.test.ts"));
        check("integrity: and deleting that file is a test file deleted, not a file too large to scan",
                only(scan(repo), Level.PROBLEM, "test file deleted", "web/big.test.ts", "the file is gone") && count(scan(repo), "too large to scan") == 0);
        git(repo, "reset", "-q", "--hard", "HEAD~1");
    }

    /** Audit round 3, findings 1 and 2: what honest work writes is not a switch. */
    private static void honestUsers() {
        // a conditional skip is not a switch: platform, browser and environment conditions are ordinary
        List<String> conditional = List.of("@pytest.mark.skipif(False, reason=\"supported\")", "@pytest.mark.skipif(sys.platform == \"win32\", reason=\"posix only\")",
                "@unittest.skipUnless(True, \"supported\")", "@unittest.skipIf(sys.platform == 'win32', 'posix only')",
                "test.skip(false, \"unsupported browser\");", "test.fixme(false, \"unsupported browser\");",
                "test.skip(browserName === 'webkit', 'not on Safari');", "test.skip(({ browserName }) => browserName !== 'chromium', 'Chromium only');",
                "test.fixme(isMobile)", "it.skipIf(process.platform === 'win32')('posix', () => {})", "it.runIf(ci)('in CI', () => {})",
                "@DisabledOnOs(OS.WINDOWS)", "@DisabledIfEnvironmentVariable(named = \"CI\", matches = \"true\")", "@DisabledIfSystemProperty(named = \"x\", matches = \"y\")",
                "@DisabledIf(\"isWindows\")", "@EnabledIf(\"isLinux\")", "@EnabledOnJre(JRE.JAVA_21)", "@DisabledForJreRange(min = JRE.JAVA_8)");
        List<String> hits = conditional.stream().filter(Integrity::lintedSwitch).toList();
        if (!hits.isEmpty()) System.out.println("    conditional skips reported: " + hits);
        check("integrity: a conditional skip (skipif, skipUnless, Playwright's test.skip(condition, ...), @DisabledOnOs, @DisabledIf...) is not a switch", hits.isEmpty());
        check("integrity: a decorator or an annotation whose condition is a plain true is a switch, sure",
                Integrity.lintedSwitch("@pytest.mark.skipif(True, reason=\"later\")") && Integrity.lintedSwitch("@unittest.skipIf(True, \"later\")")
                && Integrity.lintedSwitch("@unittest.skipUnless(False, \"later\")") && Integrity.lintedSwitch("@DisabledIf(\"true\")") && Integrity.lintedSwitch("@EnabledIf(\"false\")"));
        check("integrity: Playwright's test.skip(true, ...) and test.fixme(true) skip from where they stand, which a condition around them may hold: a note",
                Integrity.lintedNote("test.skip(true, 'later');") && Integrity.lintedNote("test.fixme(true)") && !Integrity.lintedSwitch("test.skip(true, 'later');"));
        check("integrity: a skip whose title is a string, or a string joined to more, is a declaration, sure; one whose first argument is computed is a note",
                Integrity.lintedSwitch("test.skip(`x ${n}`, async () => {})") && Integrity.lintedSwitch("it.skip('adds ' + n, () => {})")
                && Integrity.lintedNote("it.skip(name, () => {})") && Integrity.lintedNote("describe.skip(suite.title, function () {")
                && !Integrity.lintedSwitch("it.skip(name, () => {})"));
        // a method named abort is declared, not called
        check("integrity: a method declared with the name abort, with a return type, modifiers or generics, is not a switch",
                !Integrity.lintedSwitch("  abort(): void {}") && !Integrity.lintedSwitch("  abort(reason?: string): Promise<void> {")
                && !Integrity.lintedSwitch("  public abort(): void {}") && !Integrity.lintedSwitch("  abort<T>(value: T): T {")
                && !Integrity.lintedSwitch("  abort(): void;") && !Integrity.lintedSwitch("  async abort() {") && !Integrity.lintedSwitch("  fun abort(): Unit {}"));
        check("integrity: abort called alone is a note, also as a branch of a ternary",
                Integrity.lintedNote("    abort();") && Integrity.lintedNote("    late ? abort() : run();") && !Integrity.lintedSwitch("    abort();"));
        // names that hold the words of a switch
        check("integrity: another object's fit, a function declared fit, and words that hold a switch's letters are not switches",
                Integrity.lintedSwitches(List.of("    model.fit(X_train, y_train)", "    def fit(self, X, y):", "    fitAddon.fit();", "    const skipped = rows.filter(r => r.only);",
                        "    expect(benefit).toBe(1);", "    const opts = { skip: 10, take: 5 };", "    it('paginates', async () => { await repo.find({ skip: true }) })",
                        "    result = fit(data)", "  next() { this.skip(10); }")).isEmpty());
        // a skip from inside a test, a condition on its line, on the line above it or further: a note, never sure
        check("integrity: a skip from inside a test under a condition is not sure",
                Integrity.lintedSwitches(List.of("    if not has_gpu: pytest.skip(\"needs a GPU\")", "    if testing.Short() { t.Skip(\"short mode\") }",
                        "    if (!process.env.DB) this.skip();", "    self.skipTest('x') if broken else None")).isEmpty()
                && Integrity.lintedSwitches(List.of("    if not has_gpu:", "        pytest.skip(\"needs a GPU\")")).isEmpty()
                && Integrity.lintedSwitches(List.of("\tif testing.Short() {", "\t\tt.Skipf(\"short mode: %s\", why)", "\t}")).isEmpty()
                && Integrity.lintedSwitches(List.of("    except ImportError:", "        raise unittest.SkipTest(\"needs numpy\")")).isEmpty()
                && Integrity.lintedSwitches(List.of("  if (browserName === 'webkit') {", "    test.skip();")).isEmpty()
                && !Integrity.lintedSwitch("  if (!process.env.CI) test.skip(true, 'local only');"));
        check("integrity: a switch chosen by a condition, or kept for later use, is a note",
                Integrity.lintedNote("const describeIf = process.env.DB ? describe : describe.skip;") && Integrity.lintedNote("(isCI ? it.skip : it)('x', () => {});")
                && Integrity.lintedNote("const later = it.skip;") && !Integrity.lintedSwitch("const later = it.skip;"));
    }

    /** Audit round 3, finding 4: the usual spellings, each beside an honest neighbour. */
    private static void usualSpellings() {
        check("integrity: Vitest's option object that skips, focuses or marks to do is a switch, over lines too",
                Integrity.lintedSwitch("test('x', { skip: true }, () => {})") && Integrity.lintedSwitch("it('x', { only: true }, () => {})")
                && Integrity.lintedSwitch("describe('x', { todo: true }, () => {})") && Integrity.lintedSwitch("test('x', { timeout: 100, skip: true }, async () => {})")
                && Integrity.lintedSwitches(List.of("test('x', {", "  skip: true,", "}, () => {")).size() == 1
                && !Integrity.lintedSwitch("test('x', { skip: false }, () => {})") && !Integrity.lintedSwitch("test('x', { timeout: 100 }, () => {})"));
        check("integrity: xit.each, fit.each and xdescribe.each are switches",
                Integrity.lintedSwitch("xit.each([[1]])('x', (a) => {})") && Integrity.lintedSwitch("fit.each([[1]])('x', (a) => {})")
                && Integrity.lintedSwitch("xdescribe.each([[1]])('x', () => {})") && Integrity.lintedSwitch("fit('x', () => {})"));
        check("integrity: pytest's skip mark in pytestmark and in marks=, and xfail that does not run, are switches",
                Integrity.lintedSwitch("pytestmark = pytest.mark.skip(reason=\"later\")") && Integrity.lintedSwitch("pytestmark = [pytest.mark.slow, pytest.mark.skip]")
                && Integrity.lintedSwitch("    pytest.param(1, marks=pytest.mark.skip(reason=\"later\")),") && Integrity.lintedSwitch("@pytest.mark.xfail(run=False)")
                && Integrity.lintedSwitch("@pytest.mark.xfail(reason=\"bug 12\", run=False)") && Integrity.lintedSwitch("    pytest.param(2, marks=pytest.mark.xfail(run=False)),"));
        check("integrity: a mark kept for later, an xfail that runs, and a conditional xfail are not",
                !Integrity.lintedSwitch("skip_slow = pytest.mark.skip(reason=\"need --runslow\")") && !Integrity.lintedSwitch("@pytest.mark.xfail(reason=\"bug 12\")")
                && !Integrity.lintedSwitch("@pytest.mark.xfail(sys.platform == \"win32\", run=False, reason=\"x\")")
                && !Integrity.lintedSwitch("@pytest.mark.xfail(run=False, condition=sys.platform == \"win32\")") && !Integrity.lintedSwitch("pytestmark = pytest.mark.slow"));
        List<String> body = List.of("\tt.Skipf(\"later: %s\", reason)", "\tt.SkipNow()", "    pytest.skip(\"later\")", "        self.skipTest(\"later\")",
                "        raise unittest.SkipTest(\"later\")", "    this.skip();", "  it('x', function () { this.skip(); });", "def test_later(): pytest.skip(\"later\")", "  test.skip();");
        check("integrity: Go's t.Skipf, and the skips from inside a test as statements of their own, are notes: a condition on another line may hold them",
                body.stream().allMatch(Integrity::lintedNote) && body.stream().noneMatch(Integrity::lintedSwitch));
        check("integrity: the same words that are not such a statement are not",
                !Integrity.lintedSwitch("    # pytest.skip(\"later\")") && !Integrity.lintedSwitch("    doc = 'pytest.skip(x)'") && !Integrity.lintedSwitch("    s = mocker.skip(1)")
                && !Integrity.lintedSwitch("    result = pytest.importorskip(\"numpy\")"));
        check("integrity: Mocha's specify, TestNG's enabled = false, Vitest's skipIf(true) and runIf(false) are switches; Jasmine's pending() is a note",
                Integrity.lintedSwitch("specify.skip('x', () => {})") && Integrity.lintedSwitch("xspecify('x', () => {})") && Integrity.lintedSwitch("  @Test(enabled = false)")
                && Integrity.lintedSwitch("  @Test(description = \"x\", enabled = false)") && Integrity.lintedSwitch("it.skipIf(true)('x', () => {})")
                && Integrity.lintedSwitch("describe.runIf(false)('x', () => {})") && Integrity.lintedNote("    pending('later');"));
        check("integrity: their honest neighbours are not",
                !Integrity.lintedSwitch("  @Test(enabled = true)") && !Integrity.lintedSwitch("  @Test(expected = IllegalStateException.class)") && !Integrity.lintedSwitch("it.skipIf(isWindows)('x', () => {})")
                && !Integrity.lintedSwitch("describe.runIf(process.env.DB)('x', () => {})") && !Integrity.lintedSwitch("    const p = pending(order);")
                && !Integrity.lintedSwitch("    if (!ready) pending('not ready');") && !Integrity.lintedSwitch("specify('x', () => {})"));
        check("integrity: a pending test, its whole declaration on one line, is a switch; a test with a body is not",
                Integrity.lintedSwitch("it('pending test');") && Integrity.lintedSwitch("  test(\"later\")") && !Integrity.lintedSwitch("it('works', () => {});")
                && !Integrity.lintedSwitch("it('works', async () => {") && !Integrity.lintedSwitch("  it(name);"));
    }

    private static final Integrity.Lint NOTHING = new Integrity.Lint(List.of(), List.of());

    /**
     * Audit round 4, the step back: the lint blocks only what it read shows for sure, and says the
     * rest as a note. Each shape of the audit beside its honest neighbour.
     */
    private static void sureOrNoted() {
        // finding 1: a call written over lines is judged where its arguments are, never from its opening line
        check("integrity: a Playwright condition over three lines (test.skip(, false, 'supported')) is nothing: the opening line is not judged alone",
                Integrity.lint(List.of("test.skip(", "  false,", "  'supported');")).equals(NOTHING)
                && Integrity.lint(List.of("  test.fixme(", "    false,", "    'supported',", "  );")).equals(NOTHING));
        Integrity.Lint computed = Integrity.lint(List.of("test.skip(", "  browserName === 'webkit',", "  'not on Safari');"));
        check("integrity: over lines, a computed condition is a note; a title, a plain True and an assumption that never holds are sure",
                computed.sure().isEmpty() && computed.notes().equals(List.of("test.skip( browserName === 'webkit',"))
                && Integrity.lint(List.of("it.skip(", "  'later',", "  () => {")).sure().equals(List.of("it.skip( 'later',"))
                && Integrity.lint(List.of("@pytest.mark.skipif(", "    True,", "    reason='later')")).sure().size() == 1
                && Integrity.lint(List.of("    assumeTrue(", "        false, \"later\");")).sure().size() == 1);
        check("integrity: a call whose arguments end in none of its readings is a note; one that closes as no switch is nothing",
                Integrity.lint(List.of("test.skip(", "  (", "    browserName", "  ) === 'webkit', 'x')")).equals(new Integrity.Lint(List.of(), List.of("test.skip(")))
                && Integrity.lint(List.of("    assumeTrue(", "        System.getenv(\"X\") != null,", "        \"needs X\");")).equals(NOTHING));
        // finding 2: a statement between the condition and the skip, or only the skip's message edited under an unchanged condition
        check("integrity: a skip from inside a test with a statement between it and its condition is a note, not a problem",
                Integrity.lint(List.of("    if (!ready) {", "      log.info('not ready');", "      this.skip();", "    }")).equals(new Integrity.Lint(List.of(), List.of("this.skip();")))
                && Integrity.lint(List.of("        pytest.skip(\"needs a GPU, see #12\")")).equals(new Integrity.Lint(List.of(), List.of("pytest.skip(\"needs a GPU, see #12\")"))));
        // finding 3: a condition and a computed reason
        check("integrity: test.skip(false, reason) is nothing; test.fixme(isMobile, ISSUE_REASON) is a note",
                Integrity.lint(List.of("test.skip(false, reason);", "test.fixme(false, issueReason());")).equals(NOTHING)
                && Integrity.lint(List.of("test.fixme(isMobile, ISSUE_REASON);")).equals(new Integrity.Lint(List.of(), List.of("test.fixme(isMobile, ISSUE_REASON);"))));
        // finding 4: abort declared over lines, or another object's abort over lines
        check("integrity: a method named abort declared over three lines is nothing; another object's abort over two lines is not sure",
                Integrity.lint(List.of("  abort(", "    reason?: string,", "  ): void {}")).equals(NOTHING)
                && Integrity.lint(List.of("    controller.", "      abort();")).sure().isEmpty());
        // finding 6: Python's conditions by keyword
        List<String> keyword = List.of("@pytest.mark.skipif(reason=\"later\", condition=True)", "@unittest.skipIf(condition=True, reason=\"later\")",
                "@unittest.skipUnless(condition=False, reason=\"later\")", "@pytest.mark.skipif(reason=\"later\")", "@pytest.mark.xfail(reason=\"x\", condition=True, run=False)");
        check("integrity: a plain condition by keyword, in any place, and pytest's skipif with no condition, are sure",
                Integrity.lint(keyword).sure().size() == keyword.size());
        // audit round 5: a computed condition is a note (the run of the file says whether it held), never sure
        List<String> computedKeyword = List.of("@pytest.mark.skipif(reason=\"posix\", condition=sys.platform == \"win32\")", "@unittest.skipIf(condition=IS_WINDOWS, reason=\"posix\")",
                "@unittest.skipUnless(condition=HAS_GPU, reason=\"gpu\")", "@pytest.mark.skipif(True if X else False, reason=\"x\")");
        check("integrity: their honest neighbours, a computed condition by keyword, are notes, never sure",
                Integrity.lint(computedKeyword).equals(new Integrity.Lint(List.of(), computedKeyword))
                && Integrity.lint(List.of("@pytest.mark.xfail(run=False,", "    condition=sys.platform == \"win32\")"))
                        .equals(new Integrity.Lint(List.of(), List.of("@pytest.mark.xfail(run=False, condition=sys.platform == \"win32\")"))));
        // what was sure stays sure: the usual spellings, one per line
        List<String> sure = List.of("@Disabled", "@org.junit.Ignore", "@Test(enabled = false)", "@DisabledIf(\"true\")", "@pytest.mark.skip(reason=\"x\")",
                "@unittest.skip(\"x\")", "it.skip('x', () => {})", "describe.only('x', () => {})", "xit('x', () => {})", "test.todo('x')", "it.skip.each([[1]])('x', (a) => {})",
                "test('x', { skip: true }, () => {})", "it.skipIf(true)('x', () => {})", "assumeTrue(false);", "assumeFalse(true, \"x\");", "it('pending');");
        List<String> missed = sure.stream().filter(l -> !Integrity.lintedSwitch(l)).toList();
        if (!missed.isEmpty()) System.out.println("    not sure: " + missed);
        check("integrity: the forms the lint is sure of are problems", missed.isEmpty());
    }

    private static Integrity.Lint noted(String... lines) {
        return new Integrity.Lint(List.of(), List.of(lines));
    }

    /**
     * Audit round 5: one gate for sure. Each finding's exact shapes beside the honest neighbour and
     * the plain form, then the property of the gate: no sure form stays sure when its literal is
     * made part of more, nested, or cut before its argument ends.
     */
    private static void gate() {
        // finding 1: a title is judged where its argument ends, and only a string for certain is one
        check("integrity: a string first argument cut by the line end, or compared after a +, is a note, not a title",
                Integrity.lint(List.of("test.skip('webkit'", "  === browserName, 'webkit only');")).equals(noted("test.skip('webkit' === browserName, 'webkit only');"))
                && Integrity.lint(List.of("test.skip('platform: ' + platform === target, 'unsupported')")).equals(noted("test.skip('platform: ' + platform === target, 'unsupported')"))
                && Integrity.lint(List.of("test.skip('webkit'")).equals(noted("test.skip('webkit'")));
        check("integrity: its neighbours, a title joined with + to names and calls, and a title over two lines, are sure",
                Integrity.lintedSwitch("it.skip('adds ' + n + ' to ' + fmt(m), () => {})") && Integrity.lintedSwitch("test.skip(`on ${browser}`, async () => {")
                && Integrity.lint(List.of("test.skip('webkit only'", "  , async ({ page }) => {")).sure().size() == 1 && Integrity.lintedSwitch("test.skip('webkit only', async ({ page }) => {"));
        // finding 2: an option counts only as a top-level property, with a whole true, that nothing after it can change
        check("integrity: an option with a computed value, a nested one and one a spread may change are not sure",
                Integrity.lint(List.of("test('x', { skip: true && isWindows }, fn);")).equals(noted("test('x', { skip: true && isWindows }, fn);"))
                && Integrity.lint(List.of("test('x', { meta: { skip: true } }, fn);")).equals(NOTHING)
                && Integrity.lint(List.of("test('x', { skip: true, ...options }, fn);")).equals(noted("test('x', { skip: true, ...options }, fn);"))
                && Integrity.lint(List.of("test('x', { skip: true, skip: false }, fn);")).equals(NOTHING)
                && Integrity.lint(List.of("test('x', { skip: true, [key]: v }, fn);")).equals(noted("test('x', { skip: true, [key]: v }, fn);")));
        check("integrity: its neighbours, an option after a spread, a later one that sets true, and a quoted name, are sure",
                Integrity.lintedSwitch("test('x', { ...options, skip: true }, fn);") && Integrity.lintedSwitch("test('x', { skip: false, only: true }, fn);")
                && Integrity.lintedSwitch("test('x', { 'skip': true }, fn);") && Integrity.lintedSwitch("test('x', { skip: true }, fn);"));
        // finding 3: an annotation's condition is a whole literal in a complete argument list
        check("integrity: @Test(enabled = false || RUN_TESTS), also cut after false, is a note",
                Integrity.lint(List.of("@Test(enabled = false || RUN_TESTS)")).equals(noted("@Test(enabled = false || RUN_TESTS)"))
                && Integrity.lint(List.of("@Test(enabled = false", "    || RUN_TESTS)")).equals(noted("@Test(enabled = false || RUN_TESTS)"))
                && Integrity.lint(List.of("@DisabledIf(\"true\" + suffix)")).sure().isEmpty() && Integrity.lint(List.of("@EnabledIf(value = \"false\", disabledReason = x)")).sure().size() == 1);
        check("integrity: its neighbours, @Test(enabled = false) whole, over two lines, and @Test(enabled = true), are sure and nothing",
                Integrity.lintedSwitch("@Test(enabled = false)") && Integrity.lint(List.of("@Test(enabled = false,", "      description = \"x\")")).sure().size() == 1
                && Integrity.lint(List.of("@Test(enabled = true)")).equals(NOTHING));
        // finding 4: a modifier is sure only as the callee of the call that registers a test
        check("integrity: test.skip.each chosen by a condition, or kept, is a note; an alias that is only named is nothing",
                Integrity.lint(List.of("const testEachIf = isWindows ? test.skip.each : test.each;")).equals(noted("const testEachIf = isWindows ? test.skip.each : test.each;"))
                && Integrity.lint(List.of("const table = it.skip.each([[1]]);")).equals(noted("const table = it.skip.each([[1]]);"))
                && Integrity.lint(List.of("const later = xit.each;", "import { xit, fit } from '@jest/globals';")).equals(NOTHING));
        check("integrity: its neighbours, .each with a table and a title, also a tagged table and an alias, are sure",
                Integrity.lintedSwitch("it.skip.each([[1, 2]])('adds %i', (a, b) => {") && Integrity.lintedSwitch("describe.only.each`a | b`('x $a', () => {")
                && Integrity.lintedSwitch("xit.each([[1]])('x', (a) => {})") && Integrity.lint(List.of("it.skip.each([", "  [1, 2],", "])('adds %i', (a, b) => {")).sure().size() == 1);
        // finding 5: a decorator is sure only when it is the whole mark
        check("integrity: a mark inside a larger decorator expression is a note",
                Integrity.lint(List.of("@(pytest.mark.skip if IS_CI else identity)")).equals(noted("@(pytest.mark.skip if IS_CI else identity)"))
                && Integrity.lint(List.of("@pytest.mark.skip if IS_CI else identity")).sure().isEmpty()
                && Integrity.lint(List.of("@apply(pytest.mark.skip)")).equals(noted("@apply(pytest.mark.skip)"))
                && Integrity.lint(List.of("pytestmark = pytest.mark.skip if IS_CI else []")).sure().isEmpty()
                && Integrity.lint(List.of("pytestmark = [pytest.mark.skip] if IS_CI else []")).sure().isEmpty());
        check("integrity: its neighbours, the mark alone, in parentheses, before the function and in a list, are sure",
                Integrity.lintedSwitch("@pytest.mark.skip") && Integrity.lintedSwitch("@(pytest.mark.skip(reason='later'))") && Integrity.lintedSwitch("@pytest.mark.skip  # flaky")
                && Integrity.lint(List.of("@pytest.mark.skip(", "    reason='later')", "def test_later():")).sure().size() == 1
                && Integrity.lintedSwitch("pytestmark = [pytest.mark.slow, pytest.mark.skip(reason='later')]"));
        // finding 6: pytest's skipif reads every condition, and none is a skip; condition= is the only one when given (pytest 9)
        check("integrity: a bare @pytest.mark.skipif above a function, and a True among its conditions, are sure",
                Integrity.lint(List.of("@pytest.mark.skipif")).sure().size() == 1 && Integrity.lintedSwitch("@pytest.mark.skipif(False, True, reason='off')")
                && Integrity.lintedSwitch("@pytest.mark.skipif(False, condition=True, reason='off')") && Integrity.lintedSwitch("@pytest.mark.xfail(False, True, run=False, reason='x')"));
        check("integrity: its neighbours: every condition False, or condition=False beside a positional True, is nothing; a computed one is a note",
                Integrity.lint(List.of("@pytest.mark.skipif(False, False, reason='off')", "@pytest.mark.skipif(True, condition=False, reason='off')",
                        "@pytest.mark.xfail(True, run=True, reason='x')")).equals(NOTHING)
                && Integrity.lint(List.of("@pytest.mark.skipif(False, IS_WINDOWS, reason='posix')")).equals(noted("@pytest.mark.skipif(False, IS_WINDOWS, reason='posix')"))
                && Integrity.lint(List.of("@pytest.mark.skipif(*conditions, reason='x')")).sure().isEmpty()
                && Integrity.lint(List.of("@pytest.mark.xfail(run=RUN_SLOW, reason='x')")).sure().isEmpty());
        // a declaration, an option or an assumption after a condition on its line may not run: a note
        check("integrity: a sure form after a condition on its line is a note",
                Integrity.lint(List.of("if (isCI) it.skip('x', () => {})")).equals(noted("if (isCI) it.skip('x', () => {})"))
                && Integrity.lint(List.of("ci ? test('x', { skip: true }, fn) : test('x', fn)")).sure().isEmpty()
                && Integrity.lint(List.of("ci && it.skipIf(true)('x', fn)")).sure().isEmpty()
                && Integrity.lint(List.of("    if (late) assumeTrue(false);")).equals(noted("if (late) assumeTrue(false);"))
                && Integrity.lint(List.of("if sys.platform == 'win32': pytestmark = pytest.mark.skip")).sure().isEmpty());
        check("integrity: so is one on the line after a line that opens a block under a condition; a block without one does not change it",
                Integrity.lint(List.of("if (!process.env.DB) {", "  describe.skip('db', () => {")).equals(noted("if (!process.env.DB) { describe.skip('db', () => {"))
                && Integrity.lint(List.of("except ImportError:", "    pytestmark = pytest.mark.skip(reason='needs numpy')"))
                        .equals(noted("except ImportError: pytestmark = pytest.mark.skip(reason='needs numpy')"))
                && Integrity.lint(List.of("describe('db', () => {", "  it.skip('x', () => {})")).sure().equals(List.of("it.skip('x', () => {})"))
                && Integrity.lint(List.of("if (x) { run(); }", "it.skip('x', () => {})")).sure().size() == 1
                && Integrity.lint(List.of("if (isWindows) {", "  @Disabled")).sure().equals(List.of("@Disabled")));
        // the property of the gate: no sure form stays sure when its literal is made part of more, nested or cut
        String[][] forms = {
            {"it.skip(§, () => {})", "'x'"}, {"describe.only(§, () => {})", "'x'"}, {"test.todo(§)", "'x'"}, {"test.fixme(§, async () => {})", "'x'"},
            {"xit(§, () => {})", "'x'"}, {"fit(§, () => {})", "'x'"}, {"it.skip.each([[1]])(§, (a) => {})", "'x'"}, {"it.skip(§ + n, () => {})", "'x'"},
            {"test('x', { skip: § }, () => {})", "true"}, {"it('x', { timeout: 1, only: § }, () => {})", "true"}, {"test(§, { skip: true }, () => {})", "'x'"},
            {"it.skipIf(§)('x', () => {})", "true"}, {"describe.runIf(§)('x', () => {})", "false"},
            {"@Test(enabled = §)", "false"}, {"@Test(description = \"x\", enabled = §)", "false"}, {"@DisabledIf(§)", "\"true\""}, {"@EnabledIf(value = §)", "\"false\""},
            {"@pytest.mark.skipif(§, reason='x')", "True"}, {"@pytest.mark.skipif(condition=§, reason='x')", "True"}, {"@pytest.mark.skipif(False, §, reason='x')", "True"},
            {"@unittest.skipIf(§, 'x')", "True"}, {"@unittest.skipUnless(§, 'x')", "False"}, {"@pytest.mark.xfail(run=§)", "False"},
            {"@pytest.mark.xfail(§, run=False, reason='x')", "True"}, {"assumeTrue(§);", "false"}, {"Assume.assumeFalse(\"x\", §);", "true"},
            {"@§", "Disabled(\"x\")"}, {"@§", "org.junit.Ignore"}, {"@§", "pytest.mark.skip(reason='x')"}, {"@§", "pytest.mark.skipif"}, {"@§", "unittest.skip('x')"}, {"pytestmark = §", "pytest.mark.skip"},
            {"    pytest.param(1, marks=§),", "pytest.mark.skip"}, {"pytestmark = [pytest.mark.slow, §]", "pytest.mark.skip"}, {"it(§);", "'x'"}};
        List<String> grown = List.of("§ || X", "§ && X", "§ if X else Y", "§ ? a : b", "§ === X", "(§ or X)", "f(§)", "§.with_args(x)");
        for (String[] form : forms) {
            String plain = form[0].replace("§", form[1]);
            int cut = form[0].indexOf('§') + form[1].length();
            boolean python = plain.contains("pytest") || plain.contains("unittest"), placed = plain.startsWith("@");
            List<List<String>> variants = new java.util.ArrayList<>();
            for (String g : grown) variants.add(List.of(form[0].replace("§", g.replace("§", form[1]))));
            if (cut < plain.length()) { // a mark that ends its form ends its statement there: nothing is cut
                variants.add(List.of(plain.substring(0, cut))); // the reading ends before the argument does
                variants.add(List.of(plain.substring(0, cut), "  || X" + plain.substring(cut))); // and goes on, computed, on the next line
            }
            if (!placed && !python) variants.add(List.of("if (X) " + plain)); // a condition before it on its line
            if (plain.contains("{ ")) {
                variants.add(List.of(plain.replaceFirst("\\{ ([^}]*) \\}", "{ meta: { $1 } }")));
                variants.add(List.of(plain.replaceFirst("\\{ ([^}]*) \\}", "{ $1, ...opts }")));
            }
            // audit round 6: a form that is a deferred body, an argument or a value is not a statement of its own
            for (String around : List.of("() -> %s", "() => %s", "f(%s)", "x = %s", "return %s")) variants.add(List.of(String.format(around, plain.strip())));
            // a comment, a quoted operator or a quoted override inside what the proof reads changes what it means as written
            variants.add(List.of(placed && form[0].equals("@§") ? "@/* || X */" + form[1] : form[0].replace("§", form[1] + " /* || X */")));
            if (!form[1].startsWith("'")) { // a title in quotes with an operator inside is still a title
                String quoted = form[1].startsWith("\"") ? form[1].replaceFirst("\"$", " || X\"") : "\"" + form[1].replace('"', '\'') + " || X\"";
                variants.add(List.of(form[0].replace("§", quoted)));
            }
            java.util.regex.Matcher option = java.util.regex.Pattern.compile("(skip|only|todo): true").matcher(plain);
            if (option.find()) {
                variants.add(List.of(plain.replaceFirst("\\{ ([^}]*) \\}", "{ $1, '" + option.group(1) + "': false }")));
                variants.add(List.of(plain.replaceFirst("\\{ ([^}]*) \\}", "{ $1, \"" + option.group(1) + "\": X }")));
            }
            // a line that opens a condition before it, the form on one, two or three lines (an annotation or a decorator takes none)
            List<List<String>> widths = new java.util.ArrayList<>();
            for (int width = 1; width <= (plain.startsWith("it(") ? 1 : 3); width++) widths.add(split(plain, width));
            if (!placed) for (List<String> w : widths) variants.add(Stream.concat(Stream.of(python ? "if X:" : "if (X) {"), w.stream()).toList());
            List<String> stillSure = variants.stream().filter(v -> !Integrity.lint(v).sure().isEmpty()).map(v -> String.join(" / ", v)).toList();
            List<String> notSure = widths.stream().filter(w -> Integrity.lint(w).sure().isEmpty()).map(w -> String.join(" / ", w)).toList();
            if (!stillSure.isEmpty()) System.out.println("    sure though not whole: " + stillSure);
            if (!notSure.isEmpty()) System.out.println("    not sure over lines: " + notSure);
            check("integrity: " + plain + " is sure, on one, two or three lines, and no variant of it that is not whole, or not a statement or a declaration of its own, is",
                    Integrity.lintedSwitch(plain) && stillSure.isEmpty() && notSure.isEmpty());
        }
    }

    /**
     * Audit round 6, findings 2 to 5 and 7: each shape of the audit beside the plain form and the
     * honest neighbour, all through the lint (the gate).
     */
    private static void round6() {
        // finding 2: the written reading decides what is sure; a quoted key the code reading emptied may override an option
        check("integrity: an option overridden by a later quoted key, or by a quoted key with a computed value, is a note",
                Integrity.lint(List.of("test('x', { skip: true, 'skip': false }, fn);")).equals(noted("test('x', { skip: true, 'skip': false }, fn);"))
                && Integrity.lint(List.of("test('x', { only: true, \"only\": false }, fn);")).equals(noted("test('x', { only: true, \"only\": false }, fn);"))
                && Integrity.lint(List.of("test('x', { skip: true, 'skip': isCI }, fn);")).equals(noted("test('x', { skip: true, 'skip': isCI }, fn);")));
        check("integrity: its plain forms are sure, and an unquoted override, or a later quoted key that is no option, says what it says",
                Integrity.lintedSwitch("test('x', { skip: true }, fn);") && Integrity.lintedSwitch("test('x', { 'skip': true }, fn);")
                && Integrity.lintedSwitch("test('x', { skip: true, 'timeout': 5 }, fn);") && Integrity.lint(List.of("test('x', { skip: true, skip: false }, fn);")).equals(NOTHING));
        check("integrity: a comment or a string inside what the proof reads is read as written: a note, never sure",
                Integrity.lint(List.of("@Test(enabled = false /* || RUN */)")).equals(noted("@Test(enabled = false /* || RUN */)"))
                && Integrity.lint(List.of("test('x', { skip: true /* , skip: false */ }, fn);")).sure().isEmpty()
                && Integrity.lint(List.of("@DisabledIf(\"true || isWindows\")")).equals(noted("@DisabledIf(\"true || isWindows\")")));
        // what the written reading shows inside a string or a trailing comment is read, at the gate's level for it as written
        check("integrity: a switch's words inside a string or a trailing comment are read as written: sure where they stand as a statement, else a note",
                Integrity.lintedSwitch("String s = \"x\"; @Disabled class X {}") && Integrity.lintedNote("const s = \"it.skip('a', () => {})\";")
                && Integrity.lintedNote("x(); // it.skip('a', () => {})") && Integrity.lintedNote("String s = \"@Disabled\";"));
        // finding 7: a literal condition that runs is nothing; the masked reading's emptied string does not make it a note
        check("integrity: @DisabledIf(\"false\") and @EnabledIf(\"true\") are nothing; @DisabledIf(\"true\") is sure, @DisabledIf(\"isWindows\") a note",
                Integrity.lint(List.of("@DisabledIf(\"false\")", "@EnabledIf(\"true\")", "@EnabledIf(value = \"true\")", "@DisabledIf(expression = \" false \")")).equals(NOTHING)
                && Integrity.lintedSwitch("@DisabledIf(\"true\")") && Integrity.lintedSwitch("@EnabledIf(\"false\")")
                && Integrity.lint(List.of("@DisabledIf(\"isWindows\")")).equals(noted("@DisabledIf(\"isWindows\")")));
        // finding 3: the added line before a form is read by the gate, whatever width of reading proves the form
        List<String> joinedTwo = List.of("if (isCI) {", "  describe.skip(", "    'database', () => {});");
        List<String> joinedThree = List.of("if (isCI) {", "  describe", "    .skip(", "    'database', () => {});");
        check("integrity: a declaration under a condition on the line before is a note, on one line, joined over two and over three, shown with the condition",
                Integrity.lint(List.of("if (isCI) {", "  describe.skip('database', () => {});")).equals(noted("if (isCI) { describe.skip('database', () => {});"))
                && Integrity.lint(joinedTwo).equals(noted("if (isCI) { describe.skip( 'database', () => {});"))
                && Integrity.lint(joinedThree).equals(noted("if (isCI) { describe .skip( 'database', () => {});")));
        // the auditor's own pass: a line that ends in else or return, a backslash or an operator holds the line after it; one that
        // ends in an open bracket or a comma holds a statement (it is an argument or an element there), not an element of a list
        List<List<String>> heldBelow = List.of(List.of("if (x) run();", "else", "  it.skip('x', () => {});"), List.of("function f() {", "  return", "  it.skip('x', () => {});"),
                List.of("wrap(", "  it.skip('x', () => {}));"), List.of("const tests = [", "  it.skip('x', () => {}),"), List.of("x = a and \\\\", "pytestmark = pytest.mark.skip"),
                List.of("const t = a +", "  it.skip('x', () => {});"), List.of("Runnable r = cond ? null :", "    () -> {}; assumeTrue(false);"));
        check("integrity: a form on the line after one that ends in else, return, a backslash, an operator, an open bracket or a comma is not sure",
                heldBelow.stream().allMatch(l -> Integrity.lint(l).sure().isEmpty() && !Integrity.lint(l).notes().isEmpty()));
        check("integrity: its neighbours, a pytest.param as an element of a parametrize list, and a declaration in a describe block, are sure",
                Integrity.lint(List.of("@pytest.mark.parametrize('x', [", "    pytest.param(1, marks=pytest.mark.skip),")).sure().size() == 1
                && Integrity.lint(List.of("@pytest.mark.parametrize('x', [", "    pytest.param(0),", "    pytest.param(1, marks=pytest.mark.skip),")).sure().size() == 1
                && Integrity.lint(List.of("describe('x', () => {", "  it.skip('y', () => {});")).sure().size() == 1
                && Integrity.lint(List.of("    }", "    @Test void a() { assumeTrue(false); }")).sure().size() == 1);
        check("integrity: a line that is a note of its own and holds the next: both are said",
                Integrity.lint(List.of("if not has_gpu: pytest.skip('needs a GPU')", "pytestmark = pytest.mark.skip")).equals(
                        noted("if not has_gpu: pytest.skip('needs a GPU')", "if not has_gpu: pytest.skip('needs a GPU') pytestmark = pytest.mark.skip")));
        check("integrity: its plain forms over lines are sure, and so is the same after a block that opens under no condition",
                Integrity.lint(joinedTwo.subList(1, 3)).sure().size() == 1 && Integrity.lint(joinedThree.subList(1, 4)).sure().size() == 1
                && Integrity.lint(List.of("describe('db', () => {", "  describe.skip(", "    'database', () => {});")).sure().size() == 1);
        check("integrity: a line that ends where an expression goes on (=>, ->, =, ?, &&, ||) holds the line after it too",
                Integrity.lint(List.of("Runnable later = () ->", "    assumeTrue(false);")).sure().isEmpty()
                && Integrity.lint(List.of("const later = () =>", "  it.skip('x', () => {});")).sure().isEmpty()
                && Integrity.lint(List.of("const t = isCI ?", "  it.skip('x', () => {}) : null;")).sure().isEmpty());
        // finding 4: an assumption is sure only as a statement of its own
        check("integrity: an assumption in a lambda, caught by an exception assertion, passed or assigned, is a note",
                Integrity.lint(List.of("Runnable later = () -> assumeTrue(false);")).equals(noted("Runnable later = () -> assumeTrue(false);"))
                && Integrity.lint(List.of("assertThrows(TestAbortedException.class, () -> assumeTrue(false));"))
                        .equals(noted("assertThrows(TestAbortedException.class, () -> assumeTrue(false));"))
                && Integrity.lint(List.of("    run(Assumptions.assumeTrue(false));")).sure().isEmpty() && Integrity.lint(List.of("    x = assumeFalse(true);")).sure().isEmpty()
                && Integrity.lint(List.of("    assumeTrue(false) || run();")).sure().isEmpty());
        check("integrity: its plain forms, qualified or not, in a method's body or over two lines, are sure",
                Integrity.lintedSwitch("assumeTrue(false);") && Integrity.lintedSwitch("    Assumptions.assumeTrue(false, \"later\");")
                && Integrity.lintedSwitch("    org.junit.Assume.assumeTrue(\"later\", false);") && Integrity.lintedSwitch("  @Test void a() { assumeTrue(false); }")
                && Integrity.lintedSwitch("    assumeFalse(true) // later") && Integrity.lint(List.of("    assumeTrue(", "        false, \"later\");")).sure().size() == 1);
        // finding 5: complete annotations and modifiers before the switch annotation are a declaration's
        check("integrity: @Test @Disabled, public @Disabled and the switch first, in the middle or last of three annotations are sure",
                Integrity.lintedSwitch("@Test @Disabled void off() {}") && Integrity.lintedSwitch("public @Disabled void off()")
                && Integrity.lintedSwitch("@Disabled @Tag(\"slow\") @Test void a() {}") && Integrity.lintedSwitch("@Tag(\"slow\") @Disabled(\"later\") @Test void a() {}")
                && Integrity.lintedSwitch("@Tag({\"a\", \"b\"}) @Test @Disabled void a() {}") && Integrity.lintedSwitch("  public static @org.junit.Ignore void a() {}"));
        check("integrity: their honest neighbours: in a string a note, in an address nothing, after a word, an argument or a value a note",
                Integrity.lint(List.of("String s = \"see @Disabled\";")).equals(noted("String s = \"see @Disabled\";"))
                && Integrity.lint(List.of("String to = user@Disabled.example;", "mail ci@Ignore.org")).equals(NOTHING)
                && Integrity.lint(List.of("f(@Disabled)")).equals(noted("f(@Disabled)")) && Integrity.lint(List.of("x = @Disabled")).equals(noted("x = @Disabled"))
                && Integrity.lint(List.of("void @Disabled")).equals(noted("void @Disabled")));
    }

    /** A form written over width lines: cut after its first bracket, before its last one, then at its dots and spaces. */
    private static List<String> split(String plain, int width) {
        String t = plain.strip();
        List<Integer> cuts = new java.util.ArrayList<>();
        int open = -1, close = -1;
        for (int i = 0; i < t.length() && open < 0; i++) if (t.charAt(i) == '(' || t.charAt(i) == '[') open = i + 1;
        for (int i = t.length() - 1; i >= 0 && close < 0; i--) if (t.charAt(i) == ')' || t.charAt(i) == ']') close = i;
        if (open > 0) cuts.add(open);
        if (close > open && close > 0) cuts.add(close);
        for (int i = 1; i < t.length(); i++) if (t.charAt(i) == '.' || t.charAt(i) == ' ') cuts.add(i);
        java.util.TreeSet<Integer> chosen = new java.util.TreeSet<>();
        for (int c : cuts) if (chosen.size() < width - 1 && c > 0 && c < t.length()) chosen.add(c);
        List<String> lines = new java.util.ArrayList<>();
        int from = 0;
        for (int c : chosen) {
            lines.add(t.substring(from, c).strip());
            from = c;
        }
        lines.add(t.substring(from).strip());
        return lines;
    }

    /**
     * Audit round 3, findings 6, 7 and 8: lines that made the lint slow. Far above the audit's
     * sizes they are refused by the bound; within it, the same shapes on many lines are read in
     * work that grows as what is given grows. Work is counted (Integrity.lintWork), so a slow
     * machine does not fail these.
     */
    private static void bounded() {
        String comments = "const x = '" + "/* ".repeat(1_000_000) + "';";
        String identifier = "const skip" + "x".repeat(40_000_000) + " = 1;";
        StringBuilder words = new StringBuilder("const a = \"");
        for (int i = 0; i < 300_000; i++) words.append('w').append(i).append("\\u000a");
        String escapes = words.append("\";").toString();
        List<String> huge = List.of(comments, identifier, escapes, escapes, escapes);
        long before = Integrity.lintWork(), started = System.nanoTime();
        List<String> found = Integrity.lintedSwitches(huge);
        long spent = Integrity.lintWork() - before, ms = (System.nanoTime() - started) / 1_000_000;
        check("integrity: lines far longer than the audit's slow ones are not read: no work, and each is counted as unread",
                found.isEmpty() && Integrity.unlinted(huge) == 5 && spent == 0 && ms < 1000);
        // the same shapes within the bound, and lines of nothing but calls: the work for twice the lines is about twice the work
        StringBuilder near = new StringBuilder("const a = \"");
        for (int i = 0; near.length() < 1980; i++) near.append('w').append(i).append("\\u000a");
        List<String> shapes = List.of("const x = '" + "/* ".repeat(660) + "';", "const skip" + "x".repeat(1980) + " = 1;", near + "\";",
                "abort(".repeat(330), "assumeTrue(x, ".repeat(140), "it.".repeat(660) + "x;", "@a.".repeat(660), "test.skip(".repeat(190), "marks=[".repeat(280),
                "it('x', { skip: 1 }, ".repeat(95), "`${`".repeat(490), "'".repeat(1990), "\\u".repeat(990), "it.skip ".repeat(249), "@Test(".repeat(330),
                "@pytest.mark.skipif(".repeat(99),
                // audit round 5: the readers of the gate (nested brackets, +-joined strings, option objects, lists of marks, .each)
                "it.skip('x' + f(" + "([{".repeat(660), "it.skip('a' " + "+ 'b' ".repeat(330), "test('x', " + "{ skip: true, ".repeat(140),
                "it.skip.each(" + "[(".repeat(990), "pytestmark = [" + "pytest.mark.skip, ".repeat(110), "@(" + "pytest.mark.skipif(True) if (".repeat(66),
                "@Test(enabled = false, " + "@Disabled(".repeat(195), "test('x', { ...o, ".repeat(110));
        for (String shape : shapes) {
            long once = work(Collections.nCopies(60, shape)), twice = work(Collections.nCopies(120, shape));
            long chars = 120L * shape.length();
            boolean linear = twice <= 2.2 * once && twice <= 400 * chars;
            if (!linear) System.out.println("    lint work, 60 and 120 lines of " + shape.substring(0, 20) + "...: " + once + ", " + twice + " (" + chars + " characters)");
            check("integrity: lines like " + shape.substring(0, 12).replace('\n', ' ') + "... are read in work linear in what is given", linear);
        }
        for (String shape : List.of("const skip", "/* ", "abort(", "it.", "w0\\u000a", "' ", "it.skip('a' + ", "{ skip: true, ", "([{", "@pytest.mark.skipif(True, ")) {
            int n = 1990 / shape.length();
            long half = work(List.of(shape.repeat(n / 2))), full = work(List.of(shape.repeat(n)));
            check("integrity: one line of '" + shape + "' twice as long is about twice the work", full <= 2.4 * half + 2000);
        }
        // many short lines, as a large honest change has
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 200_000; i++) many.add(i % 7 == 0 ? "  it('adds " + i + "', () => {" : "    expect(sum(" + i + ", 1)).toBe(" + (i + 1) + ");" + (i % 10 == 0 ? " // skip nothing" : ""));
        long manyStarted = System.nanoTime(), manyWork = work(many);
        long manyChars = many.stream().mapToLong(String::length).sum();
        check("integrity: two hundred thousand short added lines are linted in work linear in them, and soon",
                manyWork <= 400 * manyChars && (System.nanoTime() - manyStarted) / 1_000_000 < 20_000);
    }

    private static long work(List<String> lines) {
        long before = Integrity.lintWork();
        Integrity.lintedSwitches(lines);
        return Integrity.lintWork() - before;
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

    /** What a caller of the scan printed, on standard output and on standard error, and its exit status. */
    private record Said(int code, String out, String err) {}

    /** Runs sdlc check with these options, the host's input on standard input (for the stop hook). */
    private static Said checkWith(Map<String, String> options) {
        PrintStream out = System.out, err = System.err;
        java.io.InputStream in = System.in;
        ByteArrayOutputStream said = new ByteArrayOutputStream(), errors = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(said, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            System.setIn(new java.io.ByteArrayInputStream("{\"hook_event_name\": \"Stop\", \"stop_hook_active\": false}".getBytes(StandardCharsets.UTF_8)));
            int code = Check.run(options);
            return new Said(code, said.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
            System.setIn(in);
        }
    }

    /**
     * Audit round 5, finding 7: the lint's two levels through the callers of the scan, not the scan
     * alone: sdlc check, with and without its run, the stop hook and --since. What the lint is sure
     * of fails (check, the stop hook); a note is said and never fails; --since reports problems
     * only and never fails.
     */
    private static void callers(Path repo) throws IOException {
        String sureLine = "it.skip('later', () => {})", noteLines = "test.skip('webkit'\n  === browserName, 'webkit only');\n";
        String sure = "PROBLEM  test switched off: web/switch.test.ts  " + sureLine;
        String note = "note     may switch a test off: web/switch.test.ts  the run of the file judges it: test.skip('webkit' === browserName, 'webkit only');";
        write(repo, "web/switch.test.ts", sureLine + "\n" + noteLines);
        Said noRun = checkWith(Map.of("no-run", "true"));
        check("integrity, through sdlc check --no-run: a sure switch fails, by its line; a note beside it is said as a note",
                noRun.code() == 1 && noRun.out().contains(sure) && noRun.out().contains(note));
        Said ran = checkWith(Map.of("selftest-runner", "true", "runs", "1"));
        check("integrity, through sdlc check: the same", ran.code() == 1 && ran.out().contains(sure) && ran.out().contains(note));
        Said hook = checkWith(Map.of("selftest-runner", "true", "stop-hook", "true"));
        check("integrity, through the stop hook: a sure switch blocks, with its line; the note is not a reason",
                hook.code() == 2 && hook.err().contains("- test switched off: web/switch.test.ts (" + sureLine + ")") && !hook.err().contains("webkit"));
        write(repo, "web/switch.test.ts", noteLines);
        Said alone = checkWith(Map.of("no-run", "true"));
        Said aloneRan = checkWith(Map.of("selftest-runner", "true", "runs", "1"));
        Said quiet = checkWith(Map.of("selftest-runner", "true", "stop-hook", "true"));
        check("integrity, through sdlc check and the stop hook: a note alone never fails, and the stop hook says nothing",
                alone.code() == 0 && alone.out().contains(note) && !alone.out().contains("PROBLEM") && aloneRan.code() == 0 && aloneRan.out().contains(note)
                && quiet.code() == 0 && quiet.err().isBlank());
        write(repo, "web/switch.test.ts", sureLine + "\n" + noteLines);
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "a switch and a note");
        try {
            Said since = checkWith(Map.of("since", "1d", "max", "1", "no-run", "true"));
            check("integrity, through sdlc check --since: the commit's sure switch is a problem in the report, the note is not, and the report never fails",
                    since.code() == 0 && since.out().contains("    " + sure) && since.out().contains("1 integrity problem") && !since.out().contains("webkit"));
        } finally {
            git(repo, "reset", "-q", "--hard", "HEAD~1");
        }
        Files.deleteIfExists(repo.resolve("web/switch.test.ts"));
        // audit round 6, findings 2 to 5 and 7, through sdlc check --no-run and the stop hook: what the audit found sure is a note,
        // what it found a note is a problem, and the literal that runs is nothing
        write(repo, "web/round6.test.ts", "test('x', { skip: true, 'skip': false }, fn);\nif (isCI) {\n  describe.skip(\n    'database', () => {});\n}\n");
        write(repo, "src/test/java/demo/Round6Test.java", "class Round6Test {\n    @Test @Disabled void off() {}\n    @DisabledIf(\"false\") @Test void on() {}\n"
                + "    @Test void caught() { assertThrows(TestAbortedException.class, () -> assumeTrue(false)); }\n}\n");
        Said round6 = checkWith(Map.of("no-run", "true"));
        String out6 = round6.out();
        check("integrity, through sdlc check --no-run: @Test @Disabled fails; a quoted override, a condition on the line before a joined declaration and a caught assumption are notes; @DisabledIf(\"false\") is nothing",
                round6.code() == 1 && out6.contains("PROBLEM  test switched off: src/test/java/demo/Round6Test.java  @Test @Disabled void off() {}")
                && out6.contains("note     may switch a test off: web/round6.test.ts  the run of the file judges it: test('x', { skip: true, 'skip': false }, fn);")
                && out6.contains("note     may switch a test off: web/round6.test.ts  the run of the file judges it: if (isCI) { describe.skip( 'database', () => {});")
                && out6.contains("note     may switch a test off: src/test/java/demo/Round6Test.java  the run of the file judges it: @Test void caught()")
                && !out6.contains("PROBLEM  test switched off: web/round6.test.ts") && !out6.contains("DisabledIf"));
        Files.delete(repo.resolve("src/test/java/demo/Round6Test.java"));
        Said held = checkWith(Map.of("selftest-runner", "true", "stop-hook", "true"));
        check("integrity, through the stop hook: those notes alone do not block", held.code() == 0 && held.err().isBlank());
        reset(repo);
    }

    /**
     * Audit round 4, finding 8: a deleted test file is read by all its lines in one pass. Shapes
     * that made the reading search the rest of the file again for each line are read in work
     * linear in the file (counted, see Integrity.lintWork), and soon.
     */
    private static void deletedCost(Path repo) throws IOException {
        StringBuilder rows = new StringBuilder();
        while (rows.length() < 5_000_000) rows.append("    *rows,\n");
        java.util.Map<String, String> shapes = new java.util.LinkedHashMap<>();
        shapes.put("tests/stars.py", "*a\n".repeat(200_000));
        shapes.put("tests/big_helper.py", "rows = [\n" + rows + "]\n");
        shapes.put("web/__tests__/unclosed.ts", "/* x\n".repeat(200_000));
        shapes.put("web/__tests__/opened.ts", "\"\"\" x\n".repeat(200_000));
        shapes.put("web/__tests__/chain.ts", "it.".repeat(1_000_000) + "\n" + "@a.".repeat(1_000_000) + "\n");
        for (java.util.Map.Entry<String, String> e : shapes.entrySet()) {
            write(repo, e.getKey(), e.getValue());
            git(repo, "add", "-A");
            git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "a large helper");
            Files.delete(repo.resolve(e.getKey()));
            long before = Integrity.lintWork(), started = System.nanoTime();
            List<Finding> gone = scan(repo);
            long spent = Integrity.lintWork() - before, ms = (System.nanoTime() - started) / 1_000_000;
            boolean ok = spent <= 100L * e.getValue().length() && ms < 10_000 && gone.stream().anyMatch(f -> f.path().equals(e.getKey()));
            if (!ok) System.out.println("    deleted " + e.getKey() + ": work " + spent + " for " + e.getValue().length() + " characters, " + ms + " ms, " + gone);
            check("integrity: deleting " + e.getKey() + " (" + e.getValue().length() / 1000 + " kB) is read in work linear in it, and soon", ok);
            git(repo, "reset", "-q", "--hard", "HEAD~1");
        }
    }

    /**
     * Audit round 6, finding 1: the readers of removed and added lines (a package or import line,
     * the move matching, the test calls, the assumption counts, a pom's minimums) read lines of at
     * most Integrity.LONGEST characters, and their patterns walk such a line once. Through sdlc
     * check --no-run, on a file deleted and on the same file moved to another package: the audit's
     * shape at 400,000 spaces (one line, longer than the bound), the same for an import line, and
     * three shapes within the bound, 2 MB each, aimed at the patterns that were rewritten.
     */
    private static void movedCost(Path repo) throws IOException {
        String rest = "\nimport org.junit.jupiter.api.Test;\n\nclass SpaceTest {\n    @Test\n    void adds() {}\n}\n";
        StringBuilder packages = new StringBuilder(), imports = new StringBuilder(), minimums = new StringBuilder("<project>\n");
        for (int i = 0; i < 1000; i++) packages.append("package demo").append(" ".repeat(1980)).append("; // c\n");
        for (int i = 0; i < 1000; i++) imports.append("import static demo.Calc.").append(" ".repeat(1970)).append("x\n");
        for (int i = 0; i < 900; i++) minimums.append("<minimum>0.8").append(" ".repeat(1980)).append("x\n");
        minimums.append("</project>\n");
        record Shape(String name, String path, String text, String moved, String movedText, String deleted, String afterMove) {}
        String longPackage = "package demo" + " ".repeat(400_000) + "; // comment\n";
        String longImport = "import demo.Calc" + " ".repeat(400_000) + "; // comment\n";
        List<Shape> shapes = List.of(
                new Shape("a package line of 400,000 spaces", "src/test/java/demo/SpaceTest.java", longPackage + rest,
                        "src/test/java/demo/moved/SpaceTest.java", "package demo.moved;\n" + rest, "test file deleted", "test file deleted"),
                new Shape("an import line of 400,000 spaces", "src/test/java/demo/SpaceTest.java", "package demo;\n" + longImport + rest,
                        "src/test/java/demo/moved/SpaceTest.java", "package demo.moved;\n" + longImport + rest, "test file deleted", "test file moved"),
                new Shape("1,000 package lines of 1,980 spaces", "src/test/java/demo/SpaceTest.java", packages + rest,
                        "src/test/java/demo/moved/SpaceTest.java", "package demo.moved;\n" + packages + rest, "test file deleted", "test file moved"),
                new Shape("1,000 import lines of 1,970 spaces", "src/test/java/demo/SpaceTest.java", "package demo;\n" + imports + rest,
                        "src/test/java/demo/moved/SpaceTest.java", "package demo.moved;\n" + rest, "test file deleted", "test file deleted"),
                new Shape("900 minimum lines of 1,980 spaces in a pom", "sub/pom.xml", minimums.toString(), "other/pom.xml", minimums.toString(), "build configuration changed",
                        "build configuration changed"));
        for (Shape s : shapes) {
            write(repo, s.path(), s.text());
            git(repo, "add", "-A");
            git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "a file of long lines");
            Files.delete(repo.resolve(s.path()));
            for (boolean move : List.of(false, true)) {
                if (move) write(repo, s.moved(), s.movedText());
                long before = Integrity.lintWork(), started = System.nanoTime();
                Said said = checkWith(Map.of("no-run", "true"));
                long spent = Integrity.lintWork() - before, ms = (System.nanoTime() - started) / 1_000_000;
                String expected = move ? s.afterMove() : s.deleted();
                boolean ok = ms < 3000 && spent <= 100L * (s.text().length() + (move ? s.movedText().length() : 0)) && said.out().contains(expected + ": " + s.path());
                if (!ok || System.getenv("SDLC_COST") != null) System.out.println("    " + (move ? "moved " : "deleted ") + s.name() + ": " + ms + " ms, work " + spent + (ok ? "" : "\n" + said.out()));
                check("integrity, through sdlc check --no-run: " + (move ? "moving" : "deleting") + " a file of " + s.name() + " is read in work linear in it, and soon (" + expected + ")", ok);
            }
            reset(repo);
            git(repo, "reset", "-q", "--hard", "HEAD~1");
        }
        // a deleted file beside the tests whose only long line is not read: a note that says so, never a problem from that line
        write(repo, "tests/helpers.py", "def helper():\n    return 1\nDATA = [" + "1, ".repeat(1000) + "]\n");
        write(repo, "tests/cases.py", "DATA = '" + "x".repeat(3000) + "'\n\ndef test_real():\n    assert True\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "helpers with a long line");
        Files.delete(repo.resolve("tests/helpers.py"));
        Files.delete(repo.resolve("tests/cases.py"));
        List<Finding> gone = scan(repo);
        check("integrity: a deleted helper with a line too long to read is a note that says the line was not read; a test call on another line is still a test file deleted",
                only(gone, Level.NOTE, "test support file deleted", "tests/helpers.py", "longer than 2000 characters and was not read")
                && only(gone, Level.PROBLEM, "test file deleted", "tests/cases.py", "the file is gone"));
        git(repo, "reset", "-q", "--hard", "HEAD~1");
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
